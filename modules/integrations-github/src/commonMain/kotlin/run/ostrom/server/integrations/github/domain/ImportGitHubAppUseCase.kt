// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock

/**
 * Imports, once, the GitHub App a deployment still names in the former variables (`OSTROM_GITHUB_APP_ID`,
 * `_APP_SLUG`, `_APP_PEM`, `_WEBHOOK_SECRET`, `_APP_CLIENT_ID`, `_APP_CLIENT_SECRET`) into the stored App, sealed, as
 * TeamCode keeps its App in `github_apps`. Afterwards the stored App is the only one, and a variable still set is
 * ignored with a warning.
 */
fun interface ImportGitHubAppUseCase {
    /**
     * Imports the App, when nothing is stored yet.
     *
     * @param environment The App the former variables name.
     */
    suspend operator fun invoke(environment: GitHubAppCredentials)
}

/**
 * [ImportGitHubAppUseCase].
 *
 * @param store The stored App.
 * @param logger Where the deprecation is said (the variables' names, never a value).
 * @param clock The clock.
 */
class ImportGitHubAppUseCaseImpl(
    private val store: GitHubAppStore,
    private val logger: Logger,
    private val clock: Clock,
) : ImportGitHubAppUseCase {
    override suspend fun invoke(environment: GitHubAppCredentials) {
        if (!environment.named) return
        val fields = mapOf("variables" to VARIABLES)
        val app = environment.created()
        when {
            store.app() != null -> logger.warn(
                "Ignored deprecated variables: the GitHub App is stored and changed in the console only.",
                fields,
            )
            app == null -> logger.warn(
                "Deprecated variables that name no usable GitHub App (an id, a slug and a private key) were not " +
                    "imported: create the App in the console.",
                fields,
            )
            store.saveApp(app, IMPORTER, clock.now()) -> logger.warn(
                "Imported the GitHub App of deprecated variables into the stored App, once: remove the variables.",
                fields,
            )
        }
    }

    private fun GitHubAppCredentials.created(): CreatedGitHubApp? {
        val id = appId?.toLongOrNull()
        val key = privateKeyPem
        val name = slug
        return if (id == null || key == null || name == null) {
            null
        } else {
            CreatedGitHubApp(id, name, null, key, webhookSecret, clientId, clientSecret)
        }
    }

    /** The former variables and who an imported App is recorded as created by. */
    companion object {
        /** The former variables of the App. */
        const val VARIABLES: String = "OSTROM_GITHUB_APP_ID,OSTROM_GITHUB_APP_SLUG,OSTROM_GITHUB_APP_PEM," +
            "OSTROM_GITHUB_WEBHOOK_SECRET,OSTROM_GITHUB_APP_CLIENT_ID,OSTROM_GITHUB_APP_CLIENT_SECRET"

        /** The creator recorded for an imported App. */
        const val IMPORTER: String = "environment"
    }
}
