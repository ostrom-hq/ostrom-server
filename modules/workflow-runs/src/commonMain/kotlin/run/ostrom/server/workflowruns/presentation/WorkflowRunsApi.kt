// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.presentation

import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.ReportCheckRequest
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.kernel.http.NullsKept
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The routes and bodies of `ostrom-core/api/openapi.yaml` (operations `reportCheck`, `listChecks`, `sendNotification`,
// `listNotifications`, `signWorkflowReceipt`, `getReceiptBundle`, `startWorkflowRun`, `listWorkflowRuns` and
// `listRuns`).

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
data class ChecksPage(val items: List<@Serializable(with = CheckRunAnswer::class) CheckRun>, val next: String? = null)

/** A check run writes `exit` as `null` for a run that did not end: required and nullable in the contract. */
object CheckRunAnswer : NullsKept<CheckRun>(CheckRun.serializer(), "exit")

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
 * @property orgBound True when the signed body names the caller's organization.
 */
@Serializable
data class VerificationDto(
    val ok: Boolean,
    val digest: String? = null,
    val kid: String? = null,
    val reason: String? = null,
    val orgBound: Boolean = false,
)

/**
 * A signed workflow receipt.
 *
 * @property receipt The receipt, as signed.
 * @property verification Its verification.
 */
@Serializable
data class WorkflowReceiptResponse(val receipt: JsonObject, val verification: VerificationDto)

/**
 * `POST /api/v1/cloud/runner/checks`: a check run the runner of a cloud session ran itself, authenticated by the
 * execution's runner token, which the agent cannot read (S2-49). Not in the contract yet.
 */
@Serializable
@Resource("/api/v1/cloud/runner/checks")
class RunnerChecksApi

/**
 * A check run the runner reports.
 *
 * @property sessionId The cloud session the runner was started for.
 * @property epoch The execution it was started for.
 * @property check The run, as the hooks report one: its `sessionId` is the workflow session it ran for.
 */
@Serializable
data class RunnerCheckRequest(val sessionId: String, val epoch: Long, val check: ReportCheckRequest)

/**
 * The runs of a workflow.
 *
 * @property id The workflow.
 * @property status Only these (`queued`, `running`, `waiting`, `succeeded`, `failed`, `cancelled`).
 * @property before The id the previous page ended on.
 */
@Serializable
@Resource("/api/v1/workflows/{id}/runs")
class WorkflowRunsOfApi(val id: String, val status: String? = null, val before: String? = null)

/**
 * The runs of the organization's workflows.
 *
 * @property status Only these.
 * @property before The id the previous page ended on.
 */
@Serializable
@Resource("/api/v1/runs")
class RunsApi(val status: String? = null, val before: String? = null)

/**
 * A run of a workflow, with its visits.
 *
 * @property id The run.
 */
@Serializable
@Resource("/api/v1/runs/{id}")
class RunApi(val id: String)

/**
 * Cancels a run of a workflow.
 *
 * @property id The run.
 */
@Serializable
@Resource("/api/v1/runs/{id}/cancel")
class RunCancelApi(val id: String)

/**
 * The body of `POST /api/v1/workflows/{id}/runs`.
 *
 * @property inputs The inputs, by id.
 */
@Serializable
data class StartRunRequest(val inputs: Map<String, String> = emptyMap())

/**
 * A page of runs of workflows.
 *
 * @property items The runs, newest first.
 * @property next The id to ask the next page before, or null on the last page.
 */
@Serializable
data class RunsPage(val items: List<WorkflowRun>, val next: String? = null)

/** The scopes of the runs of workflows; a person's browser session (`user:*`) holds them all. */
object RunScopes {
    /** Reads the workflows and their runs. */
    const val WORKFLOWS_READ: String = "workflows:read"

    /** Writes the workflows and starts their runs (an owner or an admin). */
    const val WORKFLOWS_WRITE: String = "workflows:write"

    /** Who starts a run: a person's credential that writes workflows; never an agent token. */
    val starting: Set<String> = setOf(GateScopes.ALL_USER, WORKFLOWS_WRITE)

    /** Who reads the runs: any credential that reads workflows; never an agent token. */
    val reading: Set<String> = setOf(GateScopes.ALL_USER, WORKFLOWS_READ, WORKFLOWS_WRITE)
}
