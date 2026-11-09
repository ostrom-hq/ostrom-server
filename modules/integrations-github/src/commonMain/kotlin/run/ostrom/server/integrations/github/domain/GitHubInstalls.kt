// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
 * GitHub has not yet told the server who installed the App (its `installation.created` webhook): the install cannot
 * be checked yet. 503 `installation_pending`: the person installs again from the console a moment later.
 */
class InstallationPendingException :
    OstromException(
        ErrorKind.UNAVAILABLE,
        "GitHub has not confirmed this installation yet: start the install again in a moment.",
        "installation_pending",
    )

/**
 * Installs the GitHub App on an organization (SECURITY-AUDIT S2-23): the install link carries a state created by an
 * owner's or an admin's session, used once and for 10 minutes. Only its digest is kept, over the state and the session
 * that started the install, so the state is bound to its organization and to that session: GitHub sends the browser
 * back to the callback with the person's session cookie, and a state brought back by another session (or replayed)
 * matches no row. The `installation_id` of the return is never trusted on its own (installation ids are sequential):
 * the App asks the person to authorize it during the install, the callback's `code` is exchanged for their token, and
 * the id is accepted only if GitHub lists it among the installations they can reach, and only if they are who installed
 * it (AUDIT-SECU-2 M1): GitHub lists an installation to anyone with read access to its account, while only an owner or
 * an admin of a GitHub organization can install an App on it. Who installed it comes from GitHub's signed
 * `installation.created` webhook ([GitHubInstallationOrigin]), awaited a few seconds; on a personal account, its owner
 * must be the person too. The installation is then read with the App's own JWT before it is linked.
 *
 * @param repository Installations and states.
 * @param origins Who installed each installation, awaited up to 5 seconds, checked once a second.
 * @param api GitHub.
 * @param page GitHub's page that installs the App ([installPage]), or null when the App is not configured (read at
 *   each start: the App can be created later).
 * @param clock The clock.
 * @param installed Told of each installation linked (the directory is read again).
 */
class GitHubInstalls(
    private val repository: GitHubRepository,
    private val origins: GitHubOriginsRepository,
    private val api: GitHubApi,
    private val page: suspend () -> String?,
    private val clock: Clock,
    private val installed: suspend (GitHubInstallation) -> Unit = {},
) {
    /**
     * Starts an install.
     *
     * @param orgId The organization.
     * @param by The owner or admin who starts it.
     * @param session The session that starts it: only that session finishes it.
     * @return The link to GitHub's install page.
     * @throws GitHubAppNotConfiguredException Without an App.
     */
    suspend fun start(orgId: Uuid, by: String, session: String): String {
        val install = page() ?: throw GitHubAppNotConfiguredException()
        val state = Uuid.random().toHexString() + Uuid.random().toHexString()
        repository.saveState(orgId, hash(state, session), by, clock.now() + STATE_LIFETIME)
        return "$install?state=$state"
    }

    /**
     * Finishes an install: GitHub came back with the installation, the person's authorization and the state.
     *
     * @param installationId GitHub's `installation_id`.
     * @param code GitHub's `code`: the authorization of the person who installed.
     * @param state The state of [start].
     * @param session The session the browser came back with, or null without one.
     * @return The installation, linked to the organization that started it.
     * @throws OstromException 400 for a malformed callback (a missing `code` too: the App does not ask for the
     *   person's authorization) or an installation GitHub does not know for this App, 401 without a session, 403 for
     *   an unknown, used or expired state or one another session started, 403 [InstallationNotYoursException] for an
     *   installation the person cannot reach or did not install, 409 for an installation another organization holds,
     *   503 [InstallationPendingException] before GitHub's webhook says who installed it.
     */
    suspend fun finish(installationId: String?, code: String?, state: String?, session: String?): GitHubInstallation {
        val id = installationId?.toLongOrNull()
        val proof = state?.takeIf(::bounded)
        val authorization = code?.takeIf(::bounded)
        if (id == null || proof == null || authorization == null) {
            refuse(ErrorKind.INVALID_REQUEST, "This is not a GitHub installation callback.")
        }
        if (session == null) refuse(ErrorKind.UNAUTHORIZED, "Sign in to the console, then install the App again.")
        // The state is used before GitHub is asked anything: a refused callback never serves twice.
        val orgId = repository.takeState(hash(proof, session), clock.now())
            ?: refuse(ErrorKind.FORBIDDEN, "This install link expired, was already used or belongs to another session.")
        requireInstaller(id, authorization)
        val account = api.installationAccount(id)
            ?: refuse(ErrorKind.INVALID_REQUEST, "GitHub does not know this installation of the App.")
        if (repository.installationById(id)?.let { it.orgId != orgId } == true) {
            refuse(ErrorKind.CONFLICT, "This installation belongs to another organization.")
        }
        val installation = GitHubInstallation(orgId, id, account)
        repository.saveInstallation(installation, clock.now())
        installed(installation)
        return installation
    }

    // Who comes back must reach the installation on GitHub, and be who installed it (AUDIT-SECU-2 M1).
    private suspend fun requireInstaller(installationId: Long, authorization: String) {
        val person = api.userToken(authorization)?.takeIf { installationId in api.userInstallations(it) }
            ?: notYours()
        val origin = awaitOrigin(installationId) ?: throw InstallationPendingException()
        if (!origin.installedBy(api.userLogin(person))) notYours()
    }

    private fun notYours(): Nothing = throw InstallationNotYoursException()

    // GitHub sends the webhook as the install ends, and the browser back at the same time: either can come first.
    private suspend fun awaitOrigin(installationId: Long): GitHubInstallationOrigin? {
        var origin = origins.origin(installationId)
        var waited = Duration.ZERO
        while (origin == null && waited < ORIGIN_WAIT) {
            delay(ORIGIN_POLL)
            waited += ORIGIN_POLL
            origin = origins.origin(installationId)
        }
        return origin
    }

    private fun refuse(kind: ErrorKind, message: String): Nothing = throw OstromException(kind, message)

    private fun bounded(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_FIELD

    private suspend fun hash(state: String, session: String): String =
        CryptographyProvider.Default.get(SHA256).hasher().hash("$state:$session".encodeToByteArray())
            .joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

    private companion object {
        const val MAX_FIELD = 128
        const val BYTE = 0xff
        const val HEX = 16
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val STATE_LIFETIME = 10.minutes
private val ORIGIN_WAIT = 5.seconds
private val ORIGIN_POLL = 1.seconds

/**
 * GitHub's page that installs an App.
 *
 * @param webUrl GitHub's web address.
 * @param slug The App's slug, or null when no App is configured.
 * @return The page, or null without an App.
 */
fun installPage(webUrl: String, slug: String?): String? = slug?.let { "$webUrl/apps/$it/installations/new" }
