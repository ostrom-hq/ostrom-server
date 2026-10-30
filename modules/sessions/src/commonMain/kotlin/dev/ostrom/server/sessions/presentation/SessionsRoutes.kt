package dev.ostrom.server.sessions.presentation

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionQuery
import dev.ostrom.server.sessions.domain.models.SessionView
import dev.ostrom.server.sessions.domain.models.StartSession
import dev.ostrom.server.sessions.domain.usecases.EndSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.GetSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.ListSessionsUseCase
import dev.ostrom.server.sessions.domain.usecases.ReopenSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.ReportSeenUseCase
import dev.ostrom.server.sessions.domain.usecases.SessionAliveUseCase
import dev.ostrom.server.sessions.domain.usecases.StartSessionUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * What the sessions routes need.
 *
 * @property actors Resolves callers.
 * @property start Starts a segment.
 * @property end Ends one.
 * @property alive Records a sign of life.
 * @property reopen Reopens after a closed one.
 * @property seen Records a skill or sub-agent opened.
 * @property get Reads one.
 * @property list Lists them.
 */
data class SessionsRoutesDependencies(
    val actors: GateActorResolver,
    val start: StartSessionUseCase,
    val end: EndSessionUseCase,
    val alive: SessionAliveUseCase,
    val reopen: ReopenSessionUseCase,
    val seen: ReportSeenUseCase,
    val get: GetSessionUseCase,
    val list: ListSessionsUseCase,
)

/**
 * The sessions routes of the contract. Every route is authenticated; the organization and the person always come
 * from the credential, never from the body.
 *
 * @param dependencies The use cases.
 */
fun Route.sessionsRoutes(dependencies: SessionsRoutesDependencies) = with(dependencies) {
    authenticated {
        post<SessionsApi> {
            val actor = call.actor(actors)
            val body = call.receive<StartSessionRequest>()
            call.respond(get.view(start(actor, body.toStart())).toResponse())
        }
        get<SessionsApi> { params ->
            val actor = call.actor(actors)
            val limit = (params.limit ?: PAGE).coerceIn(1, SessionQuery.PAGE)
            val query = SessionQuery(params.repo, params.dev, params.open, params.before, null, limit)
            val items = list(actor, query)
            val next = if (items.size == query.limit) items.last().id else null
            call.respond(SessionsPage(items.map { it.toDto() }, next))
        }
        get<SessionsApi.Id> { params -> call.respond(get(call.actor(actors), params.id).toResponse()) }
        post<SessionsApi.Id.Alive> { params ->
            val session = alive(call.actor(actors), params.parent.id)
            call.respond(session.toDto())
        }
        post<SessionsApi.Id.End> { params ->
            val body = call.receive<EndSessionRequest>()
            val end = EndSession(body.tools, body.model, body.costUsd, body.source, body.exitCode)
            call.respond(get.view(end(call.actor(actors), params.parent.id, end)).toResponse())
        }
        post<SessionsApi.Id.Reopen> { params ->
            val reopened = reopen(call.actor(actors), params.parent.id)
            call.respond(get.view(reopened.session).toResponse(reopened.reopened))
        }
        post<SessionsApi.Id.Seen> { params ->
            val body = call.receive<SeenRequest>()
            seen(call.actor(actors), params.parent.id, body.kind, body.name, body.via)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun ApplicationCall.actor(actors: GateActorResolver): GateActor =
    actors.resolve(requirePrincipal())
        ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")

private fun StartSessionRequest.toStart() =
    StartSession(id, launchId, repo, realRepo, cwd, agent, harnessVersion, packageDigest, model)

private fun SessionView.toResponse(reopened: Boolean? = null) =
    SessionResponse(session.toDto(gates), receipt, reopened)

/**
 * Maps a segment for the contract.
 *
 * @param gates Its gates, on single reads.
 * @return The DTO.
 */
internal fun Session.toDto(gates: List<String>? = null) = SessionDto(
    id = id,
    agentSessionId = rootId.takeIf { segment > 1 },
    segment = segment.takeIf { it > 1 },
    resumedFrom = resumedFrom,
    previousReceiptId = previousReceiptId,
    launchId = launchId,
    repo = repo,
    realRepo = realRepo,
    cwd = cwd,
    agent = agent,
    dev = dev,
    harnessVersion = harnessVersion,
    packageDigest = packageDigest,
    model = model,
    startedAt = startedAt.toString(),
    endedAt = endedAt?.toString(),
    endedBy = source.takeIf { endedAt != null },
    lastSeenAt = lastSeenAt?.toString(),
    gates = gates,
    receiptId = receiptId,
    tools = tools,
    costUsd = costUsd,
    exitCode = exitCode,
    ended = endedAt != null,
    lost = lost,
)

private const val PAGE = 50
