// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.presentation

import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.workflowruns.domain.usecases.DeliverWorkflowSessionUseCase
import run.ostrom.server.workflowruns.domain.usecases.StepCaller
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.Uuid

/**
 * The `ostrom-workflow` MCP server of an agent working on a workflow step (TeamCode `WorkflowMcpServer`; protocol
 * 2025-06-18, streamable HTTP with JSON answers): one tool, `deliver {output}`, which does what
 * `deliverWorkflowSession` does for the session the relay names. A refusal comes back as a tool error saying what to
 * do about it.
 *
 * @param deliver Delivers for a session's agent step.
 */
internal class WorkflowsMcpServer(private val deliver: DeliverWorkflowSessionUseCase) {
    /**
     * Answers one JSON-RPC message.
     *
     * @param body The request body.
     * @param callerOf Who calls and for which session, read from the credential only when the tool is called; throws
     *   when refused.
     *
     * @return The answer, or null for a notification (HTTP 202 without a body).
     */
    suspend fun handle(body: String, callerOf: suspend () -> Pair<StepCaller, Uuid?>): JsonObject? {
        val message = try {
            JSON.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "Parse error.")
        }
        val method = (message?.get("method") as? JsonPrimitive)?.contentOrNull
        val id = message?.get("id")
        return when {
            method == null -> error(id ?: JsonNull, INVALID_REQUEST, "Invalid request.")
            id == null -> null
            else -> answer(id, method, message["params"] as? JsonObject ?: JsonObject(emptyMap()), callerOf)
        }
    }

    private suspend fun answer(
        id: JsonElement,
        method: String,
        params: JsonObject,
        callerOf: suspend () -> Pair<StepCaller, Uuid?>,
    ): JsonObject = when (method) {
        "initialize" -> result(id, initialize(params))
        "ping" -> result(id, JsonObject(emptyMap()))
        "tools/list" -> result(id, buildJsonObject { put("tools", TOOLS) })
        "tools/call" -> {
            val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            if (name != TOOL) {
                error(id, INVALID_PARAMS, "Unknown tool: ${name.orEmpty().take(MAX_ECHO)}.")
            } else {
                result(id, call(params["arguments"] as? JsonObject, callerOf))
            }
        }
        else -> error(id, METHOD_NOT_FOUND, "Method not found.")
    }

    private suspend fun call(arguments: JsonObject?, callerOf: suspend () -> Pair<StepCaller, Uuid?>): JsonObject {
        val output = arguments?.get("output")?.takeIf { it !is JsonNull }
            ?: return toolResult("output is required: hand in what the step asked for.", isError = true)
        return try {
            val (caller, session) = callerOf()
            val run = deliver(caller, session, output, null)
            val answer = buildJsonObject {
                put("delivered", true)
                put("run", run.id)
                put("status", run.status.value)
                put("instruction", DELIVERED)
            }
            toolResult(answer.toString(), isError = false)
        } catch (refused: OstromException) {
            toolResult(listOfNotNull(refused.message, HINTS[refused.type]).joinToString(" "), isError = true)
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in VERSIONS } ?: VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "ostrom-workflow")
                put("version", "1")
            }
            put("instructions", INSTRUCTIONS)
        }
    }
}

// The protocol's error codes, the versions spoken, and the most of an unknown tool's name echoed.
private const val PARSE_ERROR = -32700
private const val INVALID_REQUEST = -32600
private const val METHOD_NOT_FOUND = -32601
private const val INVALID_PARAMS = -32602
private const val MAX_ECHO = 64
private val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
private val JSON = Json { ignoreUnknownKeys = true }
private const val TOOL = "deliver"

private const val INSTRUCTIONS = "You are working on a step of an Ostrom workflow. When the task of the step is " +
    "done, call `deliver` once with exactly what the step asked for (its instruction names the form), then end your " +
    "turn. Do not call it for anything else."

private const val DELIVERED = "Your delivery is recorded. End your turn now without doing anything else on this " +
    "step; do not deliver again."

// What to do about each refusal of the server.
private val HINTS = mapOf(
    "invalid_deliverable" to "Deliver exactly what the step asked for, in the form its instruction names, as the " +
        "message says; then call deliver again.",
    "nothing_to_deliver" to "This session is not running a workflow step that waits for a delivery, or the step " +
        "already has its delivery: end your turn.",
    "todo_open" to "Finish or drop the open items of this session's todo, then call deliver again.",
    "credential_scope" to "This session's credential cannot deliver a workflow step; say what you produced in your " +
        "reply instead.",
    "not_found" to "No workflow run is bound to this session for you.",
)

private val TOOLS: JsonArray = buildJsonArray {
    add(
        buildJsonObject {
            put("name", TOOL)
            put(
                "description",
                "Hand in the result of the workflow step you are working on. Call it once, when the task of the " +
                    "current step is done, with exactly what the step's instruction asked for: a string for text, an " +
                    "https address or an artifact id, a JSON value for structured data. The server checks it against " +
                    "the step and refuses what does not match (then fix it and call again). After it succeeds, end " +
                    "your turn: the workflow moves on.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("output") {
                        put(
                            "description",
                            "What the step asked for: a string (text, https address or artifact id) or, when the " +
                                "step asks for JSON, a JSON value (not a string holding JSON).",
                        )
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("output")) }
            }
        },
    )
}

private fun toolResult(text: String, isError: Boolean) = buildJsonObject {
    putJsonArray("content") {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            },
        )
    }
    put("isError", isError)
}

private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}
