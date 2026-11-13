// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.presentation

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import run.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCase
import run.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import run.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCase
import run.ostrom.server.mcprelay.domain.DeleteMcpServerUseCase
import run.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import run.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import run.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import run.ostrom.server.mcprelay.domain.ListMcpCallsUseCase
import run.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import run.ostrom.server.mcprelay.domain.ListMemberServersUseCase
import run.ostrom.server.mcprelay.domain.McpUsageUseCase
import run.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import run.ostrom.server.mcprelay.domain.RelayMcpMessageUseCase
import run.ostrom.server.mcprelay.domain.UnassignMcpServerUseCase
import run.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.SecretDirectoryUseCase
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

/**
 * `/api/v1/mcp`: the MCP relay of the caller's org (from the token), as in ostrom-core's `openapi.yaml`.
 * Assignments, argument policies and settings are additions to the contract (proposed in ostrom-core).
 */
@Serializable
@Resource("/api/v1/mcp")
class McpApi {
    /**
     * `/api/v1/mcp/servers`: every server of the org (admins), or with `mine=true` the servers the caller reaches
     * (what `ostrom claude` connects; members always get these).
     *
     * @property mine Only the caller's servers.
     */
    @Serializable
    @Resource("servers")
    class Servers(val parent: McpApi = McpApi(), val mine: Boolean = false) {
        /**
         * `/api/v1/mcp/servers/{name}`: one server.
         *
         * @property name Its name.
         */
        @Serializable
        @Resource("{name}")
        class Name(val parent: Servers = Servers(), val name: String) {
            /** `…/assignments`: who reaches it, with which tools. */
            @Serializable
            @Resource("assignments")
            class Assignments(val parent: Name) {
                /**
                 * `…/assignments/{member}`: one member's assignment, or `all` for the whole org.
                 *
                 * @property member A member id, or `all`.
                 */
                @Serializable
                @Resource("{member}")
                class Member(val parent: Assignments, val member: String)
            }

            /** `…/policies`: argument policies. */
            @Serializable
            @Resource("policies")
            class Policies(val parent: Name) {
                /**
                 * One policy.
                 *
                 * @property policyId Its id.
                 */
                @Serializable
                @Resource("{policyId}")
                class PolicyId(val parent: Policies, val policyId: String)
            }
        }
    }

    /** `/api/v1/mcp/settings`: the tool name prefix agents see. */
    @Serializable
    @Resource("settings")
    class Settings(val parent: McpApi = McpApi())

    /**
     * `/api/v1/mcp/calls`: the journal, newest first.
     *
     * @property server Only this server.
     * @property dev Only this member (admins).
     * @property before Keyset cursor (ISO 8601).
     * @property limit Page size (1 to 200).
     */
    @Serializable
    @Resource("calls")
    class Calls(
        val parent: McpApi = McpApi(),
        val server: String? = null,
        val dev: String? = null,
        val before: String? = null,
        val limit: Int = 50,
    )

    /**
     * `/api/v1/mcp/usage`: calls by server and by tool.
     *
     * @property days The window (1 to 30).
     */
    @Serializable
    @Resource("usage")
    class Usage(val parent: McpApi = McpApi(), val days: Int = 7)
}

/**
 * A stdio command (an addition to the contract's `McpServer`).
 *
 * @property launcher `npx`, `uvx`, ...
 * @property packageName The package.
 * @property args Arguments; `${NAME}` names a secret variable.
 * @property env Visible variables.
 * @property secretEnv Secret variables: variable name to org secret name.
 */
@Serializable
data class StdioSpecDto(
    val launcher: String,
    val packageName: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val secretEnv: Map<String, String> = emptyMap(),
)

/**
 * A server (contract `McpServer`, with additions). Credentials are named, never sent.
 *
 * @property name Unique in the org.
 * @property url The HTTP endpoint; for a stdio server `stdio:<launcher>:<package>`; in a member's list, the relay path.
 * @property transport `http` or `stdio`.
 * @property secretNames The org secrets it uses: for HTTP, the credential.
 * @property tools The tools the caller reaches, in a member's list; absent for every tool.
 * @property enabled Always true.
 * @property scope `shared` (one credential for the org) or `per-member` (each member's own).
 * @property agentName The name the agent sees (`<prefix>-<name>`), in a member's list.
 * @property path The relay endpoint, in a member's list.
 * @property credentialHeader The header carrying the credential, when not `Authorization: Bearer`.
 * @property stdio The command of a stdio server.
 * @property version Bumped at each change.
 * @property createdAt Creation time.
 */
@Serializable
data class McpServerDto(
    val name: String,
    val url: String = "",
    val transport: String,
    val secretNames: List<String> = emptyList(),
    val tools: List<String>? = null,
    val enabled: Boolean = true,
    val scope: String = "shared",
    val agentName: String? = null,
    val path: String? = null,
    val credentialHeader: String? = null,
    val stdio: StdioSpecDto? = null,
    val version: Int? = null,
    val createdAt: String? = null,
)

/**
 * A page of servers.
 *
 * @property items The servers, by name.
 * @property next Never set: an org has at most a hundred servers.
 */
@Serializable
data class McpServersPage(val items: List<McpServerDto>, val next: String? = null)

