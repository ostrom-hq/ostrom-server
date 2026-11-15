// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.anyJson
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryFlag
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "mcp"

private const val STEP_UP = "A write from the console needs a sign-in less than 5 minutes old (`step_up_required`); " +
    "sign in again, then send it again. Otherwise the caller's role or scope does not allow it (`forbidden`)."

private const val SERVER_NAME = "The server's name, unique in the organization."

private const val SERVER_NF = "No such server in the organization (`not_found`)."

private const val MEMBER = "The member's id (`Member.id`), or `all` for every member of the organization."

private const val PERSONAL_NOTE = "A member's personal server is refused with 409 `mcp_server_personal`: promote it " +
    "first."

private const val PERSONAL_WRITE = "A person only: a service key, an agent or a device token is refused " +
    "(`forbidden`). Reads take `mcp:read`, writes `mcp:admin` on a token (the same scopes as the team's routes), " +
    "from the console with a recent sign-in. A hosted stdio command is refused with 403 `hosted_not_allowed` " +
    "unless an administrator turned `memberHostedMcp` on (`putMcpSettings`, off by default)."

private const val INVALID_SERVER = "An invalid server: a name, URL, transport, command, variable or tool list the " +
    "rules refuse, or a secret name that is not the organization's (`invalid_request`)."

private const val NAME_TAKEN = "The name is taken by a team server or a member's personal one (`conflict`), or " +
    "the organization holds its limit of servers (`mcp_server_limit`), or a stdio server on a server without an MCP " +
    "host (`conflict`)."

/**
 * The MCP relay module's operations: the servers an organization relays to its agents, who reaches them and with
 * which tools, the argument policies, the journal of the calls, the processes run on the MCP host, and the
 * Streamable HTTP endpoint agents call.
 */
internal fun ApiCatalog.mcpRelayOperations() {
    tag(
        TAG,
        "The MCP servers an organization relays to its agents, who reaches them, and the calls that went through.",
    )
    serverOperations()
    serverChangeOperations()
    personalServerOperations()
    personalServerChangeOperations()
    assignmentOperations()
    policyOperations()
    journalOperations()
    hostedOperations()
    endpointOperations()
}

/** The team's servers: listing and creating. */
private fun ApiCatalog.serverOperations() {
    get("/api/v1/mcp/servers") {
        operationId = "listMcpServers"
        tag(TAG)
        summary = "The MCP servers relayed by Ostrom."
        description = "The whole list, with upstream URLs and secret names, is for an administrator with `mcp:read` " +
            "or a browser session. A member, an agent session or an `mcp:use` token always gets the servers it " +
            "reaches, whatever `mine` says. Credentials are named, never sent."
        parameters {
            organizationHeader()
            queryFlag(
                "mine",
                "Only the servers the caller reaches, with their tools and agent names (what `ostrom run claude` " +
                    "connects).",
            )
            queryFlag(
                "personal",
                "The members' personal MCP servers (`mcp.personal.v1`), by owner, with `owner` and their " +
                    "definition for review, never a credential. An administrator with `mcp:read`; the first 500, " +
                    "in owner order, without `next`. Without it, the list is the team's servers only.",
            )
        }
        responses {
            ok<McpServersPage>("The servers, by name.")
        }
    }
    post("/api/v1/mcp/servers") {
        operationId = "createMcpServer"
        tag(TAG)
        summary = "Add a relayed MCP server."
        description = "Credentials are given as secret names, never as values. At most 64 servers. Audited " +
            "(`mcp.server.created`). Scope `mcp:admin`, from the console."
        parameters { organizationHeader() }
        body<McpServerDto>("The server: its name, transport, URL or stdio command, and the names of its secrets.")
        responses {
            ok<McpServerDto>("The server, created.", HttpStatusCode.Created)
            badRequest(INVALID_SERVER)
            error(HttpStatusCode.Forbidden, STEP_UP)
            conflict(NAME_TAKEN)
        }
    }
}

