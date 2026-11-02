// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.presentation

import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.Notification
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The routes and bodies of `ostrom-core/api/openapi.yaml` (operations `reportCheck`, `listChecks`, `sendNotification`,
// `listNotifications`, `signWorkflowReceipt` and `getReceiptBundle`).

/**
 * The check runs.
 *
 * @property sessionId Only this session's.
 * @property nodeId Only this node's.
 * @property before The id the previous page ended on.
 */
@Serializable
@Resource("/api/v1/checks")
class ChecksApi(val sessionId: String? = null, val nodeId: String? = null, val before: String? = null)

/**
 * The notifications.
 *
 * @property sessionId Only this session's.
 * @property status Only these (`queued`, `sent`, `logged-only`, `not-sent`).
 * @property before The id the previous page ended on.
 */
@Serializable
@Resource("/api/v1/notifications")
class NotificationsApi(val sessionId: String? = null, val status: String? = null, val before: String? = null)

/**
 * The workflow receipt of a session.
 *
 * @property id The session.
 */
@Serializable
@Resource("/api/v1/sessions/{id}/workflow-receipt")
class WorkflowReceiptApi(val id: String)

/**
 * The bundle of a receipt.
 *
 * @property id The receipt.
 */
@Serializable
@Resource("/api/v1/receipts/{id}/bundle")
class ReceiptBundleApi(val id: String)

/**
 * The body of `POST /api/v1/notifications`.
 *
 * @property sessionId The session.
 * @property digest The package.
 * @property nodeId The notify node.
 * @property notifyId The notification.
 * @property command The command that triggered it.
 */
@Serializable
data class SendNotificationRequest(
    val sessionId: String? = null,
    val digest: String,
    val nodeId: String,
    val notifyId: String,
    val command: String? = null,
)

/**
 * The body of `POST /api/v1/sessions/{id}/workflow-receipt`.
 *
 * @property digest The package the session ran.
 * @property agent The agent.
 * @property model The model.
 */
@Serializable
data class WorkflowReceiptRequestBody(val digest: String, val agent: String? = null, val model: String? = null)

/**
 * A page of check runs.
 *
 * @property items The runs, newest first.
 * @property next The id to ask the next page before, or null on the last page.
 */
@Serializable
data class ChecksPage(val items: List<CheckRun>, val next: String? = null)

/**
 * A page of notifications.
 *
 * @property items The notifications, newest first.
 * @property next The id to ask the next page before, or null on the last page.
 */
@Serializable
data class NotificationsPage(val items: List<Notification>, val next: String? = null)

/**
 * A verification.
 *
 * @property ok Whether the signature holds.
 * @property digest The digest signed.
 * @property kid The key that signed it.
 * @property reason Why not.
 */
@Serializable
data class VerificationDto(
    val ok: Boolean,
    val digest: String? = null,
    val kid: String? = null,
    val reason: String? = null,
)

/**
 * A signed workflow receipt.
 *
 * @property receipt The receipt, as signed.
 * @property verification Its verification.
 */
@Serializable
data class WorkflowReceiptResponse(val receipt: JsonObject, val verification: VerificationDto)
