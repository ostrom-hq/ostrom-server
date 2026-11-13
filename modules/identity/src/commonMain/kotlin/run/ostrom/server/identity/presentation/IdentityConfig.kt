// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.domain.trimmedOrNull
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.auth.HOST_COOKIE_PREFIX
import run.ostrom.server.kernel.config.InvalidConfigException
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
 * @property signUp Who may get a new account (`OSTROM_SIGNUP`: `invite`, the default, or `open`).
 */
data class IdentityConfig(
    val publicUrl: String,
    val consoleUrl: String,
    val allowedOrigins: Set<String> = setOf(originOf(consoleUrl)),
    val signUp: SignUpMode = SignUpMode.INVITE,
) {
    /** Cookies are `Secure` whenever the API is served over HTTPS (always, outside a developer's machine). */
    val secureCookies: Boolean get() = publicUrl.startsWith("https://")

    /** `__Host-` on the session cookies over HTTPS, nothing on a plain-HTTP development server (audit S2-12). */
    val sessionCookiePrefix: String get() = if (secureCookies) HOST_COOKIE_PREFIX else ""

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

    /**
     * The console page the instance's setup link opens; the token is in the fragment, as an e-mailed link's.
     *
     * @param token The setup link's token.
     *
     * @return The URL.
     */
    fun setupUrl(token: String): String = "$consoleUrl/setup#token=$token"

    /** Reads the configuration from the environment. */
    companion object {
        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When a URL is not `http://` or `https://`, or `OSTROM_SIGNUP` is neither
         *   `invite` nor `open`.
         */
        fun from(source: ConfigSource): IdentityConfig {
            val consoleUrl = url(source, "OSTROM_CONSOLE_URL", "http://localhost:5173")
            val listed = source["OSTROM_ALLOWED_ORIGINS"].orEmpty().split(',').map(String::trim)
                .filter(String::isNotEmpty)
            return IdentityConfig(
                publicUrl = url(source, "OSTROM_PUBLIC_URL", "http://localhost:8080"),
                consoleUrl = consoleUrl,
                allowedOrigins = if (listed.isEmpty()) setOf(originOf(consoleUrl)) else listed.map(::origin).toSet(),
                signUp = SignUpMode.of(trimmedOrNull(source["OSTROM_SIGNUP"], Int.MAX_VALUE))
                    ?: throw InvalidConfigException("OSTROM_SIGNUP is invite (the default) or open."),
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
            val value = trimmedOrNull(source[key], Int.MAX_VALUE) ?: default
            if (!value.startsWith("https://") && !value.startsWith("http://")) {
                throw InvalidConfigException("$key must be an http:// or https:// URL.")
            }
            return value.trimEnd('/')
        }
    }
}
