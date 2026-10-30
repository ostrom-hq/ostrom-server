// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.StdioLauncher
import dev.ostrom.server.mcprelay.domain.StdioSpec
import dev.ostrom.server.mcprelay.domain.UpstreamResult
import dev.ostrom.server.mcprelay.domain.UpstreamTarget
import dev.ostrom.server.mcprelay.infrastructure.HostMcpUpstream
import dev.ostrom.server.mcprelay.infrastructure.HttpMcpUpstream
import dev.ostrom.server.mcprelay.infrastructure.McpHostConfig
import dev.ostrom.server.mcprelay.infrastructure.TransportMcpUpstream
import dev.ostrom.server.mcprelay.infrastructure.UpstreamAddressGuard
import dev.ostrom.server.secrets.domain.SecretValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UpstreamsTest {
    private val requests = mutableListOf<Pair<HttpRequestData, JsonObject?>>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData, JsonObject?) -> HttpResponseData) =
        HttpClient(
            MockEngine { request ->
                val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                    ?: (request.body as? io.ktor.http.content.TextContent)?.text
                val json = body?.let { Json.parseToJsonElement(it).jsonObject }
                requests += request to json
                handler(request, json)
            },
        ) {
            followRedirects = false
            expectSuccess = false
        }

    private fun httpTarget(header: String? = null) = UpstreamTarget(
        server = Relay.server().copy(credentialHeader = header),
        credential = SecretValue("upstream_token"),
        secretEnv = emptyMap(),
        processKey = "k",
    )

    private fun MockRequestHandleScope.json(id: Any?, result: String, headers: Map<String, String> = emptyMap()) =
        respond(
            """{"jsonrpc":"2.0","id":$id,"result":$result}""",
            HttpStatusCode.OK,
            headersOf(
                *(headers + (HttpHeaders.ContentType to "application/json")).map {
                    it.key to listOf(it.value)
                }.toTypedArray(),
            ),
        )

    private fun MockRequestHandleScope.mcp(
        request: HttpRequestData,
        body: JsonObject?,
        answer: (String) -> String,
    ): HttpResponseData {
        if (request.method == HttpMethod.Delete) return respond("", HttpStatusCode.OK)
        val method = body!!["method"]!!.jsonPrimitive.content
        val id = body["id"]?.toString()
        return when (method) {
            "initialize" -> json(
                id,
                """{"protocolVersion":"2025-06-18","capabilities":{}}""",
                mapOf(
                    "Mcp-Session-Id" to "s-1",
                ),
            )
            "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
            else -> json(id, answer(method))
        }
    }

    @Test
    fun httpListsEveryPageWithTheCredentialAndClosesTheSession() = realTime {
        var page = 0
        val upstream = HttpMcpUpstream(
            client { request, body ->
                mcp(request, body) {
                    page++
                    if (page ==
                        1
                    ) {
                        """{"tools":[{"name":"a"}],"nextCursor":"c2"}"""
                    } else {
                        """{"tools":[{"name":"b"},3]}"""
                    }
                }
            },
            UpstreamAddressGuard(),
        )
        val result = upstream.listTools(httpTarget(), 5.seconds)
        assertIs<UpstreamResult.Ok<List<JsonObject>>>(result)
        assertEquals(listOf("a", "b"), result.value.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(requests.all { it.first.headers[HttpHeaders.Authorization] == "Bearer upstream_token" })
        assertEquals("c2", requests[3].second!!["params"]!!.jsonObject["cursor"]!!.jsonPrimitive.content)
        assertEquals("s-1", requests[2].first.headers["Mcp-Session-Id"])
        assertEquals("2025-06-18", requests[2].first.headers["MCP-Protocol-Version"])
        assertEquals(HttpMethod.Delete, requests.last().first.method)
    }

    @Test
    fun httpCallsAToolOverServerSentEventsWithACustomHeader() = realTime {
        val upstream = HttpMcpUpstream(
            client { request, body ->
                if (body?.get("method")?.jsonPrimitive?.content == "tools/call") {
                    val id = body["id"].toString()
                    respond(
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
                            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\n" +
                            "data: \"result\":{\"content\":[]}}\n\n",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                } else {
                    mcp(request, body) { "{}" }
                }
            },
            UpstreamAddressGuard(),
        )
        val result = upstream.callTool(
            httpTarget(header = "X-Api-Key"),
            "t",
            buildJsonObject {
                put("q", 1)
            },
            5.seconds,
        )
        assertIs<UpstreamResult.Ok<JsonObject>>(result)
        val call = requests.first { it.second?.get("method")?.jsonPrimitive?.content == "tools/call" }
        assertEquals("upstream_token", call.first.headers["X-Api-Key"])
        assertEquals(null, call.first.headers[HttpHeaders.Authorization])
        assertEquals("t", call.second!!["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun httpFailuresBecomeOutcomes() = realTime {
        suspend fun outcome(
            maxBytes: Int = 1 shl 20,
            answer: suspend MockRequestHandleScope.(HttpRequestData, JsonObject?) -> HttpResponseData,
        ) = (
            HttpMcpUpstream(
                client(answer),
                UpstreamAddressGuard(),
                maxBytes,
            ).callTool(httpTarget(), "t", JsonObject(emptyMap()), 200.milliseconds)
                as UpstreamResult.Failure
            )

        assertEquals(McpOutcome.UPSTREAM_ERROR, outcome { _, _ -> respond("no", HttpStatusCode.Unauthorized) }.outcome)
        assertEquals(McpOutcome.UPSTREAM_ERROR, outcome { _, _ -> respond("not json", HttpStatusCode.OK) }.outcome)
        assertEquals(McpOutcome.TOO_LARGE, outcome(maxBytes = 10) { r, b -> mcp(r, b) { "{}" } }.outcome)
        assertEquals(McpOutcome.TIMEOUT, outcome { _, _ -> awaitCancellation() }.outcome)
        val refusedInit =
            outcome { _, b ->
                respond("""{"jsonrpc":"2.0","id":${b!!["id"]},"error":{"message":"nope"}}""", HttpStatusCode.OK)
            }
        assertEquals("The MCP server refused to initialize: nope", refusedInit.message)
        val oldProtocol = outcome { _, b -> json(b!!["id"], """{"protocolVersion":"2024-11-05"}""") }
        assertEquals("The MCP server speaks an unsupported protocol version.", oldProtocol.message)
        val toolError = outcome { r, b ->
            if (b?.get("method")?.jsonPrimitive?.content == "tools/call") {
                respond(
                    """{"jsonrpc":"2.0","id":${b["id"]},"error":{"code":-32602,"message":"bad args"}}""",
                    HttpStatusCode.OK,
                )
            } else {
                mcp(r, b) { "{}" }
            }
        }
        assertEquals(McpOutcome.TOOL_ERROR, toolError.outcome)
        val noUrl = HttpMcpUpstream(client { _, _ -> error("never") }, UpstreamAddressGuard())
            .listTools(httpTarget().copy(server = Relay.server().copy(url = null)), 1.seconds)
        assertEquals("The MCP server's address is not allowed.", (noUrl as UpstreamResult.Failure).message)
        val private = HttpMcpUpstream(client { _, _ -> error("never") }, UpstreamAddressGuard())
            .listTools(
                httpTarget().copy(server = Relay.server().copy(url = "https://169.254.169.254/latest")),
                1.seconds,
            )
        assertEquals("The MCP server's address is not allowed.", (private as UpstreamResult.Failure).message)
    }

    @Test
    fun theGuardRefusesPrivateDestinations() {
        val guard = UpstreamAddressGuard()
        listOf(
            "http://mcp.example.com",
            "https://localhost/x",
            "https://127.0.0.1",
            "https://10.1.2.3",
            "https://172.16.0.1",
            "https://192.168.1.1",
            "https://169.254.169.254",
            "https://100.64.0.1",
            "https://0.0.0.0",
            "https://224.0.0.1",
            "https://[::1]/",
            "https://[fd00::1]/",
            "https://[fe80::1]/",
            "https://[::ffff:127.0.0.1]/",
            "https://[2001:db8::1]/",
            "https://api.svc",
            "https://grafana.monitoring.svc.cluster.local",
            "https://printer.local",
            "https://intranet",
            "https://198.18.0.1",
            "https://192.0.2.1",
            "not a url",
        ).forEach { assertFalse(guard.allows(it), it) }
        listOf(
            "https://mcp.grafana.com/mcp",
            "https://8.8.8.8/",
            "https://[2606:4700::1111]/",
            "https://172.32.0.1",
        ).forEach {
            assertTrue(guard.allows(it), it)
        }
        assertTrue(UpstreamAddressGuard(allowPrivate = true).allows("https://10.0.0.5/mcp"))
        assertFalse(UpstreamAddressGuard(allowPrivate = true).allows("http://10.0.0.5/mcp"))
    }

    private fun stdioTarget() = UpstreamTarget(
        server = Relay.server(transport = McpTransport.STDIO).copy(
            stdio = StdioSpec(
                StdioLauncher.NPM,
                "mcp-discord@1.2.3",
                listOf("--token", "\${TOKEN}"),
                mapOf(
                    "MODE" to "x",
                ),
                mapOf("TOKEN" to Relay.orgA),
            ),
        ),
        credential = null,
        secretEnv = mapOf("TOKEN" to SecretValue("discord_bot_token")),
        processKey = "org:server:1",
    )

    @Test
    fun theHostRunsStdioServersOverProtocolOne() = realTime {
        val upstream = HostMcpUpstream(
            client { _, body ->
                val op = body!!["op"]!!.jsonPrimitive.content
                val result = if (op == "list") """{"tools":[{"name":"discord_send"}]}""" else """{"content":[]}"""
                respond("""{"result":$result,"cold":false,"state":"ready"}""", HttpStatusCode.OK)
            },
            McpHostConfig("http://mcp-host:8080/", "host_token"),
        )
        val listed = upstream.listTools(stdioTarget(), 5.seconds)
        assertEquals("discord_send", (listed as UpstreamResult.Ok).value.single()["name"]!!.jsonPrimitive.content)
        upstream.callTool(stdioTarget(), "discord_send", buildJsonObject { put("text", "hi") }, 5.seconds)
        val (request, body) = requests.last()
        assertEquals("http://mcp-host:8080/v1/ops", request.url.toString())
        assertEquals("Bearer host_token", request.headers[HttpHeaders.Authorization])
        assertEquals("1", request.headers["TeamCode-Host-Protocol"])
        assertEquals(HostMcpUpstream.processKey("org:server:1"), body!!["key"]!!.jsonPrimitive.content)
        assertEquals(64, body["key"]!!.jsonPrimitive.content.length)
        val spec = body["spec"]!!.jsonObject
        assertEquals("npm", spec["launcher"]!!.jsonPrimitive.content)
        assertEquals("discord_bot_token", spec["secrets"]!!.jsonObject["TOKEN"]!!.jsonPrimitive.content)
        assertEquals("discord_send", body["tool"]!!.jsonPrimitive.content)
        assertEquals(5000, body["deadlineMs"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun hostErrorsBecomeOutcomesAndAListingIsRetriedOnce() = realTime {
        fun host(code: String?, status: HttpStatusCode = HttpStatusCode.OK) = HostMcpUpstream(
            client { _, _ ->
                if (code ==
                    null
                ) {
                    respond("{}", status)
                } else {
                    respond("""{"error":{"code":"$code","message":"m"},"cold":false,"state":"x"}""", status)
                }
            },
            McpHostConfig("http://h", "t"),
        )

        suspend fun call(code: String?, status: HttpStatusCode = HttpStatusCode.OK) =
            host(code, status).callTool(stdioTarget(), "t", JsonObject(emptyMap()), 1.seconds) as UpstreamResult.Failure

        assertEquals(McpOutcome.TIMEOUT, call("timeout").outcome)
        assertEquals(McpOutcome.TOO_LARGE, call("too_large").outcome)
        assertEquals(McpOutcome.TOOL_ERROR, call("tool_error").outcome)
        assertEquals("The MCP host is restarting. Retry shortly.", call("shutting_down").message)
        assertEquals("m", call("crashed").message)
        assertTrue("same token" in call("unauthorized").message)
        assertEquals("m", call("start_failed").message)
        assertEquals("The MCP host sent an invalid answer.", call(null).message)
        assertEquals("The MCP host is unreachable (HTTP 502).", call(null, HttpStatusCode.BadGateway).message)

        requests.clear()
        val listed = host("crashed").listTools(stdioTarget(), 1.seconds)
        assertIs<UpstreamResult.Failure>(listed)
        assertEquals(2, requests.size, "A listing is retried once, a call never")
        val noCommand = host(null).listTools(stdioTarget().copy(server = Relay.server()), 1.seconds)
        assertEquals("The MCP server has no command.", (noCommand as UpstreamResult.Failure).message)
        val slow =
            HostMcpUpstream(
                client { _, _ ->
                    awaitCancellation()
                },
                McpHostConfig("http://h", "t"),
                startAllowance = 0.seconds,
            )
        assertEquals(
            McpOutcome.TIMEOUT,
            (
                slow.callTool(
                    stdioTarget(),
                    "t",
                    JsonObject(emptyMap()),
                    50.milliseconds,
                ) as UpstreamResult.Failure
                ).outcome,
        )
        val down = HostMcpUpstream(client { _, _ -> error("connection refused") }, McpHostConfig("http://h", "t"))
        assertEquals(
            "The MCP host is unreachable.",
            (down.callTool(stdioTarget(), "t", JsonObject(emptyMap()), 1.seconds) as UpstreamResult.Failure).message,
        )
    }

    @Test
    fun eachTransportGoesToItsUpstream() = realTime {
        val http = FakeUpstream()
        val stdio = FakeUpstream()
        TransportMcpUpstream(http, stdio).listTools(httpTarget(), 1.seconds)
        TransportMcpUpstream(http, stdio).callTool(stdioTarget(), "t", JsonObject(emptyMap()), 1.seconds)
        assertEquals(1, http.targets.size)
        assertEquals(1, stdio.targets.size)
        val none = TransportMcpUpstream(http, null).callTool(stdioTarget(), "t", JsonObject(emptyMap()), 1.seconds)
        assertEquals("No MCP host is configured on this server.", (none as UpstreamResult.Failure).message)
        assertIs<UpstreamResult.Failure>(TransportMcpUpstream(http, null).listTools(stdioTarget(), 1.seconds))
    }
}

/**
 * Runs a test on real time: the upstreams bound each request with a timeout, and the virtual time of `runTest` would
 * fire it while the mock engine answers on another dispatcher.
 */
fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }
