// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.presentation

import dev.ostrom.server.automations.domain.AskWait
import dev.ostrom.server.automations.domain.Wait
import dev.ostrom.server.automations.domain.WaitAction
import dev.ostrom.server.automations.domain.WaitCaller
import dev.ostrom.server.automations.domain.WaitField
import dev.ostrom.server.automations.domain.WaitNotFoundException
import dev.ostrom.server.automations.domain.WaitQuery
import dev.ostrom.server.automations.domain.WaitRef
import dev.ostrom.server.automations.domain.WaitSpec
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.serialization.Serializable
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The waits MCP server of an agent (protocol 2025-06-18, streamable HTTP with JSON answers): `ask_human`,
 * `list_waits`, `get_wait` and `cancel_wait`, bound to the agent's session. It never answers a wait: the person
 * does, from "Waiting for you".
 *
 * @param dependencies The wait use cases.
 */
internal class WaitsMcpServer(private val dependencies: WaitsRoutesDependencies) {
    /**
     * Answers one JSON-RPC message.
     *
     * @param body The request body.
     * @param binding The session and run the agent runs in.
     * @param callerFor The member for a scope; throws when refused.
     *
     * @return The answer, or null for a notification (HTTP 202 without a body).
     */
    suspend fun handle(body: String, binding: WaitsMcpApi, callerFor: suspend (String) -> WaitCaller): JsonObject? {
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
            else -> answer(id, method, message["params"] as? JsonObject ?: JsonObject(emptyMap()), binding, callerFor)
        }
    }

    private suspend fun answer(
        id: JsonElement,
        method: String,
        params: JsonObject,
        binding: WaitsMcpApi,
        callerFor: suspend (String) -> WaitCaller,
    ): JsonObject = when (method) {
        "initialize" -> result(id, initialize(params))
        "ping" -> result(id, JsonObject(emptyMap()))
        "tools/list" -> result(id, buildJsonObject { put("tools", TOOLS) })
        "tools/call" -> {
            val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            if (name == null || name !in TOOL_NAMES) {
                error(id, INVALID_PARAMS, "Unknown tool: ${name.orEmpty().take(MAX_ECHO)}.")
            } else {
                call(id, name, arguments, Bound(binding, callerFor))
            }
        }
        else -> error(id, METHOD_NOT_FOUND, "Method not found.")
    }

    private class Bound(val binding: WaitsMcpApi, val callerFor: suspend (String) -> WaitCaller)

    private suspend fun call(id: JsonElement, name: String, arguments: JsonObject, bound: Bound): JsonObject = try {
        val text = when (name) {
            "ask_human" -> ask(bound, JSON.decodeFromJsonElement<AskArguments>(arguments))
            "list_waits" -> list(bound, JSON.decodeFromJsonElement<ListArguments>(arguments))
            "get_wait" -> describe(
                ownWait(bound, JSON.decodeFromJsonElement<WaitArguments>(arguments).wait, WAITS_READ),
            )
            else -> cancel(bound, JSON.decodeFromJsonElement<WaitArguments>(arguments).wait)
        }
        result(id, toolResult(text, isError = false))
    } catch (failure: OstromException) {
        result(id, toolResult(failure.message, isError = true))
    } catch (_: SerializationException) {
        error(id, INVALID_PARAMS, "Invalid arguments for $name: see its input schema.")
    }

    private suspend fun ask(bound: Bound, arguments: AskArguments): String {
        val question = WaitSpec(arguments.title, arguments.body, arguments.fields, arguments.actions, arguments.refs)
        val request = AskWait(question, bound.binding.session, bound.binding.run, arguments.assignee)
        val wait = dependencies.ask.ask(bound.callerFor(WAITS_ASK), request, null).wait
        return "Asked: wait ${wait.id} is open, for member ${wait.assignee}. End your turn now: the answer comes " +
            "back to this session when the member gives it, and get_wait reads it."
    }

    private suspend fun list(bound: Bound, arguments: ListArguments): String {
        val query = WaitQuery(status = arguments.status, sessionId = bound.binding.session, runId = bound.binding.run)
        val waits = dependencies.read.list(bound.callerFor(WAITS_READ), query).items
        if (waits.isEmpty()) return "No waits."
        return waits.joinToString("\n") { "${it.id}  ${it.status.wire}  ${it.question.title}" }
    }

    private suspend fun cancel(bound: Bound, raw: String): String {
        val wait = ownWait(bound, raw, WAITS_ASK)
        dependencies.answer.cancel(bound.callerFor(WAITS_ASK), wait.id)
        return "Cancelled wait ${wait.id}."
    }

    /** A wait of this agent's session: a wait of another session is not this agent's to read or cancel. */
    private suspend fun ownWait(bound: Bound, raw: String, scope: String): Wait {
        val wait = dependencies.read.get(bound.callerFor(scope), waitId(raw))
        return wait.takeIf { it.sessionId == bound.binding.session } ?: throw WaitNotFoundException()
    }

    private fun describe(wait: Wait): String = JSON.encodeToString(WaitResponse.serializer(), wait.toResponse())

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in VERSIONS } ?: VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "ostrom-waits")
                put("version", "1")
            }
            put("instructions", INSTRUCTIONS)
        }
    }

    @Serializable
    private data class AskArguments(
        val title: String,
        val body: String? = null,
        val assignee: String? = null,
        val fields: List<WaitField> = emptyList(),
        val actions: List<WaitAction> = emptyList(),
        val refs: List<WaitRef> = emptyList(),
    )

    @Serializable
    private data class ListArguments(val status: String? = null)

    @Serializable
    private data class WaitArguments(val wait: String)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val MAX_ECHO = 64
        val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        val TOOL_NAMES = setOf("ask_human", "list_waits", "get_wait", "cancel_wait")

        const val INSTRUCTIONS = "When you need an answer, a decision, an approval or a setup only a person can " +
            "give, call ask_human with typed fields instead of guessing, then end your turn. The person answers " +
            "from Waiting for you; you never answer a wait yourself."

        val TOOLS: JsonArray = buildJsonArray {
            add(tool("ask_human", "Ask the session's owner (or another member) and wait for the answer.", ASK_SCHEMA))
            add(tool("list_waits", "This session's waits (open by default).", LIST_SCHEMA))
            add(tool("get_wait", "One of this session's waits, with its answer.", WAIT_SCHEMA))
            add(tool("cancel_wait", "Withdraw one of this session's open waits.", WAIT_SCHEMA))
        }

        private val ASK_SCHEMA get() = schema(
            required = listOf("title"),
            "title" to string("The question, 1 to 200 characters."),
            "body" to string("Markdown: the context the person needs."),
            "assignee" to string("A member id; the session's owner by default."),
            "fields" to array(
                schema(
                    required = listOf("id", "type", "label"),
                    "id" to string("A letter then letters, digits or _."),
                    "type" to string("text, choice, confirm, number, date or info."),
                    "label" to string("What it asks."),
                    "help" to string("More detail."),
                    "required" to type("boolean"),
                    "options" to array(type("string")),
                    "multiple" to type("boolean"),
                    "allowOther" to type("boolean"),
                    "maxLength" to type("integer"),
                ),
            ),
            "actions" to array(
                schema(
                    required = listOf("id", "label"),
                    "id" to string("A letter then letters, digits or _."),
                    "label" to string("The button."),
                    "requires" to array(type("string")),
                    "style" to string("primary, secondary or danger."),
                ),
            ),
            "refs" to array(
                schema(
                    required = listOf("kind"),
                    "kind" to string("artifact, session (by id) or url (https)."),
                    "id" to type("string"),
                    "url" to type("string"),
                    "title" to type("string"),
                ),
            ),
        )

        private val LIST_SCHEMA get() = schema(
            required = emptyList(),
            "status" to string("open (default), resolved, cancelled or all."),
        )

        private val WAIT_SCHEMA get() = schema(required = listOf("wait"), "wait" to string("The wait id."))

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

        private fun tool(name: String, description: String, input: JsonObject) = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", input)
        }

        private fun type(name: String) = buildJsonObject { put("type", name) }

        private fun string(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun array(items: JsonObject) = buildJsonObject {
            put("type", "array")
            put("items", items)
        }

        private fun schema(required: List<String>, vararg properties: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { properties.forEach { (name, value) -> put(name, value) } }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }
}