/** Changing, removing and promoting a team server. */
private fun ApiCatalog.serverChangeOperations() {
    put("/api/v1/mcp/servers/{name}") {
        operationId = "saveMcpServer"
        tag(TAG)
        summary = "Change a relayed MCP server."
        description = "Creates the server when the name is new. The name of the body is the name of the path. " +
            "$PERSONAL_NOTE Audited (`mcp.server.updated`, or `mcp.server.created`). Scope `mcp:admin`, from the " +
            "console."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        body<McpServerDto>("The server, replaced.")
        responses {
            ok<McpServerDto>("The server as saved.")
            badRequest(INVALID_SERVER)
            error(HttpStatusCode.Forbidden, STEP_UP)
            conflict("A personal server (`mcp_server_personal`), or the name or the limit as `createMcpServer`.")
        }
    }
    delete("/api/v1/mcp/servers/{name}") {
        operationId = "deleteMcpServer"
        tag(TAG)
        summary = "Remove a relayed MCP server."
        description = "With its assignments and argument policies. An administrator deletes a member's personal " +
            "server this way too. Audited (`mcp.server.deleted`). Scope `mcp:admin`, from the console."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        responses {
            empty("Removed.")
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound(SERVER_NF)
        }
    }
    post("/api/v1/mcp/servers/{name}/promote") {
        operationId = "promoteMcpServer"
        tag(TAG)
        summary = "Make a member's personal MCP server a team server."
        description = "(`mcp.personal.v1`) The owner is cleared and keeps their assignment; everyone else reaches " +
            "the server only by assignment. A team server is returned as it is. Audited (`mcp.server.promoted`). " +
            "Scope `mcp:admin`, from the console."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        responses {
            ok<McpServerDto>("The team server.")
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound(SERVER_NF)
            conflict(
                "It would exceed the team's 64 servers (`mcp_server_limit`), or it uses its owner's personal " +
                    "secrets, which they remove first (`conflict`).",
            )
        }
    }
}

/** A member's own servers, under `/api/v1/me/mcp/servers`: listing and declaring. */
private fun ApiCatalog.personalServerOperations() {
    get("/api/v1/me/mcp/servers") {
        operationId = "listMyMcpServers"
        tag(TAG)
        summary = "The caller's personal MCP servers."
        description = "(`mcp.personal.v1`) With the personal secret names they use. $PERSONAL_WRITE"
        parameters { organizationHeader() }
        responses {
            ok<McpServersPage>("The caller's own servers, by name.")
        }
    }
    post("/api/v1/me/mcp/servers") {
        operationId = "createMyMcpServer"
        tag(TAG)
        summary = "Declare a personal MCP server."
        description = "Relayed to the caller alone: it is assigned to them in the same transaction, with every " +
            "tool, and nobody else reaches it (an administrator cannot assign or save it: 409 " +
            "`mcp_server_personal`). The body is a team server's; `owner` is set by the server. `secretNames` name " +
            "the caller's own personal secrets. Its name shares the organization's namespace. A remote HTTPS " +
            "server follows the team's upstream address checks; a hosted command follows the launcher and " +
            "exact-version rules of a team command and always runs per member. At most 20 per member (409 " +
            "`mcp_server_limit`); they do not count in the team's 64. $PERSONAL_WRITE Audited " +
            "(`mcp.server.personal.created`)."
        parameters { organizationHeader() }
        body<McpServerDto>("The server, as a team server's body.")
        responses {
            ok<McpServerDto>("The server, created.", HttpStatusCode.Created)
            badRequest(INVALID_SERVER)
            error(HttpStatusCode.Forbidden, STEP_UP)
            conflict(NAME_TAKEN)
        }
    }
}

