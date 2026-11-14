// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.presentation

import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * A member's own MCP servers under `/api/v1/me/mcp/servers` (TeamCode #269), and an administrator's promotion of one:
 * read by the person, changed from the console with a recent sign-in.
 */
internal fun Route.personalRoutes(dependencies: McpRelayRoutesDependencies) = with(dependencies) {
    get<MyMcpServersApi> {
        val caller = call.requirePerson(callers, RelayScopes.MCP_READ)
        val servers = personal.list(caller)
        val names = directory.namesById(caller.orgId, servers.flatMap { it.secretIds() })
        call.respond(McpServersPage(servers.map { it.toDto(names) }))
    }
    post<MyMcpServersApi> {
        val caller = call.personalWriter(callers)
        val request = call.receive<McpServerDto>()
        // A member's server names their own personal secrets.
        val payload = request.toPayload(directory.idsByName(caller.orgId, caller.memberId, request.secretNamesUsed()))
        val server = personal.create(caller, payload)
        recordChange(call, caller, "mcp.server.personal.created", server.name, server.createdDetails())
        call.respond(HttpStatusCode.Created, server.toDto(directory.namesById(caller.orgId, server.secretIds())))
    }
    put<MyMcpServersApi.Name> { params ->
        val caller = call.personalWriter(callers)
        val request = call.receive<McpServerDto>()
        val payload = request.toPayload(directory.idsByName(caller.orgId, caller.memberId, request.secretNamesUsed()))
        val server = personal.update(caller, params.name, payload)
        recordChange(call, caller, "mcp.server.personal.updated", server.name, emptyMap())
        call.respond(server.toDto(directory.namesById(caller.orgId, server.secretIds())))
    }
    delete<MyMcpServersApi.Name> { params ->
        val caller = call.personalWriter(callers)
        val server = personal.delete(caller, params.name)
        recordChange(call, caller, "mcp.server.personal.deleted", server.name, emptyMap())
        call.respond(HttpStatusCode.NoContent)
    }
    post<McpApi.Servers.Name.Promote> { params ->
        val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
        call.requireRecentSignIn("change an MCP server")
        val (server, owner) = personal.promote(caller, params.parent.name)
        if (owner != null) {
            recordChange(call, caller, "mcp.server.promoted", server.name, mapOf("owner" to owner.toString()))
        }
        call.respond(server.toDto(directory.namesById(caller.orgId, server.secretIds())))
    }
}

/**
 * A person's own read: a service key is refused even when it holds the scope (a member's servers are theirs, never a
 * robot's), and so are agent and device tokens ([requireCaller] admits neither).
 */
private suspend fun ApplicationCall.requirePerson(callers: CallerResolver, scope: String): Caller {
    if (requirePrincipal().kind == PrincipalKind.SERVICE) {
        throw OstromException(ErrorKind.FORBIDDEN, "A member's own MCP servers are read by the member.")
    }
    return requireCaller(callers, scope)
}

/** A person's own change, from the console signed in within five minutes (step-up). */
private suspend fun ApplicationCall.personalWriter(callers: CallerResolver): Caller {
    val caller = requirePerson(callers, RelayScopes.MCP_ADMIN)
    requireRecentSignIn("change an MCP server")
    return caller
}
