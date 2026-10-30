package dev.ostrom.server.mcprelay.domain

import dev.ostrom.server.secrets.domain.SecretValue
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * Everything needed to reach one server for one member, credentials decrypted for this request only.
 *
 * @property server The server.
 * @property credential The credential sent to an HTTP server (the member's own for a per-member server).
 * @property secretEnv The decrypted secret variables of a stdio server, by name.
 * @property processKey The stdio process this member uses: shared per server version, or per member.
 */
data class UpstreamTarget(
    val server: McpServer,
    val credential: SecretValue?,
    val secretEnv: Map<String, SecretValue>,
    val processKey: String,
) {
    /** Every secret value of the target, scrubbed from whatever comes back. */
    val secretValues: List<String> get() = listOfNotNull(credential?.reveal()) + secretEnv.values.map { it.reveal() }

    override fun toString(): String = "UpstreamTarget(server=${server.name}, processKey=$processKey)"
}

/** The result of an upstream operation: a value, or a failure the relay turns into a tool error. */
sealed interface UpstreamResult<out T> {
    /**
     * Success.
     *
     * @property value The value.
     * @property bytes The size of the upstream answer.
     */
    data class Ok<T>(val value: T, val bytes: Long) : UpstreamResult<T>

    /**
     * Failure, with a message the relay wrote (or an upstream JSON-RPC error, scrubbed by the caller).
     *
     * @property outcome The journal outcome.
     * @property message For the agent.
     */
    data class Failure(val outcome: McpOutcome, val message: String) : UpstreamResult<Nothing>
}

/** Talks to an upstream MCP server. Implementations never log arguments, results or credentials. */
interface McpUpstream {
    /**
     * Lists the server's tools.
     *
     * @param target The server and its credentials.
     * @param deadline The time allowed.
     *
     * @return The tool definitions, as the server sent them.
     */
    suspend fun listTools(target: UpstreamTarget, deadline: Duration): UpstreamResult<List<JsonObject>>

    /**
     * Calls a tool. Never replayed: a tool call is not idempotent.
     *
     * @param target The server and its credentials.
     * @param tool The tool name.
     * @param arguments Its arguments.
     * @param deadline The time allowed.
     *
     * @return The `CallToolResult`.
     */
    suspend fun callTool(target: UpstreamTarget, tool: String, arguments: JsonObject, deadline: Duration): UpstreamResult<JsonObject>
}

/**
 * Opens a gate when an argument policy asks for approval. Bound by the gates module; without it, a call that needs
 * approval is refused with the policy's message.
 */
interface ApprovalRequester {
    /**
     * Asks for approval of a call.
     *
     * @param caller The member.
     * @param server The server.
     * @param tool The tool.
     * @param arguments The arguments the approver sees.
     * @param policy The policy that asks for it.
     *
     * @return Where the approver answers (a console link), or null when no gate could be opened.
     */
    suspend fun request(
        caller: dev.ostrom.server.secrets.domain.Caller,
        server: McpServer,
        tool: String,
        arguments: JsonObject,
        policy: ArgumentPolicy,
    ): String?
}

/** No gates module: nothing is opened. */
class NoApprovalRequester : ApprovalRequester {
    override suspend fun request(
        caller: dev.ostrom.server.secrets.domain.Caller,
        server: McpServer,
        tool: String,
        arguments: JsonObject,
        policy: ArgumentPolicy,
    ): String? = null
}