/** Changing and deleting a member's own server. */
private fun ApiCatalog.personalServerChangeOperations() {
    put("/api/v1/me/mcp/servers/{name}") {
        operationId = "saveMyMcpServer"
        tag(TAG)
        summary = "Change a personal MCP server."
        description = "With the rules of `createMyMcpServer`. Another member's server, or a team server, is 404. " +
            "$PERSONAL_WRITE Audited (`mcp.server.personal.updated`)."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        body<McpServerDto>("The server, replaced.")
        responses {
            ok<McpServerDto>("The server as saved.")
            badRequest(INVALID_SERVER)
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound("No such personal server of the caller (`not_found`).")
        }
    }
    delete("/api/v1/me/mcp/servers/{name}") {
        operationId = "deleteMyMcpServer"
        tag(TAG)
        summary = "Delete a personal MCP server."
        description = "With its assignment. Another member's server, or a team server, is 404 (an administrator " +
            "deletes a personal one with `deleteMcpServer`). $PERSONAL_WRITE Audited " +
            "(`mcp.server.personal.deleted`)."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        responses {
            empty("Deleted.")
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound("No such personal server of the caller (`not_found`).")
        }
    }
}

/** Who reaches a server, and with which tools. */
private fun ApiCatalog.assignmentOperations() {
    get("/api/v1/mcp/servers/{name}/assignments") {
        operationId = "listMcpAssignments"
        tag(TAG)
        summary = "Who reaches a relayed server."
        description = "Each member, or `all`, with the tools they reach and the secret of their own credential. " +
            "Scope `mcp:read`."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        responses {
            ok<McpAssignmentsPage>("The assignments.")
            notFound(SERVER_NF)
        }
    }
    put("/api/v1/mcp/servers/{name}/assignments/{member}") {
        operationId = "assignMcpServer"
        tag(TAG)
        summary = "Give a member a relayed server."
        description = "All its tools, or an allowlist. For a per-member server, `credentialSecret` names the " +
            "member's own personal secret holding their credential; it goes to one member, never to `all`. " +
            "$PERSONAL_NOTE Audited (`mcp.assignment.changed`). Scope `mcp:admin`, from the console."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
            pathId("member", MEMBER)
        }
        body<McpAssignmentRequest>("The allowed tools (absent for every tool) and the member's credential secret.")
        responses {
            ok<McpAssignmentResponse>("The assignment as saved.")
            badRequest(
                "An invalid tool list, or a credential assigned to all members or naming an unknown secret " +
                    "(`invalid_request`).",
            )
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound(SERVER_NF)
            conflict("The server is a member's personal one (`mcp_server_personal`).")
        }
    }
    delete("/api/v1/mcp/servers/{name}/assignments/{member}") {
        operationId = "unassignMcpServer"
        tag(TAG)
        summary = "Take a relayed server back from a member."
        description = "Audited (`mcp.assignment.removed`). Scope `mcp:admin`, from the console."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
            pathId("member", MEMBER)
        }
        responses {
            empty("Taken back.")
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound(SERVER_NF)
        }
    }
}

/** A server's argument policies. */
private fun ApiCatalog.policyOperations() {
    get("/api/v1/mcp/servers/{name}/policies") {
        operationId = "listMcpArgumentPolicies"
        tag(TAG)
        summary = "The argument policies of a relayed server."
        description = "Scope `mcp:read`."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        responses {
            ok<ArgumentPoliciesResponse>("The policies.")
            notFound(SERVER_NF)
        }
    }
    post("/api/v1/mcp/servers/{name}/policies") {
        operationId = "createMcpArgumentPolicy"
        tag(TAG)
        summary = "Allow, deny or ask for approval of a tool call by its arguments."
        description = "A policy names a tool, up to 16 conditions on its arguments and an effect, with a message " +
            "of 1 to 500 characters. At most 64 policies a server. Audited (`mcp.policy.created`). Scope " +
            "`mcp:admin`, from the console."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
        }
        body<ArgumentPolicyRequest>("The tool, the conditions on its arguments, the effect and its message.")
        responses {
            ok<ArgumentPolicyResponse>("The policy, created.", HttpStatusCode.Created)
            badRequest("An invalid policy, or the server already has 64 (`invalid_request`).")
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound(SERVER_NF)
        }
    }
    delete("/api/v1/mcp/servers/{name}/policies/{policyId}") {
        operationId = "deleteMcpArgumentPolicy"
        tag(TAG)
        summary = "Remove an argument policy."
        description = "Audited (`mcp.policy.deleted`). Scope `mcp:admin`, from the console."
        parameters {
            organizationHeader()
            pathId("name", SERVER_NAME)
            pathId("policyId", "The policy's id.")
        }
        responses {
            empty("Removed.")
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound("No such server or policy in the organization (`not_found`).")
        }
    }
}

