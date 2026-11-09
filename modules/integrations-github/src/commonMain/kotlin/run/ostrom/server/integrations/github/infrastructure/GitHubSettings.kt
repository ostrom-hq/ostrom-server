// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.kernel.config.ConfigSource

/**
 * The GitHub App of the instance, from the environment (`OSTROM_GITHUB_*`). Secrets are read from the environment
 * the deployment fills from its secret store, never from a file of the repository.
 *
 * @property appId `OSTROM_GITHUB_APP_ID`.
 * @property privateKeyPem `OSTROM_GITHUB_APP_PEM`, the App's private key (PKCS#1 as GitHub gives it, or PKCS#8).
 * @property webhookSecret `OSTROM_GITHUB_WEBHOOK_SECRET`: without it, every webhook is refused.
 * @property slug `OSTROM_GITHUB_APP_SLUG`, for the install link.
 * @property apiUrl `OSTROM_GITHUB_API`, GitHub's API (GitHub Enterprise Server, or a test double).
 * @property webUrl `OSTROM_GITHUB_WEB`, GitHub's web address, for the install link.
 * @property webOrigin `OSTROM_WEB_ORIGIN`, the console, for the check's link and the return of the install.
 * @property clientId `OSTROM_GITHUB_APP_CLIENT_ID`, the App's OAuth client: the install asks the person who installs
 *   to authorize it ("Request user authorization (OAuth) during installation" on the App), so that the server checks
 *   the installation is theirs.
 * @property clientSecret `OSTROM_GITHUB_APP_CLIENT_SECRET`, its secret.
 */
data class GitHubSettings(
    val appId: String?,
    val privateKeyPem: String?,
    val webhookSecret: String?,
    val slug: String?,
    val apiUrl: String = "https://api.github.com",
    val webUrl: String = "https://github.com",
    val webOrigin: String = "http://localhost:5173",
    val clientId: String? = null,
    val clientSecret: String? = null,
) {
    /** Whether the App can authenticate: an id and a key. */
    val configured: Boolean get() = appId != null && privateKeyPem != null

    // The key never appears in a log or a dump of the settings.
    override fun toString(): String = "GitHubSettings(appId=$appId, slug=$slug, apiUrl=$apiUrl, configured=$configured)"

    /** Reads the settings. */
    companion object {
        /**
         * Reads the settings.
         *
         * @param config The environment.
         * @return The settings; a blank value counts as absent.
         */
        fun from(config: ConfigSource): GitHubSettings {
            // Every source already counts a blank value as unset.
            fun value(key: String) = config[key]?.trim()
            val defaults = GitHubSettings(null, null, null, null)
            return GitHubSettings(
                appId = value("OSTROM_GITHUB_APP_ID"),
                // A key passed in one line keeps its line breaks as `\n`.
                privateKeyPem = value("OSTROM_GITHUB_APP_PEM")?.replace("\\n", "\n"),
                webhookSecret = value("OSTROM_GITHUB_WEBHOOK_SECRET"),
                slug = value("OSTROM_GITHUB_APP_SLUG"),
                apiUrl = (value("OSTROM_GITHUB_API") ?: defaults.apiUrl).trimEnd('/'),
                webUrl = (value("OSTROM_GITHUB_WEB") ?: defaults.webUrl).trimEnd('/'),
                webOrigin = (value("OSTROM_WEB_ORIGIN") ?: defaults.webOrigin).trimEnd('/'),
                clientId = value("OSTROM_GITHUB_APP_CLIENT_ID"),
                clientSecret = value("OSTROM_GITHUB_APP_CLIENT_SECRET"),
            )
        }
    }
}
