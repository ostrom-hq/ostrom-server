// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.presentation

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.requireAnyScope
import dev.ostrom.server.kernel.auth.OrganizationNotFoundException
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionQuery
import dev.ostrom.server.sessions.domain.models.SessionView
import dev.ostrom.server.sessions.domain.models.StartSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The sessions routes of the contract. Every route is authenticated; the organization and the person always come
 * from the credential, never from the body.
 *
 * @param dependencies The use cases.
 */
fun Route.sessionsRoutes(dependencies: SessionsRoutesDependencies) = with(dependencies) {
    authenticated {
        post<SessionsApi> {
            val actor = call.actor(actors, SessionScopes.writing)
            val body = call.receive<StartSessionRequest>()
            call.respond(get.view(start(actor, body.toStart())).toResponse())
        }
        get<SessionsApi> { params ->
            val actor = call.actor(actors, SessionScopes.reading)
            val limit = (params.limit ?: PAGE).coerceIn(1, SessionQuery.PAGE)
            val query = SessionQuery(params.repo, params.dev, params.open, params.before, null, limit)
            val items = list(actor, query)
            val next = if (items.size == query.limit) items.last().id else null
            call.respond(SessionsPage(items.map { it.toDto() }, next))
        }
        get<SessionsApi.Id> { params ->
            call.respond(get(call.actor(actors, SessionScopes.reading), params.id).toResponse())
        }
        post<SessionsApi.Id.Alive> { params ->
            val session = alive(call.actor(actors, SessionScopes.writing), params.parent.id)
            call.respond(session.toDto())
        }
        post<SessionsApi.Id.End> { params ->
            val body = call.receive<EndSessionRequest>()
            // S2-48: `costUsd` of the request is never read; the server measures the cost.
            val end = EndSession(tools = body.tools, model = body.model, source = body.source, exitCode = body.exitCode)
            call.respond(get.view(end(call.actor(actors, SessionScopes.writing), params.parent.id, end)).toResponse())
        }
        post<SessionsApi.Id.Reopen> { params ->
            val reopened = reopen(call.actor(actors, SessionScopes.writing), params.parent.id)
            call.respond(get.view(reopened.session).toResponse(reopened.reopened))
        }
        post<SessionsApi.Id.Seen> { params ->
            val body = call.receive<SeenRequest>()
            seen(call.actor(actors, SessionScopes.writing), params.parent.id, body.kind, body.name, body.via)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// The scope first (SECURITY-AUDIT S2-03): a credential that may not use the route learns nothing about its org.
private suspend fun ApplicationCall.actor(actors: GateActorResolver, accepted: Set<String>): GateActor {
    val principal = requirePrincipal()
    principal.requireAnyScope(accepted)
    return actors.resolve(principal)
        ?: throw OrganizationNotFoundException()
}

/** The scopes the sessions routes require; a user's browser session (`user:*`) holds them all. */
object SessionScopes {
    /** Reads sessions. */
    const val READ: String = "sessions:read"

    /** Starts, keeps alive, reopens and ends sessions (the CLI for its user). */
    const val WRITE: String = "sessions:write"

    /** Who changes a session: the launcher's CLI token and the agent token of the session itself. */
    val writing: Set<String> = setOf(GateScopes.ALL_USER, GateScopes.AGENT_SESSION, WRITE)

    /** Who reads one. */
    val reading: Set<String> = setOf(GateScopes.ALL_USER, GateScopes.AGENT_SESSION, WRITE, READ)
}

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
