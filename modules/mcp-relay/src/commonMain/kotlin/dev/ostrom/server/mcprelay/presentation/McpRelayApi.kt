package dev.ostrom.server.mcprelay.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/v1/orgs/{orgId}/mcp`: the org's MCP relay, for admins. To move to ostrom-core with the API contract. */
@Serializable
@Resource("/v1/orgs/{orgId}/mcp")
class OrgMcpApi(val orgId: String) {
    /** `/servers`: the org's servers. */
    @Serializable
    @Resource("servers")
    class Servers(val parent: OrgMcpApi) {
        /** `/servers/{id}`: one server. */
        @Serializable
        @Resource("{id}")
        class Id(val parent: Servers, val id: String) {
            /** `/servers/{id}/assignments`: who reaches it, with which tools. */
            @Serializable
            @Resource("assignments")
            class Assignments(val parent: Id) {
                /** `/assignments/{member}`: one member's, or `all` for the org-wide one. */
                @Serializable
                @Resource("{member}")
                class Member(val parent: Assignments, val member: String)
            }

            /** `/servers/{id}/policies`: argument policies. */
            @Serializable
            @Resource("policies")
            class Policies(val parent: Id) {
                /** `/policies/{policyId}`: one policy. */
                @Serializable
                @Resource("{policyId}")
                class PolicyId(val parent: Policies, val policyId: String)
            }
        }
    }

    /** `/settings`: the agent prefix. */
    @Serializable
    @Resource("settings")
    class Settings(val parent: OrgMcpApi)

    /**
     * `/calls`: the journal, newest first.
     *
     * @property before Keyset cursor (ISO 8601).
     * @property limit Page size, 1 to 200.
     */
    @Serializable
    @Resource("calls")
    class Calls(val parent: OrgMcpApi, val before: String? = null, val limit: Int = 50)

    /**
     * `/usage`: totals per member and server.
     *
     * @property days Period, 1 to 30 days.
     */
    @Serializable
    @Resource("usage")
    class Usage(val parent: OrgMcpApi, val days: Int = 7)
}

/** `GET /v1/mcp/servers`: the servers the agent's member reaches (what `ostrom claude` connects). */
@Serializable
@Resource("/v1/mcp/servers")
class MemberMcpServersApi

/** A stdio command. */
@Serializable
data class StdioSpecDto(
    val launcher: String,
    val packageName: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val secretEnv: Map<String, String> = emptyMap(),
)

/** Declares or changes a server. */
@Serializable
data class McpServerRequest(
    val name: String,
    val transport: String,
    val url: String? = null,
    val credentialSecretId: String? = null,
    val credentialHeader: String? = null,
    val stdio: StdioSpecDto? = null,
    val sharing: String = "SHARED",
)

/** A server as admins see it: secret ids, never values. */
@Serializable
data class McpServerResponse(
    val id: String,
    val name: String,
    val transport: String,
    val url: String? = null,
    val credentialSecretId: String? = null,
    val credentialHeader: String? = null,
    val stdio: StdioSpecDto? = null,
    val sharing: String,
    val version: Int,
    val createdAt: String,
)

/** The org's servers. */
@Serializable
data class McpServersResponse(val servers: List<McpServerResponse>)

/**
 * Assigns a server.
 *
 * @property tools The allowlist, or null for every tool.
 * @property credentialSecretId The member's own credential (per-member servers).
 */
@Serializable
data class McpAssignmentRequest(val tools: List<String>? = null, val credentialSecretId: String? = null)

/** An assignment. */
@Serializable
data class McpAssignmentResponse(
    val serverId: String,
    val memberId: String? = null,
    val tools: List<String>? = null,
    val credentialSecretId: String? = null,
    val assignedAt: String,
)

/** A server's assignments. */
@Serializable
data class McpAssignmentsResponse(val assignments: List<McpAssignmentResponse>)

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

/** A journal row: outcomes only. */
@Serializable
data class McpCallResponse(
    val id: String,
    val memberId: String,
    val server: String,
    val operation: String,
    val tool: String? = null,
    val outcome: String,
    val startedAt: String,
    val durationMs: Long,
    val responseBytes: Long,
    val policyId: String? = null,
)

/** A journal page. */
@Serializable
data class McpCallsResponse(val calls: List<McpCallResponse>, val next: String? = null)

/** Usage of a member on a server. */
@Serializable
data class McpUsageResponse(
    val memberId: String,
    val server: String,
    val calls: Long,
    val lists: Long,
    val errors: Long,
    val denied: Long,
    val bytes: Long,
    val durationMs: Long,
)

/** Usage totals. */
@Serializable
data class McpUsagesResponse(val usage: List<McpUsageResponse>)

/**
 * A server as the agent reaches it.
 *
 * @property name The endpoint name.
 * @property agentName The name to give the agent (`<prefix>-<name>`): tools are `mcp__<agentName>__<tool>`.
 * @property path The relay path to POST to.
 * @property transport `http` or `stdio` (informative: the agent always speaks HTTP to the relay).
 * @property tools The allowlist, or null for every tool.
 */
@Serializable
data class MemberMcpServerResponse(
    val name: String,
    val agentName: String,
    val path: String,
    val transport: String,
    val tools: List<String>? = null,
)

/** The member's servers. */
@Serializable
data class MemberMcpServersResponse(val servers: List<MemberMcpServerResponse>)
