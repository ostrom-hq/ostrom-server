package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import io.ktor.http.Url
import io.ktor.http.hostWithPortIfSpecified

/**
 * Where the API and the console are served, read once at start (docs/MODULES.md, configuration table).
 *
 * @property publicUrl The API's public URL (`OSTROM_PUBLIC_URL`, `https://api.ostrom.run`), without a trailing slash.
 * @property consoleUrl The console's URL (`OSTROM_CONSOLE_URL`, `https://app.ostrom.run`), without a trailing slash:
 *   the device-login page, and the only place sign-ins redirect to.
 * @property allowedOrigins The origins a browser session may change state from (`OSTROM_ALLOWED_ORIGINS`, a comma
 *   list such as `https://app.ostrom.run,https://yes.ostrom.run`; the console's origin when unset): a request with a
 *   session cookie and another `Origin`, or none, is refused (audit S2-08).
 */
data class IdentityConfig(
    val publicUrl: String,
    val consoleUrl: String,
    val allowedOrigins: Set<String> = setOf(originOf(consoleUrl)),
) {
    /** Cookies are `Secure` whenever the API is served over HTTPS (always, outside a developer's machine). */
    val secureCookies: Boolean get() = publicUrl.startsWith("https://")

    /**
     * The server's callback URL for a provider, registered in the provider's settings (GESTES-LYES.md).
     *
     * @param provider The provider.
     *
     * @return The URL.
     */
    fun callbackUrl(provider: IdentityProvider): String = "$publicUrl/api/v1/auth/${provider.key}/callback"

    /**
     * The console page an e-mailed link opens; the token is in the fragment (see `VerifyMagicLinkRequest`).
     *
     * @param token The link's token.
     *
     * @return The URL.
     */
    fun magicLinkUrl(token: String): String = "$consoleUrl/auth/email#token=$token"

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
        fun from(source: ConfigSource): IdentityConfig {
            val consoleUrl = url(source, "OSTROM_CONSOLE_URL", "http://localhost:5173")
            val listed = source["OSTROM_ALLOWED_ORIGINS"].orEmpty().split(',').map(String::trim)
                .filter(String::isNotEmpty)
            return IdentityConfig(
                publicUrl = url(source, "OSTROM_PUBLIC_URL", "http://localhost:8080"),
                consoleUrl = consoleUrl,
                allowedOrigins = if (listed.isEmpty()) setOf(originOf(consoleUrl)) else listed.map(::origin).toSet(),
            )
        }

        /**
         * The origin of a URL as a browser sends it in `Origin`: scheme, host and a port only when not the default.
         *
         * @param url An `http://` or `https://` URL.
         *
         * @return The origin.
         */
        fun originOf(url: String): String {
            val parsed = Url(url)
            return "${parsed.protocol.name}://${parsed.hostWithPortIfSpecified}"
        }

        private fun origin(value: String): String {
            val trimmed = value.trimEnd('/').lowercase()
            val web = trimmed.startsWith("https://") || trimmed.startsWith("http://")
            if (!web || originOf(trimmed) != trimmed) {
                throw InvalidConfigException("OSTROM_ALLOWED_ORIGINS lists origins only: scheme://host[:port].")
            }
            return trimmed
        }

        private fun url(source: ConfigSource, key: String, default: String): String {
            val value = source[key]?.trim()?.takeIf { it.isNotEmpty() } ?: default
            if (!value.startsWith("https://") && !value.startsWith("http://")) {
                throw InvalidConfigException("$key must be an http:// or https:// URL.")
            }
            return value.trimEnd('/')
        }
    }
}
