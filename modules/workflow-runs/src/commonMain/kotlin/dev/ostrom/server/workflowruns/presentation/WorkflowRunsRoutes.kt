// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.presentation

import dev.ostrom.core.api.models.NotificationStatus
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.ReportCheckRequest
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.requireAnyScope
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.receipts.presentation.requireReceiptReader
import dev.ostrom.server.runners.domain.RunnerUnauthenticatedException
import dev.ostrom.server.sessions.presentation.SessionScopes
import dev.ostrom.server.workflowruns.domain.models.InvalidRunException
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.usecases.NotificationRequest
import dev.ostrom.server.workflowruns.domain.usecases.ReceiptBundle
import dev.ostrom.server.workflowruns.domain.usecases.WorkflowReceiptRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The workflow-runs routes of the contract. Reports (checks, notifications, the workflow receipt) come from the
 * launcher's hooks with the launch's agent token, or from the session's person; the lists are any member's, an agent
 * token's for its own session; a bundle is read by a person who reads receipts, or by the agent token of the
 * receipt's session's launch (`ostrom <agent>` keeps it at the end). Outside the contract, the runner of a cloud
 * session reports the checks it ran itself on `/api/v1/cloud/runner/checks`, with its runner token.
 *
 * @param dependencies The use cases.
 */
fun Route.workflowRunsRoutes(dependencies: WorkflowRunsRoutesDependencies) = with(dependencies) {
    authenticated {
        post<ChecksApi> {
            val actor = actorOf(call, SessionScopes.writing)
            val run = reportCheck(actor, call.receive<ReportCheckRequest>())
            call.respond(HttpStatusCode.Created, run)
        }
        get<ChecksApi> { params ->
            val actor = actorOf(call, SessionScopes.reading)
            val query = RunQuery(sessionId = params.sessionId, nodeId = params.nodeId, before = params.before)
            val items = listChecks(actor, query)
            call.respond(ChecksPage(items, nextOf(items.map { it.id }, query.limit)))
        }
        post<NotificationsApi> {
            val actor = actorOf(call, SessionScopes.writing)
            val body = call.receive<SendNotificationRequest>()
            val request = NotificationRequest(body.sessionId, body.digest, body.nodeId, body.notifyId, body.command)
            call.respond(HttpStatusCode.Accepted, sendNotification(actor, request))
        }
        get<NotificationsApi> { params ->
            val actor = actorOf(call, SessionScopes.reading)
            val query = RunQuery(sessionId = params.sessionId, status = statusOf(params.status), before = params.before)
            val items = listNotifications(actor, query)
            call.respond(NotificationsPage(items, nextOf(items.map { it.id }, query.limit)))
        }
        post<WorkflowReceiptApi> { params ->
            val actor = actorOf(call, SessionScopes.writing)
            val body = call.receive<WorkflowReceiptRequestBody>()
            val request = WorkflowReceiptRequest(params.id, body.digest, body.agent, body.model)
            val signed = signWorkflowReceipt(actor, request)
            val verification = signed.verification
            val dto = VerificationDto(verification.ok, verification.digest, verification.kid, verification.reason)
            call.respond(WorkflowReceiptResponse(signed.receipt, dto))
        }
        get<ReceiptBundleApi> { params ->
            call.respond(bundle(bundleReader(call), params.id).toJson())
        }
    }
    // The runner channel: the execution's runner token, checked before the body is read (S2-46), never a member's.
    post<RunnerChecksApi> {
        val token = call.runnerToken()
        checkRunnerToken(token)
        val body = call.receive<RunnerCheckRequest>()
        val cloudSession = Uuid.parseOrNull(body.sessionId)
            ?: throw InvalidRunException("sessionId: the cloud session's id.")
        val execution = authenticateRunner(token, cloudSession, body.epoch)
        call.respond(HttpStatusCode.Created, reportRunnerCheck(execution.orgId, execution.id, body.check))
    }
}

// The scope first (SECURITY-AUDIT S2-03): a credential that may not use the route learns nothing about its org.
private suspend fun WorkflowRunsRoutesDependencies.actorOf(call: ApplicationCall, accepted: Set<String>): GateActor {
    val principal = call.requirePrincipal()
    principal.requireAnyScope(accepted)
    return resolve(principal)
}

private suspend fun WorkflowRunsRoutesDependencies.resolve(principal: AuthenticatedPrincipal): GateActor =
    actors.resolve(principal)
        ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")

// A person reads receipts with a receipts scope (S2-47); an agent token only its own launch's (the use case checks).
private suspend fun WorkflowRunsRoutesDependencies.bundleReader(call: ApplicationCall): GateActor {
    val principal = call.requirePrincipal()
    if (principal.kind == PrincipalKind.AGENT) return actorOf(call, setOf(GateScopes.AGENT_SESSION))
    principal.requireReceiptReader()
    return resolve(principal)
}

// The next page's cursor: the last id of a full page, none after a shorter one.
private fun nextOf(ids: List<String>, limit: Int): String? = if (ids.size == limit) ids.last() else null

private fun statusOf(value: String?): NotificationStatus? = value?.let { text ->
    NotificationStatus.entries.filter { it != NotificationStatus.UNKNOWN }.firstOrNull { it.value == text }
        ?: throw InvalidRunException("status: queued, sent, logged-only or not-sent.")
}

// `package` is always written, null when the receipt cites none (the contract requires the key).
private fun ReceiptBundle.toJson(): JsonObject = buildJsonObject {
    put("format", BUNDLE_FORMAT)
    put("receipt", receipt)
    put("chain", JsonArray(chain))
    val pack = envelope
    put("package", if (pack == null) JsonNull else OstromJson.encodeToJsonElement(PackageEnvelope.serializer(), pack))
    put(
        "keys",
        JsonArray(
            keys.map {
                buildJsonObject {
                    put("kid", it.kid)
                    put("alg", it.algorithm)
                    put("publicKeyPem", it.publicKeyPem)
                }
            },
        ),
    )
    put("exportedAt", exportedAt)
}

/** The format of a receipt bundle, which `ostrom verify` reads. */
const val BUNDLE_FORMAT: String = "ostrom.receipt-bundle/1"

/**
 * The runner token of a runner call, `Authorization: Bearer ostrom_runner_…`, read as the runners module reads it
 * (a module never imports another one's presentation).
 *
 * @throws RunnerUnauthenticatedException When absent.
 */
private fun ApplicationCall.runnerToken(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: throw RunnerUnauthenticatedException()
    val token = header.removePrefix("Bearer ").trim()
    if (token == header || token.isEmpty()) throw RunnerUnauthenticatedException()
    return token
}
