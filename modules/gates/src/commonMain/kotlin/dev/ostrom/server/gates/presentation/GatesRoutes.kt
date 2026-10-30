package dev.ostrom.server.gates.presentation

import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.gates.domain.usecases.GateUse
import dev.ostrom.server.gates.domain.usecases.GetGateUseCase
import dev.ostrom.server.gates.domain.usecases.ListPendingRequestsUseCase
import dev.ostrom.server.gates.domain.usecases.UseGateUseCase
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/** Turns the authenticated principal into a gate actor (the identity module binds the real one). */
interface GateActorResolver {
    /**
     * Resolves the caller.
     *
     * @param principal The authenticated principal.
     * @return The actor, or null when the credential belongs to no organization.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal): GateActor?
}

/**
 * What the gates routes need.
 *
 * @property actors Resolves callers.
 * @property ask Opens a gate for a hook.
 * @property get Reads a gate.
 * @property use Reserves, consumes or releases a yes.
 * @property answer Answers a request.
 * @property pending The caller's inbox.
 */
data class GatesRoutesDependencies(
    val actors: GateActorResolver,
    val ask: AskGateUseCase,
    val get: GetGateUseCase,
    val use: UseGateUseCase,
    val answer: AnswerGateUseCase,
    val pending: ListPendingRequestsUseCase,
)

/**
 * The gates routes. Every route is authenticated; the organization always comes from the credential.
 *
 * @param dependencies The use cases.
 */
fun Route.gatesRoutes(dependencies: GatesRoutesDependencies) = with(dependencies) {
    authenticated {
        post<GatesApi> {
            val actor = actors.actorOf(call.requirePrincipal())
            val body = call.receive<OpenGateRequest>()
            call.respond(ask(actor, body.toAskGate()).toGateResponse())
        }
        get<GatesApi.Id> { params ->
            val actor = actors.actorOf(call.requirePrincipal())
            call.respond(get(actor, params.id.toUuid()).toGateResponse())
        }
        post<GatesApi.Id.Use> { params ->
            val actor = actors.actorOf(call.requirePrincipal())
            val move = GateUse.entries.firstOrNull { it.name.equals(params.use, ignoreCase = true) }
                ?: throw OstromException(ErrorKind.NOT_FOUND, "Unknown gate action.")
            use(actor, params.parent.id.toUuid(), move)
            call.respond(get(actor, params.parent.id.toUuid()).toGateResponse())
        }
        get<ApprovalsApi> { params ->
            val actor = actors.actorOf(call.requirePrincipal())
            call.respond(ApprovalsResponse(pending(actor, params.limit).map { it.toResponse() }))
        }
        post<ApprovalsApi.Answer> { params ->
            val actor = actors.actorOf(call.requirePrincipal())
            val body = call.receive<AnswerRequest>()
            val view = answer(
                actor,
                params.id.toUuid(),
                if (body.answer == AnswerDto.YES) Answer.YES else Answer.NO,
                body.shownDigest,
                AnswerChannel.valueOf(body.channel.name),
            )
            call.respond(view.toGateResponse())
        }
    }
}

private suspend fun GateActorResolver.actorOf(principal: AuthenticatedPrincipal): GateActor =
    resolve(principal) ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")

private fun String.toUuid(): Uuid = Uuid.parseOrNull(this) ?: throw InvalidGateRequestException("Malformed id.")

/** Maps the hook's request; the path is the hook unless the MCP tool asks. */
internal fun OpenGateRequest.toAskGate() = AskGate(
    sessionId = sessionId?.toUuid(),
    repo = repo,
    realRepo = realRepo,
    actionClass = actionClass,
    command = command,
    push = push?.let { dto -> GatePush(dto.remote, dto.refs.map { PushRef(it.ref, it.sha) }, dto.force) },
    pullRequest = pullRequest,
    commits = commits,
    diff = diff,
    redirected = redirected,
    via = AskedVia.HOOK,
)

/** Maps a gate for the clients. */
internal fun GateView.toGateResponse() = GateResponse(
    id = gate.id.toString(),
    label = gate.label,
    mode = gate.mode.name.lowercase(),
    status = gate.status.name.lowercase(),
    repo = gate.subject.repo,
    actionClass = gate.subject.actionClass,
    commits = gate.subject.commits,
    pullRequest = gate.subject.pullRequest?.let { "#${it.number}@${it.headSha}->${it.base}" },
    askedBy = gate.askedBy,
    askedVia = gate.askedVia.map { it.name.lowercase() }.sorted(),
    waitingFor = waitingFor,
    shownDigest = gate.shownDigest,
    evidence = gate.evidence.map { EvidenceDto(it.label, it.value) },
    requests = requests.map { it.toResponse() },
    decidedBy = gate.decidedBy,
    decidedAt = gate.decidedAt?.toString(),
    selfApproved = gate.selfApproved,
    receiptId = gate.receiptId,
    createdAt = gate.createdAt.toString(),
)

/** Maps a request for the clients. */
internal fun ApprovalRequest.toResponse() = ApprovalRequestResponse(
    id = id.toString(),
    gateId = gateId.toString(),
    approver = approverId,
    status = status.name.lowercase(),
    answeredAt = answeredAt?.toString(),
    channel = channel?.name?.lowercase(),
)
