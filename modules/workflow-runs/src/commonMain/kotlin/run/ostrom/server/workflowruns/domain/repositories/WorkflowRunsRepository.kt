// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.repositories

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.NotificationStatus
import kotlin.uuid.Uuid

/**
 * A page of runs or notifications.
 *
 * @property sessionId Only this session's, or every one.
 * @property nodeId Only this node's (checks), or every one.
 * @property status Only these (notifications), or every one.
 * @property before The id the previous page ended on, or null for the newest.
 * @property limit At most this many.
 * @property audience Only those this audience reads, when set; every one of the organization otherwise.
 */
data class RunQuery(
    val sessionId: String? = null,
    val nodeId: String? = null,
    val status: NotificationStatus? = null,
    val before: String? = null,
    val limit: Int = PAGE,
    val audience: RunAudience? = null,
) {
    /** The page size. */
    companion object {
        /** The size of a page. */
        const val PAGE: Int = 50
    }
}

/**
 * The runs and notifications a member reads beside their own (TeamCode #238): those of the sessions whose gates they
 * approve.
 *
 * @property dev The member, whose own runs and notifications they read (their sessions' and those outside a
 *   session), or null when unknown.
 * @property sessions The sessions whose gates they approve.
 */
data class RunAudience(val dev: String?, val sessions: List<String>)

/** Storage of the check runs and notifications, isolated by organization. */
interface WorkflowRunsRepository {
    /**
     * Stores a check run.
     *
     * @param orgId The organization.
     * @param run The run.
     * @param attestation Where it comes from: the workstation's declaration unless the server holds a proof.
     */
    suspend fun addCheck(orgId: Uuid, run: CheckRun, attestation: CheckAttestation)

    /**
     * Reads where the runs of a session come from, for those the workstation did not merely declare.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     *
     * @return The attestation of each such run, by run id; a run absent from it is the workstation's declaration.
     */
    suspend fun attestations(orgId: Uuid, sessionId: String): Map<String, CheckAttestation>

    /**
     * Lists check runs, newest first.
     *
     * @param orgId The organization.
     * @param query Which.
     *
     * @return The runs.
     */
    suspend fun checks(orgId: Uuid, query: RunQuery): List<CheckRun>

    /**
     * Whether the organization holds a check run [audience] reads.
     *
     * @param orgId The organization.
     * @param id The run's id.
     * @param audience Who reads it, or null for anyone of the organization.
     * @return True when it does.
     */
    suspend fun hasCheck(orgId: Uuid, id: String, audience: RunAudience? = null): Boolean

    /**
     * Stores a notification, unless the same action of the same session already had it: a hook may pass twice.
     *
     * @param orgId The organization.
     * @param notification The notification.
     * @param actionKey The digest of the command that triggered it.
     *
     * @return The stored notification: [notification], or the one stored first.
     */
    suspend fun addNotification(orgId: Uuid, notification: Notification, actionKey: String): Notification

    /**
     * Writes what became of a claimed notification (its status, its error).
     *
     * @param orgId The organization.
     * @param notification The notification, as delivered.
     */
    suspend fun updateNotification(orgId: Uuid, notification: Notification)

    /**
     * Lists notifications, newest first.
     *
     * @param orgId The organization.
     * @param query Which.
     *
     * @return The notifications.
     */
    suspend fun notifications(orgId: Uuid, query: RunQuery): List<Notification>

    /**
     * Whether the organization holds a notification [audience] reads.
     *
     * @param orgId The organization.
     * @param id The notification's id.
     * @param audience Who reads it, or null for anyone of the organization.
     * @return True when it does.
     */
    suspend fun hasNotification(orgId: Uuid, id: String, audience: RunAudience? = null): Boolean
}
