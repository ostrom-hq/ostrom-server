// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.infrastructure.HttpOAuthRefresher

/**
 * The sharing module's configuration, from the instance (Docker environment, Helm values): the providers' OAuth token
 * endpoints, for a proxy or the local demo's fake provider. The variables are listed in docs/MODULES.md.
 */
object SharingConfig {
    /** The variable that sets each provider's token endpoint. */
    val TOKEN_URL_KEYS: Map<AccountProvider, String> = mapOf(
        AccountProvider.CLAUDE to "OSTROM_SHARING_CLAUDE_TOKEN_URL",
        AccountProvider.CODEX to "OSTROM_SHARING_CODEX_TOKEN_URL",
    )

    /**
     * Reads the token endpoints, the providers' own by default.
     *
     * @param source The instance's configuration.
     *
     * @return The endpoint per provider.
     *
     * @throws InvalidConfigException When a URL is not http(s).
     */
    fun tokenEndpoints(source: ConfigSource): Map<AccountProvider, String> =
        HttpOAuthRefresher.ENDPOINTS.mapValues { (provider, default) ->
            val key = TOKEN_URL_KEYS.getValue(provider)
            val url = source[key] ?: default
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw InvalidConfigException("$key must be an http(s) URL.")
            }
            url
        }
}
