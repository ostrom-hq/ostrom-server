// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.presentation

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.mcprelay.domain.McpServerNotFoundException
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import run.ostrom.server.secrets.presentation.toIdOr
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

// TeamCode's "Hosted commands": the processes the MCP host runs for the org, and their Restart.
internal fun Route.hostedRoutes(dependencies: McpRelayRoutesDependencies) = with(dependencies) {
    get<McpApi.Hosted> {
        val caller = call.requireCaller(callers, RelayScopes.MCP_READ)
        call.respond(listHosted(caller).toDto())
    }
    post<McpApi.Hosted.Restart> {
        val caller = call.requireCaller(callers, RelayScopes.MCP_ADMIN)
        call.requireRecentSignIn("restart an MCP server")
        val request = call.receive<McpHostedRestartDto>()
        val member = request.member?.toIdOr(::McpServerNotFoundException)
        restartHosted(caller, request.server, member) {
            val subject = if (member == null) request.server else "${request.server}/$member"
            val principal = call.requirePrincipal()
            val record =
                OrgAuditRecord(caller.orgId, principal, "mcp.stdio.restarted", "mcp_server", subject, emptyMap())
            audit.record(record)
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
