package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.infrastructure.OAuthClient
import dev.ostrom.server.identity.infrastructure.OidcEndpoints
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException

/**
 * The sign-in methods' credentials, read once at start. A method whose credentials are absent is simply not offered;
 * half a pair (an id without its secret) refuses to start.
 *
 * @property github The GitHub App's OAuth client (`OSTROM_GITHUB_CLIENT_ID`, `OSTROM_GITHUB_CLIENT_SECRET`).
 * @property githubWebUrl GitHub's web URL (`OSTROM_GITHUB_WEB_URL`; a fake in end-to-end tests).
 * @property githubApiUrl GitHub's API URL (`OSTROM_GITHUB_API_URL`).
 * @property google Google's OAuth client (`OSTROM_GOOGLE_CLIENT_ID`, `OSTROM_GOOGLE_CLIENT_SECRET`).
 * @property googleEndpoints Google's endpoints (`OSTROM_GOOGLE_ISSUER`, `OSTROM_GOOGLE_AUTHORIZATION_URL`,
 *   `OSTROM_GOOGLE_TOKEN_URL` replace them together, for a fake in end-to-end tests).
 * @property resendApiKey Resend's key (`OSTROM_RESEND_API_KEY`).
 * @property mailFrom The sender of sign-in e-mails (`OSTROM_MAIL_FROM`).
 * @property mailDirectory A directory sign-in e-mails are written to instead of sent (`OSTROM_MAIL_DIR`), for a
 *   developer's machine and end-to-end tests; ignored when Resend is configured.
 */
data class SignInConfig(
    val github: OAuthClient?,
    val githubWebUrl: String,
    val githubApiUrl: String,
    val google: OAuthClient?,
    val googleEndpoints: OidcEndpoints,
    val resendApiKey: String?,
    val mailFrom: String,
    val mailDirectory: String?,
) {
    companion object {
        /**
         * Reads the configuration.
         *
         * @param source The configuration source.
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When a client id comes without its secret or the reverse, or the Google
         *   endpoints are only partly replaced.
         */
        fun from(source: ConfigSource): SignInConfig {
            val issuer = value(source, "OSTROM_GOOGLE_ISSUER")
            val authorization = value(source, "OSTROM_GOOGLE_AUTHORIZATION_URL")
            val token = value(source, "OSTROM_GOOGLE_TOKEN_URL")
            val endpoints = when {
                issuer == null && authorization == null && token == null -> OidcEndpoints.GOOGLE
                issuer != null && authorization != null && token != null -> OidcEndpoints(setOf(issuer), authorization, token)
                else -> throw InvalidConfigException(
                    "OSTROM_GOOGLE_ISSUER, OSTROM_GOOGLE_AUTHORIZATION_URL and OSTROM_GOOGLE_TOKEN_URL go together.",
                )
            }
            return SignInConfig(
                github = client(source, "GITHUB"),
                githubWebUrl = value(source, "OSTROM_GITHUB_WEB_URL")?.trimEnd('/') ?: "https://github.com",
                githubApiUrl = value(source, "OSTROM_GITHUB_API_URL")?.trimEnd('/') ?: "https://api.github.com",
                google = client(source, "GOOGLE"),
                googleEndpoints = endpoints,
                resendApiKey = value(source, "OSTROM_RESEND_API_KEY"),
                mailFrom = value(source, "OSTROM_MAIL_FROM") ?: "Ostrom <login@mail.ostrom.run>",
                mailDirectory = value(source, "OSTROM_MAIL_DIR"),
            )
        }

        private fun client(source: ConfigSource, provider: String): OAuthClient? {
            val id = value(source, "OSTROM_${provider}_CLIENT_ID")
            val secret = value(source, "OSTROM_${provider}_CLIENT_SECRET")
            if ((id == null) != (secret == null)) {
                throw InvalidConfigException("OSTROM_${provider}_CLIENT_ID and OSTROM_${provider}_CLIENT_SECRET go together.")
            }
            return if (id != null && secret != null) OAuthClient(id, secret) else null
        }

        private fun value(source: ConfigSource, key: String): String? = source[key]?.trim()?.takeIf { it.isNotEmpty() }
    }
}
