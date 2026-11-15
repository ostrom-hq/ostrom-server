// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.presentation

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.ReportCheckRequest
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryText

private const val CHECKS_TAG = "checks"

private const val NOTIFICATIONS_TAG = "notifications"

private const val SESSIONS_TAG = "sessions"

private const val RECEIPTS_TAG = "receipts"

private const val SESSION_NOT_FOUND = "No such session in the caller's organization, or one the caller may not " +
    "reach (`not_found`)."

/**
 * The workflow-runs module's operations: what a session's hooks report (checks, notifications, the workflow
 * receipt), the runs of workflows, and the delivery of an agent step. The bodies are the module's own mirrors of
 * ostrom-core's `Check*`, `Notification*`, `Receipt*` and `WorkflowRun*` models.
 */
internal fun ApiCatalog.workflowRunsOperations() {
    tag(CHECKS_TAG, "The checks a session ran, as the hooks, the CI and the runners report them.")
    checkOperations()
    notificationOperations()
    receiptOperations()
    startedRunsOperations()
    workflowSessionOperations()
}

/** The check runs. */
private fun ApiCatalog.checkOperations() {
    get("/api/v1/checks") {
        operationId = "listChecks"
        tag(CHECKS_TAG)
        summary = "Check runs."
        description = "Read by the session's person, or an agent token of its launch."
        parameters {
            organizationHeader()
            queryText("sessionId", "Only this session's runs.")
            queryText("nodeId", "Only the runs of this check node.")
            queryText("before", "The `next` of the previous page: the id of its last run.")
        }
        responses {
            ok<ChecksPage>("A page of runs, `next` set when another may follow.")
            badRequest("A malformed filter (`invalid_request`).")
        }
    }
    post("/api/v1/checks") {
        operationId = "reportCheck"
        tag(CHECKS_TAG)
        summary = "Report a check run."
        description = "Callers: the session's person, an agent token of the session's launch, or a service key of " +
            "the organization with `sessions:write` (its CI), which acts in its own organization whatever " +
            "`Ostrom-Org` names. Each call of a key is recorded in the organization's audit log, in the key's name " +
            "(`service_key.used`). A key's run is `workstation_declared`, like the hooks'."
        parameters {
            organizationHeader()
        }
        body<ReportCheckRequest>("The check, the session and node it ran for, and its exit code.")
        responses {
            ok<CheckRun>("The run recorded.", HttpStatusCode.Created)
            notFound(SESSION_NOT_FOUND)
        }
    }
    post("/api/v1/cloud/runner/checks") {
        operationId = "reportRunnerCheck"
        tag(CHECKS_TAG)
        summary = "The runner of a cloud session reports a check it ran itself."
        description = "With the execution's runner token, not a member's credential. The run is recorded " +
            "`isolated_runner` for a session of a launch that cloud session opened (404 otherwise), and the " +
            "receipt's check node takes the attestation of its last run. It proves that Ostrom ran the script and " +
            "read its exit code, not that the agent's workspace it ran on was untouched."
        body<RunnerCheckRequest>("The cloud session, the execution's epoch and the check.")
        responses {
            ok<CheckRun>("The run recorded.", HttpStatusCode.Created)
            notFound("No such session for this execution (`not_found`).")
        }
    }
}

/** The notifications the hooks ask for. */
private fun ApiCatalog.notificationOperations() {
    get("/api/v1/notifications") {
        operationId = "listNotifications"
        tag(NOTIFICATIONS_TAG)
        summary = "Notifications."
        parameters {
            organizationHeader()
            queryText("sessionId", "Only this session's notifications.")
            queryText("status", "`queued`, `sent`, `logged-only` or `not-sent`; 400 otherwise.")
            queryText("before", "The `next` of the previous page: the id of its last notification.")
        }
        responses {
            ok<NotificationsPage>("A page of notifications, `next` set when another may follow.")
            badRequest("An unknown status (`invalid_request`).")
        }
    }
    post("/api/v1/notifications") {
        operationId = "sendNotification"
        tag(NOTIFICATIONS_TAG)
        summary = "Ask the server to send a compiled notification (hook PostToolUse)."
        parameters {
            organizationHeader()
        }
        body<SendNotificationRequest>("The package and node that compiled the notification, and what ran.")
        responses {
            ok<Notification>("The notification, queued.", HttpStatusCode.Accepted)
            notFound("No such session, package or notification in this organization (`not_found`).")
        }
    }
}

/** The workflow receipt and the bundle that proves it. */
private fun ApiCatalog.receiptOperations() {
    post("/api/v1/sessions/{id}/workflow-receipt") {
        operationId = "signWorkflowReceipt"
        tag(SESSIONS_TAG)
        summary = "Sign the workflow receipt of the session: what each compiled node did."
        description = "Callers: the session's person, an agent token of the session's launch, or a service key of " +
            "the organization with `sessions:write` (its CI), which acts in its own organization whatever " +
            "`Ostrom-Org` names. Each call of a key is recorded in the organization's audit log, in the key's name " +
            "(`service_key.used`)."
        parameters {
            organizationHeader()
            pathId("id", "The session's id.")
        }
        body<WorkflowReceiptRequestBody>("The package the session ran, and the agent and model that ran it.")
        responses {
            ok<WorkflowReceiptResponse>("The signed receipt, and whether it verifies.")
            notFound(SESSION_NOT_FOUND)
        }
    }
    get("/api/v1/receipts/{id}/bundle") {
        operationId = "getReceiptBundle"
        tag(RECEIPTS_TAG)
        summary = "The receipt with every signed piece it cites, for `ostrom verify`."
        description = "Read by a person who reads receipts, or by the agent token of the receipt's session's " +
            "launch. The format is `ostrom.receipt-bundle/1`: the receipt, its chain, the package it cites (`null` " +
            "when it cites none) and the public keys that verify them."
        parameters {
            organizationHeader()
            pathId("id", "The receipt's id.")
        }
        responses {
            ok<JsonObject>("The bundle.")
            notFound("No such receipt in the caller's organization, or one the caller may not read (`not_found`).")
        }
    }
}
