package dev.ostrom.server.mcprelay.infrastructure

import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpUpstream
import dev.ostrom.server.mcprelay.domain.UpstreamResult
import dev.ostrom.server.mcprelay.domain.UpstreamTarget
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCaseImpl
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Stateless Streamable HTTP client of an upstream MCP server: each operation sends `initialize`,
 * `notifications/initialized`, the request, then a best-effort `DELETE` of the upstream session when one was issued.
 * Accepts JSON and server-sent event answers, sends the credential as `Authorization: Bearer` or in the configured
 * header, never follows a redirect (the client is built with `followRedirects = false`), bounds every answer.
 *
 * @param client The HTTP client (redirects off).
 * @param guard Refuses private destinations.
 * @param maxResponseBytes The largest answer read.
 */
class HttpMcpUpstream(
    private val client: HttpClient,
    private val guard: UpstreamAddressGuard,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) : McpUpstream {
    override suspend fun listTools(target: UpstreamTarget, deadline: Duration): UpstreamResult<List<JsonObject>> =
        session(target, deadline) { session ->
            val tools = mutableListOf<JsonObject>()
            var cursor: String? = null
            var bytes = 0L
            repeat(MAX_PAGES) {
                val params = buildJsonObject { cursor?.let { put("cursor", it) } }
                when (val page = session.request("tools/list", params)) {
                    is Reply.Result -> {
                        bytes += page.bytes
                        (page.value["tools"] as? JsonArray)?.forEach { tool -> (tool as? JsonObject)?.let(tools::add) }
                        cursor = (page.value["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (cursor == null) return@session UpstreamResult.Ok(tools.toList(), bytes)
                    }
                    is Reply.Error -> return@session UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server could not list its tools: ${page.message}")
                    is Reply.Refused -> return@session page.failure
                }
            }
            UpstreamResult.Ok(tools.toList(), bytes)
        }

    override suspend fun callTool(target: UpstreamTarget, tool: String, arguments: JsonObject, deadline: Duration): UpstreamResult<JsonObject> =
        session(target, deadline) { session ->
            val params = buildJsonObject {
                put("name", tool)
                put("arguments", arguments)
            }
            when (val reply = session.request("tools/call", params)) {
                is Reply.Result -> UpstreamResult.Ok(reply.value, reply.bytes)
                is Reply.Error -> UpstreamResult.Failure(McpOutcome.TOOL_ERROR, "The MCP server returned an error: ${reply.message}")
                is Reply.Refused -> reply.failure
            }
        }

    private suspend fun <T> session(target: UpstreamTarget, deadline: Duration, block: suspend (Session) -> UpstreamResult<T>): UpstreamResult<T> {
        val url = target.server.url ?: return UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server has no URL.")
        if (!guard.allows(url)) return UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server's address is not allowed.")
        val session = Session(url, target)
        try {
            return withTimeoutOrNull(deadline) {
                when (val initialized = session.request("initialize", INITIALIZE_PARAMS)) {
                    is Reply.Result -> {
                        session.protocolVersion = (initialized.value["protocolVersion"] as? JsonPrimitive)?.content
                            ?.takeIf { it in RelayMcpMessageUseCaseImpl.PROTOCOL_VERSIONS }
                            ?: return@withTimeoutOrNull UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server speaks an unsupported protocol version.")
                        session.notify("notifications/initialized")
                        block(session)
                    }
                    is Reply.Error -> UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server refused to initialize: ${initialized.message}")
                    is Reply.Refused -> initialized.failure
                }
            } ?: UpstreamResult.Failure(McpOutcome.TIMEOUT, "The MCP server did not answer in time.")
        } finally {
            session.close()
        }
    }

    private sealed interface Reply {
        data class Result(val value: JsonObject, val bytes: Long) : Reply
        data class Error(val message: String) : Reply
        data class Refused(val failure: UpstreamResult.Failure) : Reply
    }

    private inner class Session(private val url: String, private val target: UpstreamTarget) {
        var protocolVersion: String? = null
        private var sessionId: String? = null
        private var nextId = 1

        suspend fun request(method: String, params: JsonObject): Reply {
            val id = nextId++
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            return post(body) { response ->
                if (!response.status.isSuccess()) {
                    return@post Reply.Refused(UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server answered HTTP ${response.status.value}."))
                }
                response.headers["Mcp-Session-Id"]?.takeIf { it.length in 1..256 && it.all { c -> c.code in 0x21..0x7e } }?.let { sessionId = it }
                val bytes = response.bodyAsChannel().readRemaining(maxResponseBytes.toLong() + 1).readByteArray()
                if (bytes.size > maxResponseBytes) {
                    return@post Reply.Refused(UpstreamResult.Failure(McpOutcome.TOO_LARGE, "The MCP server's response exceeds the relay limit."))
                }
                val message = parse(bytes.decodeToString(), response.headers[HttpHeaders.ContentType], id)
                    ?: return@post Reply.Refused(UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server sent an invalid answer."))
                val error = message["error"] as? JsonObject
                val result = message["result"] as? JsonObject
                when {
                    error != null -> Reply.Error((error["message"] as? JsonPrimitive)?.content?.take(MAX_ERROR) ?: "unknown error")
                    result != null -> Reply.Result(result, bytes.size.toLong())
                    else -> Reply.Refused(UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server sent an invalid answer."))
                }
            }
        }

        suspend fun notify(method: String) {
            post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }) { }
        }

        suspend fun close() {
            val id = sessionId ?: return
            withContext(NonCancellable) {
                withTimeoutOrNull(2.seconds) {
                    try {
                        client.delete(url) { header("Mcp-Session-Id", id); credential(this) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Best effort: the upstream expires the session by itself.
                    }
                }
            }
        }

        private suspend fun <R> post(body: JsonObject, read: suspend (HttpResponse) -> R): R =
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                protocolVersion?.let { header("MCP-Protocol-Version", it) }
                sessionId?.let { header("Mcp-Session-Id", it) }
                credential(this)
                setBody(body.toString())
            }.execute { read(it) }

        private fun credential(builder: io.ktor.client.request.HttpRequestBuilder) {
            val value = target.credential?.reveal() ?: return
            val name = target.server.credentialHeader
            if (name == null) builder.header(HttpHeaders.Authorization, "Bearer $value") else builder.header(name, value)
        }
    }

    private fun parse(text: String, contentType: String?, id: Int): JsonObject? {
        val candidates: List<String> = if (contentType?.startsWith("text/event-stream") == true) {
            // One message per event; the data lines of an event are joined by newlines.
            text.split("\n\n", "\r\n\r\n").mapNotNull { event ->
                event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }.takeIf { it.isNotEmpty() }
            }
        } else {
            listOf(text)
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            val value = try {
                Json.parseToJsonElement(candidate)
            } catch (_: SerializationException) {
                null
            }
            (value as? JsonObject)?.takeIf { it["id"].isId(id) }
        }
    }

    private fun JsonElement?.isId(id: Int): Boolean = (this as? JsonPrimitive)?.content == id.toString()

    companion object {
        /** The default bound of an upstream answer: 4 MiB. */
        const val DEFAULT_MAX_RESPONSE_BYTES: Int = 4 * 1024 * 1024
        private const val MAX_PAGES = 16
        private const val MAX_ERROR = 1000
        private val INITIALIZE_PARAMS = buildJsonObject {
            put("protocolVersion", RelayMcpMessageUseCaseImpl.PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "ostrom-relay")
                put("version", "1")
            }
        }
    }
}