/**
 * Assigns a server.
 *
 * @property tools The allowed tools, or absent for every tool.
 * @property credentialSecret The member's personal secret holding its own credential (per-member servers).
 */
@Serializable
data class McpAssignmentRequest(val tools: List<String>? = null, val credentialSecret: String? = null)

/**
 * An assignment.
 *
 * @property server The server.
 * @property member The member, absent for the whole org.
 * @property tools The allowed tools, absent for every tool.
 * @property credentialSecret The member's credential secret.
 * @property assignedAt When.
 */
@Serializable
data class McpAssignmentResponse(
    val server: String,
    val member: String? = null,
    val tools: List<String>? = null,
    val credentialSecret: String? = null,
    val assignedAt: String,
)

/**
 * A server's assignments.
 *
 * @property items The assignments.
 */
@Serializable
data class McpAssignmentsPage(val items: List<McpAssignmentResponse>)

/** A policy condition. */
@Serializable
data class ArgumentConditionDto(val path: String, val operator: String, val value: String? = null)

/** Creates an argument policy. */
@Serializable
data class ArgumentPolicyRequest(
    val tool: String,
    val conditions: List<ArgumentConditionDto> = emptyList(),
    val effect: String,
    val message: String,
)

/** An argument policy. */
@Serializable
data class ArgumentPolicyResponse(
    val id: String,
    val tool: String,
    val conditions: List<ArgumentConditionDto>,
    val effect: String,
    val message: String,
    val createdAt: String,
)

/** A server's policies. */
@Serializable
data class ArgumentPoliciesResponse(val policies: List<ArgumentPolicyResponse>)

/** The relay settings. */
@Serializable
data class McpSettingsDto(val agentPrefix: String)

/**
 * A journal row (contract `McpCall`, with additions): outcomes only, never arguments or results.
 *
 * @property id The call.
 * @property at When it started.
 * @property server The server name at the time.
 * @property tool The tool, or `tools/list` for a listing.
 * @property dev The member.
 * @property durationMs How long it took.
 * @property ok Whether it succeeded.
 * @property outcome `OK`, `DENIED`, `UPSTREAM_ERROR`, ...
 * @property responseBytes The upstream answer size.
 * @property policyId The argument policy that refused it.
 * @property launchId The launch whose agent token made it; absent for the member's own credential.
 */
@Serializable
data class McpCallDto(
    val id: String,
    val at: String,
    val server: String,
    val tool: String,
    val dev: String,
    val durationMs: Long,
    val ok: Boolean,
    val outcome: String,
    val responseBytes: Long,
    val policyId: String? = null,
    val launchId: String? = null,
)

/**
 * A journal page.
 *
 * @property items The calls, newest first.
 * @property next The cursor of the next page.
 */
@Serializable
data class McpCallsPage(val items: List<McpCallDto>, val next: String? = null)

/**
 * Usage of a member on a server (an addition to the contract's usage).
 *
 * @property dev The member.
 * @property server The server.
 */
@Serializable
data class McpUsageRow(
    val dev: String,
    val server: String,
    val calls: Long,
    val lists: Long,
    val errors: Long,
    val denied: Long,
    val bytes: Long,
    val durationMs: Long,
)

/**
 * Usage over the window (contract `getMcpUsage`).
 *
 * @property byServer Tool calls by server.
 * @property byTool Tool calls by `server/tool`.
 * @property rows Details by member and server.
 */
@Serializable
data class McpUsageResponse(val byServer: Map<String, Long>, val byTool: Map<String, Long>, val rows: List<McpUsageRow>)

/** What the relay routes need; [audit] writes each change of the MCP governance to the organization's audit log. */
data class McpRelayRoutesDependencies(
    val callers: CallerResolver,
    val createServer: CreateMcpServerUseCase,
    val updateServer: UpdateMcpServerUseCase,
    val deleteServer: DeleteMcpServerUseCase,
    val listServers: ListMcpServersUseCase,
    val assign: AssignMcpServerUseCase,
    val unassign: UnassignMcpServerUseCase,
    val listAssignments: ListMcpAssignmentsUseCase,
    val createPolicy: CreateArgumentPolicyUseCase,
    val deletePolicy: DeleteArgumentPolicyUseCase,
    val listPolicies: ListArgumentPoliciesUseCase,
    val getSettings: GetMcpSettingsUseCase,
    val putSettings: PutMcpSettingsUseCase,
    val listCalls: ListMcpCallsUseCase,
    val usage: McpUsageUseCase,
    val listMemberServers: ListMemberServersUseCase,
    val relay: RelayMcpMessageUseCase,
    val directory: SecretDirectoryUseCase,
    val audit: OrgAuditTrail,
) {
    /**
     * Writes a change of an MCP server's governance (the server, its assignments, its policies) to the organization's
     * audit log, once made.
     *
     * @param call The request, for who made it and with which credential.
     * @param caller The member.
     * @param event What happened (`mcp.server.created`).
     * @param server The server's name.
     * @param details Extra facts, never a secret.
     */
    suspend fun recordChange(
        call: ApplicationCall,
        caller: Caller,
        event: String,
        server: String,
        details: Map<String, String>,
    ) {
        audit.record(OrgAuditRecord(caller.orgId, call.requirePrincipal(), event, "mcp_server", server, details))
    }
}