/** The settings, the journal of the calls and their usage. */
private fun ApiCatalog.journalOperations() {
    get("/api/v1/mcp/settings") {
        operationId = "getMcpSettings"
        tag(TAG)
        summary = "The relay's settings."
        description = "The prefix of relayed server names as agents see them, and whether members may declare a " +
            "hosted command. Scope `mcp:read`."
        parameters { organizationHeader() }
        responses {
            ok<McpSettingsDto>("The settings.")
        }
    }
    put("/api/v1/mcp/settings") {
        operationId = "putMcpSettings"
        tag(TAG)
        summary = "Change the relay's settings."
        description = "An absent `memberHostedMcp` keeps the stored answer. Audited (`mcp.settings.changed`). " +
            "Scope `mcp:admin`, from the console."
        parameters { organizationHeader() }
        body<McpSettingsDto>("The prefix of the agent's server names, and whether members may host commands.")
        responses {
            ok<McpSettingsDto>("The settings as saved.")
            badRequest("An invalid prefix (`invalid_request`).")
            error(HttpStatusCode.Forbidden, STEP_UP)
        }
    }
    get("/api/v1/mcp/calls") {
        operationId = "listMcpCalls"
        tag(TAG)
        summary = "The calls the relay made."
        description = "Newest first: the outcome of each call, never its arguments or its result. Scope `mcp:read`."
        parameters {
            organizationHeader()
            queryText("server", "Only the calls of this server.")
            queryText("dev", "Only the calls of this member (an id).")
            queryText("before", "An ISO 8601 instant, or the `next` of the previous page.")
            queryNumber("limit", "The page size, 50 by default; brought into 1 to 200.")
        }
        responses {
            ok<McpCallsPage>("A page of calls, and the cursor of the next one when there is one.")
            badRequest("`dev` is not a member id, or `before` is not an instant nor a `next` (`invalid_request`).")
        }
    }
    get("/api/v1/mcp/usage") {
        operationId = "getMcpUsage"
        tag(TAG)
        summary = "The calls per server and tool."
        description = "Over a window of days, with the details by member and server. Scope `mcp:read`, `usage:read` " +
            "or a service key reading the team."
        parameters {
            organizationHeader()
            queryNumber("days", "The window in days, 7 by default; brought into 1 to 30.")
        }
        responses {
            ok<McpUsageResponse>("The calls by server, by `server/tool` and by member and server.")
        }
    }
}

