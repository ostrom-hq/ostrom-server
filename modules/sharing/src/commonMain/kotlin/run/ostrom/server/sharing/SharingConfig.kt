// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.infrastructure.HttpOAuthRefresher
import io.ktor.http.Url

/**
 * The sharing module's configuration, from the instance (Docker environment, Helm values): the providers' OAuth token
 * endpoints, for a proxy or the local demo's fake provider. The refresh tokens are sent there, so an endpoint is https,
 * or http to a local host only. The variables are listed in docs/MODULES.md.
 *
 * An endpoint is read by a strict grammar (scheme, host of letters, digits, dots and hyphens, an optional port, then a
 * path or a query), and the decision is taken on the host the HTTP client parses, never on the raw string: a `#`, an
 * `@` (credentials), a backslash or a host the grammar does not know is refused, so `http://evil.example#@localhost/`
 * can never pass for a local host.
 */
object SharingConfig {
    /** The variable that sets each provider's token endpoint. */
    val TOKEN_URL_KEYS: Map<AccountProvider, String> = mapOf(
        AccountProvider.CLAUDE to "OSTROM_SHARING_CLAUDE_TOKEN_URL",
        AccountProvider.CODEX to "OSTROM_SHARING_CODEX_TOKEN_URL",
    )

    /** The hosts reached in clear: this machine, and the names that resolve to it. */
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".localtest.me")

    /**
     * Scheme, host, optional port, then nothing or a path or query. A host is dot-separated labels of letters, digits
     * and hyphens (a name or an IPv4 address), so the HTTP client parses every endpoint the grammar accepts.
     */
    private val ENDPOINT = Regex(
        """(https?)://([a-z0-9-]+(?:\.[a-z0-9-]+)*)(?::([0-9]{1,5}))?(?:[/?].*)?""",
        RegexOption.IGNORE_CASE,
    )
    private const val PORT_GROUP = 3

    /** Characters that move the authority (`#`, `@`) or that the parsers read differently (`\\`). */
    private val FORBIDDEN = setOf('#', '@', '\\')

    private const val MAX_PORT = 65_535

    /**
     * Reads the token endpoints, the providers' own by default.
     *
     * @param source The instance's configuration.
     *
     * @return The endpoint per provider.
     *
     * @throws InvalidConfigException When an endpoint is not https, or http to a local host.
     */
    fun tokenEndpoints(source: ConfigSource): Map<AccountProvider, String> =
        HttpOAuthRefresher.ENDPOINTS.mapValues { (provider, default) ->
            val key = TOKEN_URL_KEYS.getValue(provider)
            val value = source[key]
            if (value == null) default else checked(key, value.trim())
        }

    private fun checked(key: String, value: String): String {
        if (value.any { it.isWhitespace() } || !allowed(value)) {
            throw InvalidConfigException(
                "$key must be an https URL, or http to a local host (localhost, 127.0.0.1, *.localhost, " +
                    "*.localtest.me), with a plain host name or IPv4 address, a port up to 65535, and no " +
                    "credentials (@), fragment (#) or backslash.",
            )
        }
        return value
    }

    private fun allowed(value: String): Boolean {
        val match = ENDPOINT.matchEntire(value)
        if (match == null || value.any { it in FORBIDDEN } || port(match) > MAX_PORT) return false
        // The host the client will call, as Ktor parses it: the grammar above guarantees it parses.
        val url = Url(value)
        return url.protocol.name.lowercase() == "https" || local(url.host.lowercase())
    }

    /** The endpoint's port, 0 when it has none (an unmatched group is empty). */
    private fun port(match: MatchResult): Int = match.groupValues[PORT_GROUP].toIntOrNull() ?: 0

    private fun local(host: String): Boolean = host in LOCAL_HOSTS || LOCAL_SUFFIXES.any { host.endsWith(it) }
}
