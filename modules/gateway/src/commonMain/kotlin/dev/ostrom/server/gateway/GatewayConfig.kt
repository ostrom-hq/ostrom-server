// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.InstanceProviderKeys
import dev.ostrom.server.gateway.domain.ProviderEndpoints
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException

/**
 * The gateway's configuration, from the instance (Docker environment, Helm values): provider keys that every org
 * uses (they come before an org's own key), and provider URLs (proxies, tests). The variables are listed in
 * docs/MODULES.md.
 *
 * @property keys The instance's provider keys.
 * @property endpoints The provider URLs.
 * @property maxOpenCalls The model calls one replica relays at once (`OSTROM_GATEWAY_MAX_OPEN_CALLS`).
 */
data class GatewayConfig(
    val keys: InstanceProviderKeys,
    val endpoints: ProviderEndpoints,
    val maxOpenCalls: Int = DEFAULT_MAX_OPEN_CALLS,
) {
    /** Reading the configuration. */
    companion object {
        /** See docs/MODULES.md: what the chart's 256 MiB limit holds, measured by chart-load. */
        const val DEFAULT_MAX_OPEN_CALLS = 100

        /**
         * Reads `OSTROM_GATEWAY_*`.
         *
         * @throws InvalidConfigException When a URL is not http(s).
         */
        fun from(source: ConfigSource): GatewayConfig {
            val defaults = ProviderEndpoints()
            return GatewayConfig(
                keys = InstanceProviderKeys(
                    anthropic = source["OSTROM_GATEWAY_ANTHROPIC_API_KEY"],
                    openai = source["OSTROM_GATEWAY_OPENAI_API_KEY"],
                    openrouter = source["OSTROM_GATEWAY_OPENROUTER_API_KEY"],
                ),
                endpoints = ProviderEndpoints(
                    anthropic = source.url("OSTROM_GATEWAY_ANTHROPIC_URL", defaults.anthropic),
                    openai = source.url("OSTROM_GATEWAY_OPENAI_URL", defaults.openai),
                    openrouter = source.url("OSTROM_GATEWAY_OPENROUTER_URL", defaults.openrouter),
                    codex = source.url("OSTROM_GATEWAY_CODEX_URL", defaults.codex),
                ),
            )
        }

        private fun ConfigSource.url(key: String, default: String): String {
            val value = this[key] ?: default
            if (!value.startsWith("https://") && !value.startsWith("http://")) {
                throw InvalidConfigException("$key must be an http(s) URL.")
            }
            return value.trimEnd('/')
        }
    }
}