/** The processes the MCP host runs for the organization. */
private fun ApiCatalog.hostedOperations() {
    get("/api/v1/mcp/hosted") {
        operationId = "listMcpHostedProcesses"
        tag(TAG)
        summary = "The hosted stdio processes of the organization."
        description = "TeamCode's dashboard \"Hosted commands\": one item per hosted process (a `shared` server " +
            "once, a `perMember` server once per member it is assigned to), read live from the host's " +
            "`/v1/status`. When the host does not answer, `hostReachable` is false and each state is the one the " +
            "minute sweep last recorded. A `perMember` process names its member by id (`member`) and login " +
            "(`username`, TeamCode's). No secret, argument value or environment is shown. Scope `mcp:read`."
        parameters { organizationHeader() }
        responses {
            ok<McpHostedProcessesDto>("The processes, by server then member.")
            conflict(
                "No MCP host is configured (`conflict`), or more than 10,000 hosted assignments " +
                    "(`mcp_hosted_too_many`).",
            )
        }
    }
    post("/api/v1/mcp/hosted/restart") {
        operationId = "restartMcpHostedProcess"
        tag(TAG)
        summary = "Restart a hosted stdio process."
        description = "TeamCode's \"Restart\": the host stops the process and lifts its backoff (`/v1/stop`); the " +
            "next operation starts it again. Journaled before the stop as `mcp.stdio.restarted`. Scope " +
            "`mcp:admin`, from the console."
        parameters { organizationHeader() }
        body<McpHostedRestartDto>("The stdio server's name, and the member's id for a per-member server.")
        responses {
            empty("The process is stopped.")
            error(HttpStatusCode.Forbidden, STEP_UP)
            notFound("The server is not a stdio server assigned to that member, or to anyone if shared (`not_found`).")
            conflict("No MCP host is configured (`conflict`).")
            error(HttpStatusCode.ServiceUnavailable, "The MCP host does not answer (`mcp_host_unavailable`).")
        }
    }
}

/** The Streamable HTTP endpoint of a relayed server, stateless: only `POST` is served. */
private fun ApiCatalog.endpointOperations() {
    post("/api/v1/mcp/{server}") {
        operationId = "relayMcp"
        tag(TAG)
        summary = "The MCP Streamable HTTP endpoint of a relayed server."
        description = "A JSON-RPC message from an agent's session or a member's CLI token (a browser request, which " +
            "sends an `Origin`, is refused), relayed to the server (`ostrom` and `ostrom-workflow` are built in) " +
            "with the member's tools and policies applied. The body is one JSON-RPC message of at most 1 MiB, " +
            "written as is: no schema. A listing, a tool call or an initialization answers with the server's " +
            "JSON-RPC answer, an error included, in the status the relay chose; a notification or a response " +
            "sent to the server answers 202 without a body. The relay is stateless: no session, no " +
            "server-initiated stream. `MCP-Protocol-Version`, when sent, is a version the relay speaks."
        parameters {
            organizationHeader()
            pathId("server", "The relayed server's name, or `ostrom`, or `ostrom-workflow`.")
        }
        responses {
            anyJson(HttpStatusCode.OK, "The JSON-RPC answer of the server, as it sent it.")
            empty("Accepted, without a body: a notification or a response sent to the server.", HttpStatusCode.Accepted)
            anyJson(
                HttpStatusCode.BadRequest,
                "The message is not JSON, or the protocol version is unsupported (`invalid_request`); a batch or a " +
                    "message that is not JSON-RPC 2.0 is a JSON-RPC error (`code` -32600).",
            )
            error(HttpStatusCode.Forbidden, "A browser request, which sends an `Origin` (`forbidden`).")
            anyJson(
                HttpStatusCode.NotFound,
                "No such server, or one the caller does not reach: `not_found`, or a JSON-RPC error (`code` -32600) " +
                    "once the message is read.",
            )
        }
    }
    get("/api/v1/mcp/{server}") {
        operationId = "relayMcpStream"
        tag(TAG)
        summary = "Not served: the relay opens no stream."
        description = "The endpoint is stateless, with no server-initiated stream: `GET` answers 405 whatever the " +
            "server."
        parameters {
            organizationHeader()
            pathId("server", "The relayed server's name.")
        }
        responses { error(HttpStatusCode.MethodNotAllowed, "No stream is offered.") }
    }
    delete("/api/v1/mcp/{server}") {
        operationId = "relayMcpSession"
        tag(TAG)
        summary = "Not served: the relay holds no session."
        description = "The endpoint is stateless, with no session to end: `DELETE` answers 405 whatever the server."
        parameters {
            organizationHeader()
            pathId("server", "The relayed server's name.")
        }
        responses { error(HttpStatusCode.MethodNotAllowed, "No session is held.") }
    }
}
