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
 * @property memoryCeilingBytes Past this resident memory a replica admits no call; null without a container limit.
 */
data class GatewayConfig(
    val keys: InstanceProviderKeys,
    val endpoints: ProviderEndpoints,
    val maxOpenCalls: Int = DEFAULT_MAX_OPEN_CALLS,
    val memoryCeilingBytes: Long? = null,
) {
    /** Reading the configuration. */
    companion object {
        /** Without a container limit (a plain process, a test): nothing to derive the ceiling from. */
        const val DEFAULT_MAX_OPEN_CALLS = 100

        /**
         * What a replica keeps at rest once it has served calls, and what an open stream adds as it opens, in MiB:
         * measured by chart-load 41628386596 on the native binary (see docs/MODULES.md).
         */
        private const val AT_REST_MIB = 150L
        private const val PER_CALL_MIB = 4L
        private const val MIN_OPEN_CALLS = 10
        private const val MIB = 1024L * 1024
        private const val DEFAULT_MEMORY_CEILING_PERCENT = 80
        private const val WHOLE = 100L

        /**
         * Reads `OSTROM_GATEWAY_*`. Without `OSTROM_GATEWAY_MAX_OPEN_CALLS`, the ceiling of open calls comes from the
         * container's memory limit: (limit - 150 MiB) / 4 MiB, at least 10.
         *
         * @param memoryLimitBytes The container's memory limit, null without one.
         * @throws InvalidConfigException When a URL is not http(s), the ceiling of open calls not an integer from 1
         *   to 100 000, or the memory ceiling not a percentage from 1 to 100.
         */
        fun from(source: ConfigSource, memoryLimitBytes: Long? = null): GatewayConfig {
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
                maxOpenCalls = source.openCalls(memoryLimitBytes),
                memoryCeilingBytes = memoryLimitBytes?.let { share(it, source.memoryCeilingPercent()) },
            )
        }

        private fun ConfigSource.openCalls(memoryLimitBytes: Long?): Int {
            val raw = this[MAX_OPEN_CALLS] ?: return memoryLimitBytes?.let(::openCallsHeldBy) ?: DEFAULT_MAX_OPEN_CALLS
            return raw.toIntOrNull()?.takeIf { it in OPEN_CALLS }
                ?: throw InvalidConfigException(
                    "$MAX_OPEN_CALLS must be an integer between ${OPEN_CALLS.first} and ${OPEN_CALLS.last}.",
                )
        }

        private fun openCallsHeldBy(limitBytes: Long): Int {
            val calls = (limitBytes / MIB - AT_REST_MIB) / PER_CALL_MIB
            return calls.coerceIn(MIN_OPEN_CALLS.toLong(), OPEN_CALLS.last.toLong()).toInt()
        }

        /** [percent] % of [bytes], exact and without overflow up to 2^60 bytes. */
        private fun share(bytes: Long, percent: Int): Long = bytes / WHOLE * percent + bytes % WHOLE * percent / WHOLE

        private fun ConfigSource.memoryCeilingPercent(): Int {
            val raw = this[MEMORY_CEILING_PERCENT] ?: return DEFAULT_MEMORY_CEILING_PERCENT
            return raw.toIntOrNull()?.takeIf { it in PERCENT }
                ?: throw InvalidConfigException(
                    "$MEMORY_CEILING_PERCENT must be an integer between ${PERCENT.first} and ${PERCENT.last}.",
                )
        }

        private const val MAX_OPEN_CALLS = "OSTROM_GATEWAY_MAX_OPEN_CALLS"
        private const val MEMORY_CEILING_PERCENT = "OSTROM_GATEWAY_MEMORY_CEILING_PERCENT"
        private val OPEN_CALLS = 1..100_000
        private val PERCENT = 1..100

        private fun ConfigSource.url(key: String, default: String): String {
            val value = this[key] ?: default
            if (!value.startsWith("https://") && !value.startsWith("http://")) {
                throw InvalidConfigException("$key must be an http(s) URL.")
            }
            return value.trimEnd('/')
        }
    }
}
