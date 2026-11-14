// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.HostedProcessStatus
import run.ostrom.server.mcprelay.domain.McpOutcome
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.UpstreamResult
import run.ostrom.server.mcprelay.domain.UpstreamTarget
import run.ostrom.server.mcprelay.infrastructure.HostMcpUpstream
import run.ostrom.server.mcprelay.infrastructure.HttpMcpUpstream
import run.ostrom.server.mcprelay.infrastructure.McpHostConfig
import run.ostrom.server.mcprelay.infrastructure.TransportMcpUpstream
import run.ostrom.server.mcprelay.infrastructure.UpstreamAddressGuard
import run.ostrom.server.secrets.domain.SecretValue
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun aListingStopsAtSixteenPagesAndReadsOnlyWellFormedPages() = realTime {
        val endless = HttpMcpUpstream(
            client { request, body -> mcp(request, body) { """{"tools":[{"name":"t"}],"nextCursor":"again"}""" } },
            UpstreamAddressGuard(),
        ).listTools(httpTarget(), 5.seconds)
        assertEquals(16, (endless as UpstreamResult.Ok).value.size, "At most 16 pages")
        val odd = HttpMcpUpstream(
            client { request, body -> mcp(request, body) { """{"tools":"none","nextCursor":5}""" } },
            UpstreamAddressGuard(),
        ).listTools(httpTarget(), 5.seconds)
        assertEquals(emptyList(), (odd as UpstreamResult.Ok).value)
    }

    @Test
    fun aListingOrACallThatFailsAfterInitializeIsAnOutcome() = realTime {
        fun upstream(answer: suspend MockRequestHandleScope.(HttpRequestData, JsonObject?) -> HttpResponseData) =
            HttpMcpUpstream(
                client { request, body ->
                    val method = body?.get("method")?.jsonPrimitive?.content
                    if (method == "tools/list" || method == "tools/call") {
                        answer(request, body)
                    } else {
                        mcp(request, body) { "{}" }
                    }
                },
                UpstreamAddressGuard(),
            )
        val listError = upstream { _, b ->
            respond("""{"jsonrpc":"2.0","id":${b!!["id"]},"error":{"code":1}}""", HttpStatusCode.OK)
        }.listTools(httpTarget(), 5.seconds)
        assertEquals(
            "The MCP server could not list its tools: unknown error",
            (listError as UpstreamResult.Failure).message,
        )
        val listDown = upstream { _, _ -> respond("", HttpStatusCode.BadGateway) }.listTools(httpTarget(), 5.seconds)
        assertEquals("The MCP server answered HTTP 502.", (listDown as UpstreamResult.Failure).message)
        val callDown = upstream { _, _ -> respond("", HttpStatusCode.BadGateway) }
            .callTool(httpTarget(), "t", JsonObject(emptyMap()), 5.seconds)
        assertEquals(McpOutcome.UPSTREAM_ERROR, (callDown as UpstreamResult.Failure).outcome)
        val empty = upstream { _, b -> respond("""{"jsonrpc":"2.0","id":${b!!["id"]}}""", HttpStatusCode.OK) }
            .callTool(httpTarget(), "t", JsonObject(emptyMap()), 5.seconds)
        assertEquals("The MCP server sent an invalid answer.", (empty as UpstreamResult.Failure).message)
    }

    @Test
    fun anOddSessionIdIsIgnoredAndAMissingCredentialIsNotSent() = realTime {
        // Below 0x21, empty, above 0x7e and longer than 256: each is ignored.
        for (sessionId in listOf("s 1", "", "s\u007f1", "s".repeat(257))) {
            requests.clear()
            val upstream = HttpMcpUpstream(
                client { request, body ->
                    val method = body?.get("method")?.jsonPrimitive?.content
                    if (method == "initialize") {
                        json(body["id"], """{"protocolVersion":"2025-06-18"}""", mapOf("Mcp-Session-Id" to sessionId))
                    } else {
                        mcp(request, body) { "{}" }
                    }
                },
                UpstreamAddressGuard(),
            )
            upstream.callTool(httpTarget().copy(credential = null), "t", JsonObject(emptyMap()), 5.seconds)
            assertTrue(requests.none { it.first.headers["Mcp-Session-Id"] != null }, sessionId)
            assertTrue(requests.none { it.first.method == HttpMethod.Delete }, "No session, nothing to close")
            assertTrue(requests.none { it.first.headers[HttpHeaders.Authorization] != null })
        }
        val noVersion = HttpMcpUpstream(
            client { _, body -> json(body!!["id"], "{}") },
            UpstreamAddressGuard(),
        ).listTools(httpTarget(), 5.seconds)
        assertEquals(
            "The MCP server speaks an unsupported protocol version.",
            (noVersion as UpstreamResult.Failure).message,
        )
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
            "https://mcp.example.com:70000/",
            "https://mcp.example.com:port/",
            "https://./mcp",
            "https://local/",
            "https://svc/",
            "https://[2600::1.2.3.4]/",
            "https://[2002::1]/",
            // Shorthand, octal, hex and integer forms of 127.0.0.1 and 169.254.169.254, which inet_aton accepts.
            "https://127.1/",
            "https://0x7f.0.0.1/",
            "https://0177.0.0.1/",
            "https://0xa9.0xfe.0xa9.0xfe/",
            "https://169.254.43518/",
            "https://2130706433/",
            "https://0x7f000001/",
            "https://0x/",
            // Leading zeros and out-of-range octets are not canonical: refused rather than guessed.
            "https://010.8.8.8/",
            "https://0001.2.3.4/",
            "https://1.2.3.256/",
        ).forEach { assertFalse(guard.allows(it), it) }
        listOf(
            "https://mcp.grafana.com/mcp",
            "https://8.8.8.8/",
            "https://[2606:4700::1111]/",
            "https://172.32.0.1",
            // Five numeric parts is not an address; hex letters without 0x make a name.
            "https://1.2.3.4.5/",
            "https://cafe.de/",
            "https://0xcafe.com/",
            "https://0xzz.example.com/",
        ).forEach {
            assertTrue(guard.allows(it), it)
        }
        assertTrue(UpstreamAddressGuard(allowPrivate = true).allows("https://10.0.0.5/mcp"))
        assertFalse(UpstreamAddressGuard(allowPrivate = true).allows("http://10.0.0.5/mcp"))
    }

    @Test
    fun anIpv6BelowGlobalUnicastIsRefusedAndAMinusSignMakesAName() {
        val guard = UpstreamAddressGuard()
        assertFalse(guard.allows("https://[1000::1]/"), "Below 2000::/3")
        assertTrue(guard.allows("https://1.2.3.-1/"), "inet_aton reads no negative octet: a name, left to DNS")
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
        val key = body!!["key"]!!.jsonPrimitive.content
        assertEquals(HostMcpUpstream.processKey("org:server:1"), key)
        assertEquals(64, key.length)
        // No secret in the key: a rotated value keeps it, and the host restarts the process for the new definition.
        val rotated = stdioTarget().copy(secretEnv = mapOf("TOKEN" to SecretValue("discord_bot_token_2")))
        upstream.callTool(rotated, "discord_send", buildJsonObject { put("text", "hi") }, 5.seconds)
        assertEquals(key, requests.last().second!!["key"]!!.jsonPrimitive.content)
        assertEquals(
            "discord_bot_token_2",
            requests.last().second!!["spec"]!!.jsonObject["secrets"]!!.jsonObject["TOKEN"]!!.jsonPrimitive.content,
        )
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
    fun oddHostAnswersAreReadSafelyAndTheTokenIsNeverPrinted() = realTime {
        fun host(body: String) =
            HostMcpUpstream(client { _, _ -> respond(body, HttpStatusCode.OK) }, McpHostConfig("http://h/", "t"))

        suspend fun call(body: String) =
            host(body).callTool(stdioTarget(), "t", JsonObject(emptyMap()), 1.seconds) as UpstreamResult.Failure

        val invalid = "The MCP host sent an invalid answer."
        assertEquals(invalid, call("not json").message)
        assertEquals(invalid, call("[]").message)
        assertEquals(invalid, call("""{"result":[]}""").message)
        assertEquals("unknown error", call("""{"error":{"code":{},"message":{}}}""").message)
        assertEquals("unknown error", call("""{"error":{}}""").message)
        assertTrue("same token" in call("""{"error":{"code":"unsupported_protocol"}}""").message)
        val listed = host("""{"result":{"tools":[{"name":"a"},3]}}""").listTools(stdioTarget(), 1.seconds)
        assertEquals(listOf("a"), (listed as UpstreamResult.Ok).value.map { it["name"]!!.jsonPrimitive.content })
        val none = host("""{"result":{"tools":"x"}}""").listTools(stdioTarget(), 1.seconds)
        assertEquals(emptyList(), (none as UpstreamResult.Ok).value)
        assertFalse("secret" in McpHostConfig("http://h", "secret").toString())
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

    @Test
    fun theHostIsWarmedAndToldTheKeysToKeep() = realTime {
        var answer = """{"stopped":2}"""
        var status = HttpStatusCode.OK
        var warmStatus = HttpStatusCode.Unauthorized
        val upstream = HostMcpUpstream(
            client { request, _ ->
                if (request.url.encodedPath.endsWith("/v1/warm")) {
                    respond("""{"state":"starting"}""", warmStatus)
                } else {
                    respond(answer, status)
                }
            },
            McpHostConfig("http://mcp-host:8080/", "host_token"),
        )
        // A refused warm-up fails, which leaves its key free for the next listing.
        assertFailsWith<IllegalStateException> { upstream.warm(stdioTarget()) }
        warmStatus = HttpStatusCode.Accepted
        upstream.warm(stdioTarget())
        val (warm, warmBody) = requests.last()
        assertEquals("http://mcp-host:8080/v1/warm", warm.url.toString())
        assertEquals(HostMcpUpstream.processKey("org:server:1"), warmBody!!["key"]!!.jsonPrimitive.content)
        assertEquals("npm", warmBody["spec"]!!.jsonObject["launcher"]!!.jsonPrimitive.content)
        requests.clear()
        // An HTTP server has nothing to warm.
        upstream.warm(stdioTarget().copy(server = stdioTarget().server.copy(stdio = null)))
        assertTrue(requests.isEmpty())
        assertEquals(2, upstream.retain(listOf("org:server:1")))
        val (retain, retainBody) = requests.last()
        assertEquals("http://mcp-host:8080/v1/retain", retain.url.toString())
        assertEquals("Bearer host_token", retain.headers[HttpHeaders.Authorization])
        assertEquals("1", retain.headers["TeamCode-Host-Protocol"])
        assertEquals(
            listOf(HostMcpUpstream.processKey("org:server:1")),
            retainBody!!["keys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val refusals = listOf(
            """{"error":{"code":"unauthorized"}}""" to HttpStatusCode.Unauthorized,
            "not json" to HttpStatusCode.OK,
            "[]" to HttpStatusCode.OK,
            """{"stopped":"many"}""" to HttpStatusCode.OK,
            """{"stopped":{}}""" to HttpStatusCode.OK,
        )
        for ((body, code) in refusals) {
            answer = body
            status = code
            assertFailsWith<IllegalStateException>(body) { upstream.retain(emptyList()) }
        }
    }

    @Test
    fun theHostsStatusIsReadByRelayKeyAndAStopNamesTheHashedKey() = realTime {
        var answer = """{"version":"1.2.3","processes":[{"key":"${HostMcpUpstream.processKey("org:a:1")}",""" +
            """"state":"failed","launcher":"npm","package":"a@1.0.0","starts":2,"failures":1,"lastExitCode":1,""" +
            """"lastFailure":"crashed","coldStartMillis":900,"rssBytes":1024,"stderr":"boom"},""" +
            """{"key":"${HostMcpUpstream.processKey("org:b:1")}"},{"state":"ready"},"junk"]}"""
        var status = HttpStatusCode.OK
        val upstream = HostMcpUpstream(
            client { _, _ -> respond(answer, status) },
            McpHostConfig("http://mcp-host:8080", "host_token"),
        )
        val snapshot = upstream.status()
        val (request, body) = requests.last()
        assertEquals("http://mcp-host:8080/v1/status", request.url.toString())
        assertEquals(HttpMethod.Get, request.method)
        assertEquals(null, body)
        assertEquals("1.2.3", snapshot.version)
        assertEquals(
            HostedProcessStatus("failed", "npm", "a@1.0.0", 2, 1, 1, "crashed", 900, 1024, "boom"),
            snapshot.of("org:a:1"),
        )
        assertEquals(HostedProcessStatus("unknown", "", ""), snapshot.of("org:b:1"))
        assertEquals(null, snapshot.of("org:c:1"))
        for (refused in listOf("""{"processes":[]}""", """{"version":"1"}""", "[]")) {
            answer = refused
            assertFailsWith<IllegalStateException>(refused) { upstream.status() }
        }
        answer = """{"version":"1","processes":[]}"""
        status = HttpStatusCode.Unauthorized
        assertFailsWith<IllegalStateException> { upstream.status() }
        status = HttpStatusCode.OK
        answer = """{"stopped":true}"""
        assertTrue(upstream.stop("org:a:1"))
        assertEquals(HostMcpUpstream.processKey("org:a:1"), requests.last().second!!["key"]!!.jsonPrimitive.content)
        assertEquals("http://mcp-host:8080/v1/stop", requests.last().first.url.toString())
        for (refused in listOf("""{"stopped":"yes"}""", """{}""", "not json")) {
            answer = refused
            assertFailsWith<IllegalStateException>(refused) { upstream.stop("org:a:1") }
        }
        status = HttpStatusCode.Unauthorized
        answer = """{"stopped":false}"""
        assertFailsWith<IllegalStateException> { upstream.stop("org:a:1") }
    }
}

fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }
