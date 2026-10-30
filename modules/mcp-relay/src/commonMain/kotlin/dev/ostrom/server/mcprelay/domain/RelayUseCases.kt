// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.SecretScrubber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A server as the agent sees it.
 *
 * @property name The endpoint name (`/v1/mcp/<name>`).
 * @property agentName The name to give the agent: `<prefix>-<name>`.
 * @property transport How the relay reaches it.
 * @property tools The allowlist, or null for every tool.
 */
data class MemberServer(val name: String, val agentName: String, val transport: McpTransport, val tools: Set<String>?)

/** The servers the calling member reaches: what `ostrom claude` connects. */
interface ListMemberServersUseCase {
    /** Lists them, by name. */
    suspend operator fun invoke(caller: Caller): List<MemberServer>
}

/** [ListMemberServersUseCase] on the repository. */
class ListMemberServersUseCaseImpl(private val repository: McpRelayRepository) : ListMemberServersUseCase {
    override suspend fun invoke(caller: Caller): List<MemberServer> {
        val prefix = repository.settings(caller.orgId).agentPrefix
        val servers = repository.effectiveServers(caller.orgId, caller.memberId, McpRules.MAX_SERVERS)
        val agentNames = servers.groupBy { McpRules.agentName(prefix, it.server.name) }
        // Two servers that would get the same agent name (`a.b` and `a_b`) are both left out rather than one hidden.
        return servers.filter { agentNames.getValue(McpRules.agentName(prefix, it.server.name)).size == 1 }.map {
            MemberServer(
                it.server.name,
                McpRules.agentName(prefix, it.server.name),
                it.server.transport,
                it.assignment.policy.tools,
            )
        }
    }
}

/**
 * The answer to one MCP message.
 *
 * @property status The HTTP status: 200 with a body, 202 for a notification, 400 or 404 with a JSON-RPC error.
 * @property body The JSON-RPC response, or null.
 */
data class RelayAnswer(val status: Int, val body: JsonElement?)

/**
 * The relay's deadlines.
 *
 * @property list A `tools/list`.
 * @property call A `tools/call`.
 */
data class RelayDeadlines(val list: Duration = 15.seconds, val call: Duration = 60.seconds)

/**
 * How much the relay lets through.
 *
 * @property limiter Concurrency and rate limits.
 * @property deadlines The deadlines.
 */
data class RelayLimits(val limiter: McpCallLimiter, val deadlines: RelayDeadlines = RelayDeadlines())

/** One operation being relayed: who, on which server, what. */
private data class RelayedOperation(
    val caller: Caller,
    val effective: EffectiveServer,
    val operation: McpOperation,
    val tool: String?,
)

/**
 * Relays one Streamable HTTP MCP message (stateless: one JSON-RPC message, one answer, no session) to the member's
 * server, checking on every message that the server is assigned, the tool allowed and the arguments accepted.
 */
interface RelayMcpMessageUseCase {
    /**
     * Relays it.
     *
     * @param caller The member, from the agent's credential.
     * @param serverName The endpoint name.
     * @param message The JSON-RPC message.
     *
     * @return The answer.
     */
    suspend operator fun invoke(caller: Caller, serverName: String, message: JsonElement): RelayAnswer
}

/**
 * [RelayMcpMessageUseCase].
 *
 * @param repository The storage (assignments, policies, journal).
 * @param revealSecrets Decrypts the credentials for each upstream request.
 * @param upstream Reaches the server.
 * @param approvals Opens a gate when a policy asks for approval.
 * @param limits Concurrency, rate limits and deadlines.
 * @param clock The time.
 */
class RelayMcpMessageUseCaseImpl(
    private val repository: McpRelayRepository,
    private val revealSecrets: RevealSecretsUseCase,
    private val upstream: McpUpstream,
    private val approvals: ApprovalRequester,
    private val limits: RelayLimits,
    private val clock: Clock,
) : RelayMcpMessageUseCase {
    private val limiter = limits.limiter
    private val deadlines = limits.deadlines

    override suspend fun invoke(caller: Caller, serverName: String, message: JsonElement): RelayAnswer {
        val request = message as? JsonObject
        val id = request?.get("id") ?: JsonNull
        return when {
            request == null -> error(
                BAD_REQUEST,
                JsonNull,
                INVALID_REQUEST,
                "One JSON-RPC message per request; batches are not supported.",
            )
            (request["jsonrpc"] as? JsonPrimitive)?.content != "2.0" ->
                error(BAD_REQUEST, id, INVALID_REQUEST, "Not a JSON-RPC 2.0 message.")
            else -> dispatch(caller, serverName, request, id)
        }
    }

    private suspend fun dispatch(
        caller: Caller,
        serverName: String,
        request: JsonObject,
        id: JsonElement,
    ): RelayAnswer {
        val effective = repository.effectiveServer(caller.orgId, caller.memberId, serverName)
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when {
            effective == null -> error(NOT_FOUND, id, INVALID_REQUEST, "Unknown MCP server.")
            // A notification (no id) or a client response to the server: nothing to answer.
            method == null || request["id"] == null -> RelayAnswer(ACCEPTED, null)
            method == "initialize" -> result(id, initialize(serverName, params))
            method == "ping" -> result(id, JsonObject(emptyMap()))
            method == "tools/list" -> result(id, listTools(caller, effective))
            method == "tools/call" -> callTool(caller, effective, id, params)
            else -> error(HTTP_OK, id, METHOD_NOT_FOUND, "Method not found.")
        }
    }

    private fun initialize(serverName: String, params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.content
        return buildJsonObject {
            put("protocolVersion", asked?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "ostrom-$serverName")
                put("version", "1")
            }
        }
    }

    private suspend fun listTools(caller: Caller, effective: EffectiveServer): JsonObject {
        val list = RelayedOperation(caller, effective, McpOperation.LIST, null)
        val outcome = operate(list, { tools, secrets -> tools.map { it.scrubbed(secrets) as JsonObject } }) { target ->
            upstream.listTools(target, deadlines.list)
        }
        val tools = (outcome as? UpstreamResult.Ok)?.value.orEmpty()
            .filter { tool ->
                val name = (tool["name"] as? JsonPrimitive)?.content
                name != null && McpRules.isToolName(name) && effective.assignment.policy.allows(name)
            }
            .take(McpRules.MAX_TOOLS)
        return buildJsonObject { put("tools", JsonArray(tools)) }
    }

    private suspend fun callTool(
        caller: Caller,
        effective: EffectiveServer,
        id: JsonElement,
        params: JsonObject,
    ): RelayAnswer {
        val tool = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        if (tool == null || !McpRules.isToolName(tool) || !effective.assignment.policy.allows(tool)) {
            journal(
                RelayedOperation(caller, effective, McpOperation.CALL, tool?.take(MAX_TOOL_NAME)),
                McpOutcome.DENIED,
            )
            return error(HTTP_OK, id, INVALID_PARAMS, "Unknown tool.")
        }
        val call = RelayedOperation(caller, effective, McpOperation.CALL, tool)
        val policy = ArgumentPolicy.decide(repository.listPolicies(caller.orgId, effective.server.id), tool, arguments)
        return result(id, if (policy == null) relayCall(call, arguments) else refuse(call, arguments, policy))
    }

    private suspend fun refuse(call: RelayedOperation, arguments: JsonObject, policy: ArgumentPolicy): JsonObject {
        val text = when (policy.effect) {
            PolicyEffect.DENY -> "Refused by the organization's policy: ${policy.message}"
            PolicyEffect.REQUIRE_APPROVAL -> {
                val link = approvals.request(call.caller, call.effective.server, call.tool.orEmpty(), arguments, policy)
                "This call needs a human approval: ${policy.message}" +
                    (link?.let { " Approve it here, then retry: $it" } ?: "")
            }
        }
        val outcome = if (policy.effect == PolicyEffect.DENY) McpOutcome.DENIED else McpOutcome.APPROVAL_REQUIRED
        journal(call, outcome, policyId = policy.id)
        return toolError(text)
    }

    private suspend fun relayCall(call: RelayedOperation, arguments: JsonObject): JsonObject {
        val outcome = operate(call, { value, secrets -> value.scrubbed(secrets) as JsonObject }) { target ->
            upstream.callTool(target, call.tool.orEmpty(), arguments, deadlines.call)
        }
        return when (outcome) {
            is UpstreamResult.Ok -> outcome.value
            is UpstreamResult.Failure -> toolError(outcome.message)
        }
    }

    /** Admits, reaches the upstream with decrypted credentials, scrubs, journals; the slot is always released. */
    private suspend fun <T> operate(
        call: RelayedOperation,
        scrub: (T, List<String>) -> T,
        block: suspend (UpstreamTarget) -> UpstreamResult<T>,
    ): UpstreamResult<T> {
        val started = clock.now()
        val (caller, effective) = call
        val server = effective.server
        if (!limiter.acquire(caller.orgId, caller.memberId, server.id)) {
            journal(call, McpOutcome.RATE_LIMITED, started)
            return UpstreamResult.Failure(
                McpOutcome.RATE_LIMITED,
                "Too many MCP operations on ${server.name} right now. Retry shortly.",
            )
        }
        var outcome = McpOutcome.CANCELLED
        var bytes = 0L
        val result = try {
            val target = target(caller, effective)
            when (val answer = block(target)) {
                is UpstreamResult.Ok -> {
                    val failed = (answer.value as? JsonObject)?.get("isError") == JsonPrimitive(true)
                    outcome = if (failed) McpOutcome.TOOL_ERROR else McpOutcome.OK
                    bytes = answer.bytes
                    UpstreamResult.Ok(scrub(answer.value, target.secretValues), answer.bytes)
                }
                is UpstreamResult.Failure -> {
                    outcome = answer.outcome
                    UpstreamResult.Failure(answer.outcome, SecretScrubber.scrub(answer.message, target.secretValues))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // An upstream adapter answers failures as results; anything else (a credential that no longer decrypts)
            // is still the agent's tool error, never a 500 that drops the conversation.
            outcome = McpOutcome.UPSTREAM_ERROR
            UpstreamResult.Failure(outcome, "The MCP server could not be reached with its configured credentials.")
        } finally {
            withContext(NonCancellable) {
                limiter.release(caller.orgId, caller.memberId, server.id)
                journal(call, outcome, started, bytes)
            }
        }
        return result
    }

    private suspend fun target(caller: Caller, effective: EffectiveServer): UpstreamTarget {
        val server = effective.server
        val credentialId = effective.assignment.credentialSecretId ?: server.credentialSecretId
        val ids = listOfNotNull(credentialId) + server.stdio?.secretEnv?.values.orEmpty()
        val values = revealSecrets(caller.orgId, ids)
        val shared = "${server.orgId}:${server.id}:${server.version}"
        val processKey = when (server.sharing) {
            McpSharing.SHARED -> shared
            McpSharing.PER_MEMBER -> "$shared:${caller.memberId}:${effective.assignment.assignedAt}"
        }
        return UpstreamTarget(
            server = server,
            credential = credentialId?.let { values[it] },
            secretEnv = server.stdio?.secretEnv.orEmpty()
                .mapNotNull { (name, secretId) -> values[secretId]?.let { name to it } }
                .toMap(),
            processKey = processKey,
        )
    }

    private suspend fun journal(
        call: RelayedOperation,
        outcome: McpOutcome,
        started: Instant = clock.now(),
        bytes: Long = 0,
        policyId: Uuid? = null,
    ) {
        try {
            repository.record(
                McpCall(
                    id = Uuid.random(),
                    orgId = call.caller.orgId,
                    memberId = call.caller.memberId,
                    serverId = call.effective.server.id,
                    serverName = call.effective.server.name,
                    operation = call.operation,
                    tool = call.tool,
                    outcome = outcome,
                    startedAt = started,
                    durationMillis = (clock.now() - started).inWholeMilliseconds,
                    responseBytes = bytes,
                    policyId = policyId,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The journal is best effort: a failed write never turns a relayed answer into an error.
        }
    }

    /** The protocol of the relay. */
    companion object {
        /** The protocol revisions the relay speaks, newest first. */
        val PROTOCOL_VERSIONS: List<String> = listOf("2025-11-25", "2025-06-18", "2025-03-26")

        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val ACCEPTED = 202
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val MAX_TOOL_NAME = 128
    }
}

private fun toolError(text: String) = buildJsonObject {
    put(
        "content",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        },
    )
    put("isError", true)
}

private fun result(id: JsonElement, value: JsonObject) = RelayAnswer(
    HTTP_OK,
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    },
)

private fun error(status: Int, id: JsonElement, code: Int, text: String) = RelayAnswer(
    status,
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", text)
        }
    },
)

private const val HTTP_OK = 200
