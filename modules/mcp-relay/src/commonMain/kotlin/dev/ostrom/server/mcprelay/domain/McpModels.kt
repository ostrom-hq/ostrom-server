// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How the relay reaches an MCP server. */
enum class McpTransport {
    /** A Streamable HTTP endpoint (HTTPS), called by the server with the injected credential. */
    HTTP,

    /** A stdio command (npm or PyPI package) run on the server side by the MCP host, never on a workstation. */
    STDIO,
}

/** Who shares a server's credential and, for stdio, its process. */
enum class McpSharing {
    /** One credential and one process for every assigned member. */
    SHARED,

    /** Each member brings their own credential (assignment), and gets their own process. */
    PER_MEMBER,
}

/** The package registries the MCP host launches from. */
enum class StdioLauncher(val wire: String) {
    NPM("npm"),
    PYPI("pypi"),
}

/**
 * A stdio server: a pinned package run by the MCP host.
 *
 * @property launcher The registry.
 * @property packageName The package at one exact version (`name@1.2.3`, `name==1.2.3`).
 * @property args The arguments after the package; `${NAME}` is replaced by the secret variable `NAME`.
 * @property env The visible environment variables.
 * @property secretEnv The secret environment variables: name to the id of an org secret.
 */
data class StdioSpec(
    val launcher: StdioLauncher,
    val packageName: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val secretEnv: Map<String, Uuid> = emptyMap(),
)

/**
 * An MCP server of an org.
 *
 * @property id The id.
 * @property orgId The org.
 * @property name The endpoint name, unique in the org (`[a-z0-9][a-z0-9._-]{0,63}`): the agent reaches it at
 *   `/v1/mcp/<name>` and sees it as `<prefix>-<name>`.
 * @property transport How it is reached.
 * @property url The HTTPS endpoint of an [McpTransport.HTTP] server.
 * @property credentialSecretId The shared credential of an HTTP server (an org secret), sent upstream.
 * @property credentialHeader The header carrying the credential; null for `Authorization: Bearer`.
 * @property stdio The command of an [McpTransport.STDIO] server.
 * @property sharing Whether members share the credential and process, or bring their own.
 * @property version Bumped at each change, so a changed definition always reaches a new stdio process.
 * @property createdAt Creation time; also the npm/PyPI cut-off date of a stdio package.
 */
data class McpServer(
    val id: Uuid,
    val orgId: Uuid,
    val name: String,
    val transport: McpTransport,
    val url: String?,
    val credentialSecretId: Uuid?,
    val credentialHeader: String?,
    val stdio: StdioSpec?,
    val sharing: McpSharing,
    val version: Int,
    val createdAt: Instant,
)

/**
 * Which tools of a server a member may list and call: all of them, or an allowlist. Hiding a tool is not the
 * boundary: every call is checked again.
 *
 * @property tools The allowed tool names, or null for every tool.
 */
data class ToolPolicy(val tools: Set<String>?) {
    /**
     * Whether a tool is allowed.
     *
     * @param tool The upstream tool name.
     */
    fun allows(tool: String): Boolean = tools == null || tool in tools

    /** The policy that allows everything. */
    companion object {
        /** Every tool. */
        val ALL: ToolPolicy = ToolPolicy(null)
    }
}

/**
 * A server assigned to one member, or to every member of the org ([memberId] null). A member's own row wins over
 * the org-wide one.
 *
 * @property orgId The org.
 * @property serverId The server.
 * @property memberId The member, or null for the whole org.
 * @property policy The tools allowed.
 * @property credentialSecretId The member's own credential for a [McpSharing.PER_MEMBER] server (a secret).
 * @property assignedAt When it was assigned; part of a per-member stdio process key, so a rotation starts a new one.
 */
data class McpAssignment(
    val orgId: Uuid,
    val serverId: Uuid,
    val memberId: Uuid?,
    val policy: ToolPolicy,
    val credentialSecretId: Uuid?,
    val assignedAt: Instant,
)

/**
 * A server as one member reaches it: the server and the assignment that applies.
 *
 * @property server The server.
 * @property assignment The member's row, or the org-wide one.
 */
data class EffectiveServer(val server: McpServer, val assignment: McpAssignment)

/** What a relayed operation was. */
enum class McpOperation { LIST, CALL }

/** How a relayed operation ended. Never arguments, results or credentials: outcomes only. */
enum class McpOutcome {
    OK,
    TOOL_ERROR,
    DENIED,
    APPROVAL_REQUIRED,
    UPSTREAM_ERROR,
    TIMEOUT,
    TOO_LARGE,
    RATE_LIMITED,
    CANCELLED,
}

/**
 * One journal row.
 *
 * @property id The row id.
 * @property orgId The org.
 * @property memberId The member.
 * @property serverId The server (kept when the server is deleted).
 * @property serverName The server name at the time.
 * @property operation List or call.
 * @property tool The tool of a call.
 * @property outcome How it ended.
 * @property startedAt When it started.
 * @property durationMillis How long it took.
 * @property responseBytes The size of the upstream answer.
 * @property policyId The argument policy that refused it, if any.
 * @property launchId The launch whose agent token made it, or null for the member's own credential.
 */
data class McpCall(
    val id: Uuid,
    val orgId: Uuid,
    val memberId: Uuid,
    val serverId: Uuid,
    val serverName: String,
    val operation: McpOperation,
    val tool: String?,
    val outcome: McpOutcome,
    val startedAt: Instant,
    val durationMillis: Long,
    val responseBytes: Long,
    val policyId: Uuid? = null,
    val launchId: String? = null,
)

/**
 * Where a page of the journal starts: the calls strictly older than this one, newest first.
 *
 * @property startedAt When the last call of the previous page started.
 * @property id That call's id, which orders calls started in the same microsecond; null for every call before
 *   [startedAt].
 */
data class McpCallCursor(val startedAt: Instant, val id: Uuid? = null)

/**
 * Usage of one member on one server over a period.
 *
 * @property memberId The member.
 * @property serverName The server.
 * @property calls Tool calls.
 * @property lists Tool listings.
 * @property errors Calls that failed upstream, timed out or were too large.
 * @property denied Calls refused by a tool or argument policy.
 * @property bytes Upstream bytes.
 * @property durationMillis Total time.
 * @property tool The tool called, null for listings.
 */
data class McpUsage(
    val memberId: Uuid,
    val serverName: String,
    val calls: Long,
    val lists: Long,
    val errors: Long,
    val denied: Long,
    val bytes: Long,
    val durationMillis: Long,
    val tool: String? = null,
)

/**
 * The org's relay settings.
 *
 * @property agentPrefix The prefix of agent-side server names: the agent sees `<prefix>-<server>`, so tools are
 *   `mcp__<prefix>-<server>__<tool>`. Stable names keep skills and permissions working; an org imported from
 *   TeamCode keeps `teamcode`.
 */
data class McpSettings(val agentPrefix: String) {
    /** The defaults. */
    companion object {
        /** The default prefix. */
        const val DEFAULT_PREFIX: String = "ostrom"
    }
}
