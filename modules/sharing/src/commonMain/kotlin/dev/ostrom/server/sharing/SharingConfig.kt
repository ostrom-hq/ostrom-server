// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.infrastructure.HttpOAuthRefresher

/**
 * The sharing module's configuration, from the instance (Docker environment, Helm values): the providers' OAuth token
 * endpoints, for a proxy or the local demo's fake provider. The refresh tokens are sent there, so an endpoint is https,
 * or http to a local host only. The variables are listed in docs/MODULES.md.
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
                    "*.localtest.me).",
            )
        }
        return value
    }

    private fun allowed(url: String): Boolean {
        val scheme = url.substringBefore("://").lowercase()
        val host = url.substringAfter("://", "").substringBefore('/').substringAfterLast('@').substringBefore(':')
            .lowercase()
        return if (scheme == "https") host.isNotEmpty() else scheme == "http" && local(host)
    }

    private fun local(host: String): Boolean = host in LOCAL_HOSTS || LOCAL_SUFFIXES.any { host.endsWith(it) }
}
