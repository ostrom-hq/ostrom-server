// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.presentation

import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.usecases.GateUse
import dev.ostrom.server.gates.domain.usecases.ListApprovalsUseCase
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.outsideOrganization
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.Pages
import dev.ostrom.server.kernel.http.ReadThrottle
import dev.ostrom.server.kernel.http.throttledReads
import dev.ostrom.server.kernel.http.throttledWaits
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The gates and approvals routes of the contract. Every route is authenticated; the organization and the person
 * always come from the credential, never from the body.
 *
 * @param dependencies The use cases.
 * @param throttle Bounds the reads that cost the most per caller: a gate and its wait, the approvals.
 */
fun Route.gatesRoutes(dependencies: GatesRoutesDependencies, throttle: ReadThrottle) = with(dependencies) {
    authenticated {
        post<GatesApi> {
            val actor = actors.actorOf(call.requirePrincipal(), GateScopes.asking)
            call.respond(ask(actor, call.receive<GateAskRequest>().toAskGate()).toResponse(actor))
        }
        // A hook waits here for its answer, at most 30 s, instead of asking again every second. Its waits are counted
        // apart from the reads: an agent waiting on many gates keeps its reads (AUDIT-SECU-LECTURES M1).
        throttledWaits(throttle) {
            get<GatesApi.Id> { params ->
                val actor = actors.actorOf(call.requirePrincipal(), GateScopes.readingGates)
                call.respond(gate(actor, params.id.toUuid(), (params.wait ?: 0).seconds).toResponse(actor))
            }
        }
        throttledReads(throttle) {
            get<ApprovalsApi> { params ->
                val actor = actors.actorOf(call.requirePrincipal(), GateScopes.readingApprovals)
                val status = params.status?.let { statusOf(it) }
                val size = Pages.size(params.limit, DEFAULT_PAGE, ListApprovalsUseCase.MAX_LIMIT)
                val before = params.before?.let { Uuid.parseOrNull(it) ?: Pages.unknownCursor("before") }
                val items = approvals(actor, params.mine == true, status, size, before)
                val next = if (items.size == size) items.last().request.id.toString() else null
                call.respond(ApprovalsPage(items.map { it.toApproval() }, next))
            }
        }
        post<GatesApi.Id.Reserve> { params -> respondUse(dependencies, params.parent.id, GateUse.RESERVE) }
        post<GatesApi.Id.Consume> { params -> respondUse(dependencies, params.parent.id, GateUse.CONSUME) }
        post<GatesApi.Id.Release> { params -> respondUse(dependencies, params.parent.id, GateUse.RELEASE) }
        get<ApprovalsApi.Id> { params ->
            val actor = actors.actorOf(call.requirePrincipal(), GateScopes.readingApprovals)
            call.respond(approval(actor, params.id.toUuid()).toApproval())
        }
        get<ApprovalsApi.Queue> {
            val principal = call.requirePrincipal()
            val actor = actors.actorOf(principal, GateScopes.readingApprovals)
            call.respond(queue(actor, principal.toViewer()).toQueue())
        }
        get<ApprovalsApi.Id.View> { params ->
            val principal = call.requirePrincipal()
            val actor = actors.actorOf(principal, GateScopes.readingApprovals)
            call.respond(view(actor, principal.toViewer(), params.parent.id.toUuid()).toViewResponse())
        }
        post<ApprovalsApi.Id.Answer> { params ->
            val principal = call.requirePrincipal()
            principal.requireAnswering()
            // S2-54: the sign-in's age comes from the identity module, never from the request.
            val actor = actors.actorOf(principal, GateScopes.answering)
                .copy(authenticatedAt = principal.authenticatedAt)
            val body = call.receive<AnswerApprovalRequest>()
            val id = params.parent.id.toUuid()
            val choice = if (body.answer == ApprovalAnswer.APPROVE) Answer.YES else Answer.NO
            val answered = answer(actor, id, choice, body.shownDigest, AnswerChannel.CONSOLE)
            // The contract's 200 is the approval as the console and the app show it, answered, with the signed
            // receipt of the decision (a no's included).
            val receipt = receipts.read(answered.gate.orgId, answered.gate.receiptId)
            call.respond(view(actor, principal.toViewer(), id).toViewResponse().copy(receipt = receipt))
        }
    }
}

private suspend fun RoutingContext.respondUse(dependencies: GatesRoutesDependencies, id: String, move: GateUse) {
    val actor = dependencies.actors.actorOf(call.requirePrincipal(), GateScopes.using)
    val gateId = id.toUuid()
    dependencies.use(actor, gateId, move)
    call.respond(dependencies.gate(actor, gateId).toResponse(actor))
}

// The scope first: a credential that may not use the route learns nothing about its organization.
private suspend fun GateActorResolver.actorOf(principal: AuthenticatedPrincipal, accepted: Set<String>): GateActor {
    principal.requireAnyScope(accepted)
    return resolve(principal)
        ?: throw principal.outsideOrganization()
}

private fun String.toUuid(): Uuid = Uuid.parseOrNull(this) ?: throw InvalidGateRequestException("Malformed id.")

private fun statusOf(value: String): RequestStatus = when (value.lowercase()) {
    "pending" -> RequestStatus.PENDING
    "approved" -> RequestStatus.APPROVED
    "rejected" -> RequestStatus.REJECTED
    "expired" -> RequestStatus.CLOSED
    else -> throw InvalidGateRequestException("Unknown approval status.")
}

private const val DEFAULT_PAGE = 50
