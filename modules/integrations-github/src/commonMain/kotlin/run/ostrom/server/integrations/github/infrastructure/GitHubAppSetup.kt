// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.kernel.config.ConfigSource

/**
 * What the manifest flow needs besides [GitHubSettings], from the environment.
 *
 * @property publicUrl `OSTROM_PUBLIC_URL`, the API's public URL, where GitHub sends people back and posts webhooks.
 */
data class GitHubAppSetup(val publicUrl: String) {
    /** Reads the setup. */
    companion object {
        /**
         * Reads the setup.
         *
         * @param config The environment.
         * @return The setup; `http://localhost:8080` by default.
         */
        fun from(config: ConfigSource): GitHubAppSetup = GitHubAppSetup(
            // `(value ?: default).trim()`, not `value?.trim() ?: default`: no null check after a trim that never is.
            publicUrl = (config["OSTROM_PUBLIC_URL"] ?: "http://localhost:8080").trim().trimEnd('/'),
        )
    }
}

/**
 * The App the former variables name, as the one-time import reads it.
 *
 * @return The App, with its OAuth client when the environment names one.
 */
fun GitHubSettings.credentials(): GitHubAppCredentials = GitHubAppCredentials(
    appId = appId,
    slug = slug,
    privateKeyPem = privateKeyPem,
    webhookSecret = webhookSecret,
    clientId = clientId,
    clientSecret = clientSecret,
)
