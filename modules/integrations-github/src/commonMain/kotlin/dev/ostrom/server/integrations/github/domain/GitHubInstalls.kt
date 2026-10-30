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
 * The installation of the callback is not one the person who came back can reach on GitHub: someone tried to link
 * another account's installation to their organization (or GitHub did not confirm who they are). 403
 * `installation_not_yours`.
 */
class InstallationNotYoursException :
    OstromException(
        ErrorKind.FORBIDDEN,
        "This GitHub installation is not one you can reach: install the App from GitHub again.",
        "installation_not_yours",
    )

/**
 * Installs the GitHub App on an organization (SECURITY-AUDIT S2-23): the install link carries a state created by an
 * owner's session, kept as its SHA-256 only, used once and for 10 minutes. The callback is unauthenticated (GitHub
 * redirects the browser), so the state is the only proof of who started it. The `installation_id` of the return is
 * never trusted on its own (installation ids are sequential): the App asks the person to authorize it during the
 * install, the callback's `code` is exchanged for their token, and the id is accepted only if GitHub lists it among
 * the installations they can reach. The installation is then read with the App's own JWT before it is linked.
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
     * Finishes an install: GitHub came back with the installation, the person's authorization and the state.
     *
     * @param installationId GitHub's `installation_id`.
     * @param code GitHub's `code`: the authorization of the person who installed.
     * @param state The state of [start].
     * @return The installation, linked to the organization that started it.
     * @throws OstromException 400 for a malformed callback (a missing `code` too: the App does not ask for the
     *   person's authorization) or an installation GitHub does not know for this App, 403 for an unknown, used or
     *   expired state, 403 [InstallationNotYoursException] for an installation the person cannot reach, 409 for an
     *   installation another organization holds.
     */
    suspend fun finish(installationId: String?, code: String?, state: String?): GitHubInstallation {
        val id = installationId?.toLongOrNull()
        val proof = state?.takeIf(::bounded)
        val authorization = code?.takeIf(::bounded)
        if (id == null || proof == null || authorization == null) {
            refuse(ErrorKind.INVALID_REQUEST, "This is not a GitHub installation callback.")
        }
        // The state is used before GitHub is asked anything: a refused callback never serves twice.
        val orgId = repository.takeState(hash(proof), clock.now())
            ?: refuse(ErrorKind.FORBIDDEN, "This install link expired or was already used.")
        val person = api.userToken(authorization) ?: throw InstallationNotYoursException()
        if (id !in api.userInstallations(person)) throw InstallationNotYoursException()
        val account = api.installationAccount(id)
            ?: refuse(ErrorKind.INVALID_REQUEST, "GitHub does not know this installation of the App.")
        if (repository.installationById(id)?.let { it.orgId != orgId } == true) {
            refuse(ErrorKind.CONFLICT, "This installation belongs to another organization.")
        }
        val installation = GitHubInstallation(orgId, id, account)
        repository.saveInstallation(installation, clock.now())
        return installation
    }

    private fun refuse(kind: ErrorKind, message: String): Nothing = throw OstromException(kind, message)

    private fun bounded(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_FIELD

    private suspend fun hash(state: String): String =
        CryptographyProvider.Default.get(SHA256).hasher().hash(state.encodeToByteArray())
            .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

    private companion object {
        const val MAX_FIELD = 128
        const val BYTE = 0xff
        const val HEX = 16
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val STATE_LIFETIME = 10.minutes
