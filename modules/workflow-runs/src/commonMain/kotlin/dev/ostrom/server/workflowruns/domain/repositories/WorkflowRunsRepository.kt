// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.repositories

import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.Notification
import dev.ostrom.core.api.models.NotificationStatus
import kotlin.uuid.Uuid

/**
 * A page of runs or notifications.
 *
 * @property sessionId Only this session's, or every one.
 * @property nodeId Only this node's (checks), or every one.
 * @property status Only these (notifications), or every one.
 * @property before The id the previous page ended on, or null for the newest.
 * @property limit At most this many.
 */
data class RunQuery(
    val sessionId: String? = null,
    val nodeId: String? = null,
    val status: NotificationStatus? = null,
    val before: String? = null,
    val limit: Int = PAGE,
) {
    /** The page size. */
    companion object {
        /** The size of a page. */
        const val PAGE: Int = 50
    }
}

/** Storage of the check runs and notifications, isolated by organization. */
interface WorkflowRunsRepository {
    /**
     * Stores a check run.
     *
     * @param orgId The organization.
     * @param run The run.
     */
    suspend fun addCheck(orgId: Uuid, run: CheckRun)

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
     * Reads the notification an action of a session already had, not counting one not sent (it may go again).
     *
     * @param orgId The organization.
     * @param sessionId The session.
     * @param notifyId The notification of the package.
     * @param actionKey The digest of the command that triggered it.
     *
     * @return The notification, or null.
     */
    suspend fun firstNotification(orgId: Uuid, sessionId: String, notifyId: String, actionKey: String): Notification?

    /**
     * Lists notifications, newest first.
     *
     * @param orgId The organization.
     * @param query Which.
     *
     * @return The notifications.
     */
    suspend fun notifications(orgId: Uuid, query: RunQuery): List<Notification>
}
