// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The GitHub App is not configured on this server (no slug, id or key). */
class GitHubAppNotConfiguredException :
    OstromException(ErrorKind.UNAVAILABLE, "The GitHub App is not configured on this server.")

/**
 * Installs the GitHub App on an organization (SECURITY-AUDIT S2-23): the install link carries a state created by an
 * owner's session, kept as its SHA-256 only, used once and for 10 minutes. The callback is unauthenticated (GitHub
 * redirects the browser), so the state is the only proof of who started it; the installation is then read from
 * GitHub with the App's own JWT before it is linked.
 *
 * @param repository Installations and states.
 * @param api GitHub.
 * @param slug The App's slug, or null when not configured.
 * @param webUrl GitHub's web address.
 * @param clock The clock.
 */
class GitHubInstalls(
    private val repository: GitHubRepository,
    private val api: GitHubApi,
    private val slug: String?,
    private val webUrl: String,
    private val clock: Clock,
) {
    /**
     * Starts an install.
     *
     * @param orgId The organization.
     * @param by The owner who starts it.
     * @return The link to GitHub's install page.
     * @throws GitHubAppNotConfiguredException Without an App.
     */
    suspend fun start(orgId: Uuid, by: String): String {
        val app = slug ?: throw GitHubAppNotConfiguredException()
        val state = Uuid.random().toHexString() + Uuid.random().toHexString()
        repository.saveState(orgId, hash(state), by, clock.now() + STATE_LIFETIME)
        return "$webUrl/apps/$app/installations/new?state=$state"
    }

    /**
     * Finishes an install: GitHub came back with the installation and the state.
     *
     * @param installationId GitHub's `installation_id`.
     * @param state The state of [start].
     * @return The installation, linked to the organization that started it.
     * @throws OstromException 400 for a malformed callback or an installation GitHub does not know for this App, 403
     *   for an unknown, used or expired state, 409 for an installation another organization holds.
     */
    suspend fun finish(installationId: String?, state: String?): GitHubInstallation {
        val id = installationId?.toLongOrNull()
        if (id == null || state.isNullOrEmpty() || state.length > MAX_STATE) {
            throw OstromException(ErrorKind.INVALID_REQUEST, "This is not a GitHub installation callback.")
        }
        val orgId = repository.takeState(hash(state), clock.now())
            ?: throw OstromException(ErrorKind.FORBIDDEN, "This install link expired or was already used.")
        val account = api.installationAccount(id)
            ?: throw OstromException(ErrorKind.INVALID_REQUEST, "GitHub does not know this installation of the App.")
        if (repository.installationById(id)?.let { it.orgId != orgId } == true) {
            throw OstromException(ErrorKind.CONFLICT, "This installation belongs to another organization.")
        }
        val installation = GitHubInstallation(orgId, id, account)
        repository.saveInstallation(installation, clock.now())
        return installation
    }

    private suspend fun hash(state: String): String =
        CryptographyProvider.Default.get(SHA256).hasher().hash(state.encodeToByteArray())
            .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

    private companion object {
        val STATE_LIFETIME = 10.minutes
        const val MAX_STATE = 128
        const val BYTE = 0xff
        const val HEX = 16
    }
}
