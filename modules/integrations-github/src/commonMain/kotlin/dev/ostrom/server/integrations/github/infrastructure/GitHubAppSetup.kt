// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.GitHubAppCredentials
import dev.ostrom.server.kernel.config.ConfigSource

/**
 * What the manifest flow needs besides [GitHubSettings], from the environment.
 *
 * @property publicUrl `OSTROM_PUBLIC_URL`, the API's public URL, where GitHub sends people back and posts webhooks.
 * @property appName `OSTROM_GITHUB_APP_NAME`, the name of the App created on GitHub (unique on GitHub).
 * @property instanceAdmins `OSTROM_INSTANCE_ADMINS`, the user ids (comma-separated) of who may create the App.
 */
data class GitHubAppSetup(val publicUrl: String, val appName: String, val instanceAdmins: Set<String>) {
    /** Reads the setup. */
    companion object {
        /**
         * Reads the setup.
         *
         * @param config The environment.
         * @return The setup; `http://localhost:8080`, `Ostrom` and nobody by default.
         */
        fun from(config: ConfigSource): GitHubAppSetup = GitHubAppSetup(
            publicUrl = (config["OSTROM_PUBLIC_URL"]?.trim() ?: "http://localhost:8080").trimEnd('/'),
            appName = config["OSTROM_GITHUB_APP_NAME"]?.trim() ?: "Ostrom",
            instanceAdmins = config["OSTROM_INSTANCE_ADMINS"].orEmpty().split(',')
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }
}

/**
 * The App the environment names, as the App source reads it (it wins over a stored App).
 *
 * @return The App; no OAuth client from the environment yet.
 */
fun GitHubSettings.credentials(): GitHubAppCredentials =
    GitHubAppCredentials(appId = appId, slug = slug, privateKeyPem = privateKeyPem, webhookSecret = webhookSecret)
