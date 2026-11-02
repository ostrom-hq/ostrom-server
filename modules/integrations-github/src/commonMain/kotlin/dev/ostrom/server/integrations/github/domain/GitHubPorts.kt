// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** GitHub as the person who installs the App: their authorization, and the installations they reach. */
interface GitHubPeopleApi {
    /**
     * Exchanges the code GitHub gives the install's return for the token of the person who installed, with the App's
     * OAuth client. The token is only used to read [userInstallations], then dropped: never stored nor logged.
     *
     * @param code The `code` of the callback.
     * @return The person's token, or null when GitHub refuses the code (unknown, used or expired).
     * @throws GitHubCallException When GitHub fails, or the App has no OAuth client on this server.
     */
    suspend fun userToken(code: String): String?

    /**
     * Lists the installations of the App the person can reach (`GET /user/installations`): the ones on their own
     * account and on the organizations they administer.
     *
     * @param userToken The person's token, from [userToken].
     * @return The ids of the installations.
     * @throws GitHubCallException When GitHub fails or refuses the token.
     */
    suspend fun userInstallations(userToken: String): Set<Long>

    /**
     * Reads the person's GitHub login (`GET /user`).
     *
     * @param userToken The person's token, from [userToken].
     * @return Their login.
     * @throws GitHubCallException When GitHub fails or refuses the token.
     */
    suspend fun userLogin(userToken: String): String
}

/** GitHub's REST API, as the GitHub App of the instance. */
interface GitHubApi : GitHubPeopleApi {
    /**
     * Reads a pull request.
     *
     * @param installationId The installation that sees the repository.
     * @param repo `owner/name`.
     * @param number The pull request.
     * @return It, or null when GitHub does not know it.
     * @throws GitHubCallException When GitHub fails or does not answer in time.
     */
    suspend fun pullRequest(installationId: Long, repo: String, number: Int): GitHubPull?

    /**
     * Creates the check run, or updates it when [checkRunId] is given (a completed check is never reopened: the
     * caller creates a new one).
     *
     * @param installationId The installation.
     * @param repo `owner/name`.
     * @param check What to show.
     * @param checkRunId The check run to update, or null to create one.
     * @return The check run's id.
     * @throws GitHubCallException When GitHub refuses it.
     */
    suspend fun publishCheck(installationId: Long, repo: String, check: CheckRun, checkRunId: Long?): Long

    /**
     * Reads an installation of the App, with the App's own JWT.
     *
     * @param installationId The installation.
     * @return The account it is installed on, or null when it is not an installation of this App.
     * @throws GitHubCallException When GitHub fails.
     */
    suspend fun installationAccount(installationId: Long): String?
}

/** The GitHub integration's rows: installations, published checks and the single-use states of the install flow. */
interface GitHubRepository {
    /**
     * @param orgId The organization.
     * @return Its installation, or null.
     */
    suspend fun installation(orgId: Uuid): GitHubInstallation?

    /**
     * Finds which organization an installation belongs to, for a webhook whose signature is verified.
     *
     * @param installationId GitHub's id.
     * @return The installation, or null when no organization installed it.
     */
    suspend fun installationById(installationId: Long): GitHubInstallation?

    /**
     * Records an installation; an organization has one, and an installation belongs to one organization.
     *
     * @param installation The installation.
     * @param at When.
     */
    suspend fun saveInstallation(installation: GitHubInstallation, at: Instant)

    /**
     * Records who installed the App, from a verified `installation.created` webhook (kept until it is deleted).
     *
     * @param origin The installation and its sender.
     * @param at When.
     */
    suspend fun saveOrigin(origin: GitHubInstallationOrigin, at: Instant)

    /**
     * @param installationId GitHub's id.
     * @return Who installed it, or null when its `installation.created` webhook has not arrived.
     */
    suspend fun origin(installationId: Long): GitHubInstallationOrigin?

    /**
     * Forgets an installation deleted on GitHub, linked or not: its row and who installed it.
     *
     * @param installationId GitHub's id.
     */
    suspend fun forgetInstallation(installationId: Long)

    /**
     * Claims a webhook delivery once (`X-GitHub-Delivery`): a replay of a delivery already handled is refused.
     *
     * @param deliveryId GitHub's id of the delivery.
     * @param at When it arrived; deliveries older than 30 days are forgotten.
     * @return False when the delivery was already claimed.
     */
    suspend fun claimDelivery(deliveryId: String, at: Instant): Boolean

    /**
     * Releases a claimed delivery whose handling failed, so that GitHub's redelivery (same id) is handled.
     *
     * @param deliveryId GitHub's id of the delivery.
     */
    suspend fun releaseDelivery(deliveryId: String)

    /**
     * Stores the hash of an install state.
     *
     * @param orgId The organization that starts the install.
     * @param stateHash The hash of the state sent to GitHub.
     * @param by Who started it.
     * @param expiresAt When it stops being accepted.
     */
    suspend fun saveState(orgId: Uuid, stateHash: String, by: String, expiresAt: Instant)

    /**
     * Uses an install state once: it is deleted, and returned only if not expired.
     *
     * @param stateHash The hash of the state GitHub sent back.
     * @param now The current time.
     * @return The organization that started the install, or null.
     */
    suspend fun takeState(stateHash: String, now: Instant): Uuid?

    /**
     * @param orgId The organization.
     * @param gateId The gate.
     * @return The check published for the gate, or null.
     */
    suspend fun check(orgId: Uuid, gateId: Uuid): GateCheck?

    /**
     * Records a published check.
     *
     * @param orgId The organization.
     * @param check The check.
     */
    suspend fun saveCheck(orgId: Uuid, check: GateCheck)
}
