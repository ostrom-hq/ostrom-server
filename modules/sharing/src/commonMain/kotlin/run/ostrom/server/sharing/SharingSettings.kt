// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.infrastructure.HttpOAuthRefresher
import io.ktor.http.Url

/**
 * The sharing module's settings (Admin → Settings → sharing): the providers' OAuth token endpoints, for a proxy or the
 * end-to-end bench's fake provider, set by an administrator of the instance and read at each refresh. The refresh
 * tokens are sent there, so an endpoint is https, or http to a local host only. `OSTROM_SHARING_CLAUDE_TOKEN_URL` and
 * `OSTROM_SHARING_CODEX_TOKEN_URL` are imported once.
 *
 * An endpoint is read by a strict grammar (scheme, host of letters, digits, dots and hyphens, an optional port, then a
 * path or a query), and the decision is taken on the host the HTTP client parses, never on the raw string: a `#`, an
 * `@` (credentials), a backslash or a host the grammar does not know is refused, so `http://evil.example#@localhost/`
 * can never pass for a local host.
 */
object SharingSettings {
    /** The field that sets each provider's token endpoint. */
    val TOKEN_URL_FIELDS: Map<AccountProvider, String> = mapOf(
        AccountProvider.CLAUDE to "claudeTokenUrl",
        AccountProvider.CODEX to "codexTokenUrl",
    )

    private val LEGACY_VARIABLES = mapOf(
        AccountProvider.CLAUDE to "OSTROM_SHARING_CLAUDE_TOKEN_URL",
        AccountProvider.CODEX to "OSTROM_SHARING_CODEX_TOKEN_URL",
    )

    /** The section the admin module serves and stores. */
    val SECTION: SettingsSection = SettingsSection(
        "sharing",
        TOKEN_URL_FIELDS.map { (provider, field) ->
            SettingsField(field, legacyVariable = LEGACY_VARIABLES.getValue(provider), check = { refusal(field, it) })
        },
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
     * The token endpoints a stored section sets, the providers' own for those unset.
     *
     * @param values The stored section.
     *
     * @return The endpoint per provider.
     */
    fun tokenEndpoints(values: SettingsValues): Map<AccountProvider, String> =
        HttpOAuthRefresher.ENDPOINTS.mapValues { (provider, default) ->
            values[TOKEN_URL_FIELDS.getValue(provider)]?.trim() ?: default
        }

    /**
     * Why the console refuses an endpoint.
     *
     * @param field The field's name.
     * @param value The endpoint.
     *
     * @return The refusal, or null when the endpoint is https, or http to a local host.
     */
    fun refusal(field: String, value: String): String? = if (value.any { it.isWhitespace() } || !allowed(value)) {
        "$field must be an https URL, or http to a local host (localhost, 127.0.0.1, *.localhost, " +
            "*.localtest.me), with a plain host name or IPv4 address, a port up to 65535, and no " +
            "credentials (@), fragment (#) or backslash."
    } else {
        null
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
