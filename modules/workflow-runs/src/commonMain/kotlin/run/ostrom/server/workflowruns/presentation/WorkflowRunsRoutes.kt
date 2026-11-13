// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.presentation

import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.core.api.models.ReportCheckRequest
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.gates.presentation.requireAnyScope
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.admitServiceKey
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.withNulls
import run.ostrom.server.receipts.domain.models.Verification
import run.ostrom.server.receipts.presentation.requireReceiptReader
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.sessions.presentation.SessionScopes
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.repositories.RunQuery
import run.ostrom.server.workflowruns.domain.usecases.NotificationRequest
import run.ostrom.server.workflowruns.domain.usecases.ReceiptBundle
import run.ostrom.server.workflowruns.domain.usecases.WorkflowReceiptRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * The workflow-runs routes of the contract. Reports (checks, notifications, the workflow receipt) come from the
 * launcher's hooks with the launch's agent token, or from the session's person; the lists are any member's, an agent
 * token's for its own session; a bundle is read by a person who reads receipts, or by the agent token of the
 * receipt's session's launch (`ostrom <agent>` keeps it at the end). Outside the contract, the runner of a cloud
 * session reports the checks it ran itself on `/api/v1/cloud/runner/checks`, with its runner token. A service key with
 * `sessions:write` (the organization's CI) also reports a check and signs an ended session's receipt, in its own
 * organization; it reads nothing here. The runs of workflows are in [startedRunsRoutes], the agent step of a session
 * in [workflowSessionRoutes].
 *
 * @param dependencies The use cases.
 */
fun Route.workflowRunsRoutes(dependencies: WorkflowRunsRoutesDependencies) = with(dependencies) {
    authenticated {
        post<ChecksApi> {
            val actor = writerOf(call, "reportCheck")
            val run = reportCheck(actor, call.receive<ReportCheckRequest>())
            call.respond(HttpStatusCode.Created, OstromJson.encodeToJsonElement(CheckRunAnswer, run))
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
            val actor = writerOf(call, "signWorkflowReceipt")
            val body = call.receive<WorkflowReceiptRequestBody>()
            val request = WorkflowReceiptRequest(params.id, body.digest, body.agent, body.model)
            val signed = signWorkflowReceipt(actor, request)
            call.respond(WorkflowReceiptResponse(signed.receipt, signed.verification.toDto()))
        }
        get<ReceiptBundleApi> { params ->
            call.respond(bundle(bundleReader(call), params.id).toJson())
        }
        startedRunsRoutes(dependencies)
        workflowSessionRoutes(dependencies)
    }
    // The runner channel: the execution's runner token, checked before the body is read (S2-46), never a member's.
    post<RunnerChecksApi> {
        val token = call.runnerToken()
        checkRunnerToken(token)
        val body = call.receive<RunnerCheckRequest>()
        val cloudSession = Uuid.parseOrNull(body.sessionId)
            ?: throw InvalidRunException("sessionId: the cloud session's id.")
        val execution = authenticateRunner(token, cloudSession, body.epoch)
        val run = reportRunnerCheck(execution.orgId, execution.id, body.check)
        call.respond(HttpStatusCode.Created, OstromJson.encodeToJsonElement(CheckRunAnswer, run))
    }
}

// The two writes a CI makes: a service key of the organization with `sessions:write` reports for any of its sessions,
// in its own organization, each call audited in its name; anyone else as on every other write.
private suspend fun WorkflowRunsRoutesDependencies.writerOf(call: ApplicationCall, operation: String): GateActor {
    val principal = call.requirePrincipal()
    val orgId = principal.admitServiceKey(serviceKeys, SERVICE_KEY_WRITES, operation, call.request.path())
        ?: return actorOf(call, SessionScopes.writing)
    return GateActor(principal.subjectId, orgId, ActorKind.SERVICE)
}

/** The scope a service key needs to report a check and sign a session's workflow receipt. */
private const val SERVICE_KEY_WRITES = "sessions:write"

// The scope first (SECURITY-AUDIT S2-03): a credential that may not use the route learns nothing about its org.
internal suspend fun WorkflowRunsRoutesDependencies.actorOf(call: ApplicationCall, accepted: Set<String>): GateActor {
    val principal = call.requirePrincipal()
    principal.requireAnyScope(accepted)
    return resolve(principal)
}

private suspend fun WorkflowRunsRoutesDependencies.resolve(principal: AuthenticatedPrincipal): GateActor =
    actors.resolve(principal)
        ?: throw principal.outsideOrganization()

// A person reads receipts with a receipts scope (S2-47); an agent token only its own launch's (the use case checks).
private suspend fun WorkflowRunsRoutesDependencies.bundleReader(call: ApplicationCall): GateActor {
    val principal = call.requirePrincipal()
    if (principal.kind == PrincipalKind.AGENT) return actorOf(call, setOf(GateScopes.AGENT_SESSION))
    principal.requireReceiptReader()
    return resolve(principal)
}

// The next page's cursor: the last id of a full page, none after a shorter one.
internal fun nextOf(ids: List<String>, limit: Int): String? = if (ids.size == limit) ids.last() else null

private fun statusOf(value: String?): NotificationStatus? = value?.let { text ->
    NotificationStatus.entries.filter { it != NotificationStatus.UNKNOWN }.firstOrNull { it.value == text }
        ?: throw InvalidRunException("status: queued, sent, logged-only or not-sent.")
}

// `package` is always written, null when the receipt cites none, and so is its `repo`, null for an organization's
// package (the contract requires both keys).
private fun ReceiptBundle.toJson(): JsonObject = buildJsonObject {
    put("format", BUNDLE_FORMAT)
    put("receipt", receipt)
    put("chain", JsonArray(chain))
    val pack = envelope?.let { OstromJson.encodeToJsonElement(PackageEnvelope.serializer(), it).withNulls("repo") }
    put("package", pack ?: JsonNull)
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

private fun Verification.toDto() = VerificationDto(ok, digest, kid, reason, orgBound)

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
