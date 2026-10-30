package dev.ostrom.server.identity.presentation

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException

/**
 * Where the API and the console are served, read once at start (docs/MODULES.md, configuration table).
 *
 * @property publicUrl The API's public URL (`OSTROM_PUBLIC_URL`, `https://api.ostrom.run`), without a trailing slash.
 * @property consoleUrl The console's URL (`OSTROM_CONSOLE_URL`, `https://app.ostrom.run`), without a trailing slash:
 *   the device-login page, and the only place sign-ins redirect to.
 */
data class IdentityConfig(
    val publicUrl: String,
    val consoleUrl: String,
) {
    /** Cookies are `Secure` whenever the API is served over HTTPS (always, outside a developer's machine). */
    val secureCookies: Boolean get() = publicUrl.startsWith("https://")

    companion object {
        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When a URL is not `http://` or `https://`.
         */
        fun from(source: ConfigSource): IdentityConfig = IdentityConfig(
            publicUrl = url(source, "OSTROM_PUBLIC_URL", "http://localhost:8080"),
            consoleUrl = url(source, "OSTROM_CONSOLE_URL", "http://localhost:5173"),
        )

        private fun url(source: ConfigSource, key: String, default: String): String {
            val value = source[key]?.trim()?.takeIf { it.isNotEmpty() } ?: default
            if (!value.startsWith("https://") && !value.startsWith("http://")) {
                throw InvalidConfigException("$key must be an http:// or https:// URL.")
            }
            return value.trimEnd('/')
        }
    }
}
