// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.kernel.http.CallCeilingSettings
import dev.ostrom.server.kernel.http.CallsInProgress
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * P1 memory: a slow upstream MCP server holds its relay calls, so they count under the held calls' ceiling
 * (`OSTROM_MAX_HELD_CALLS`). Any member who plugs in a slow server would otherwise take the server's ordinary places
 * and have every other call told 503. The held ceiling itself is tested in the kernel (CallCeilingTest).
 */
class McpRelayCeilingPostgresTest : RelayPostgresHarness() {
    private val upstreamIn = CompletableDeferred<Unit>()
    private val upstreamAnswers = CompletableDeferred<Unit>()

    /** Answers like the shared fake, but holds every tool call until [upstreamAnswers]. */
    private val slowMcp = HttpClient(
        MockEngine { request ->
            if (request.method == HttpMethod.Delete) return@MockEngine respond("", HttpStatusCode.OK)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val result = when (body["method"]!!.jsonPrimitive.content) {
                "tools/list" -> """{"tools":[{"name":"query_loki_logs","inputSchema":{}}]}"""
                "tools/call" -> {
                    upstreamIn.complete(Unit)
                    upstreamAnswers.await()
                    """{"content":[{"type":"text","text":"3 lines"}]}"""
                }
                else -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}"""
            }
            respond(
                """{"jsonrpc":"2.0","id":${body["id"]},"result":$result}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        followRedirects = false
        expectSuccess = false
    }

    private val oneCallAtOnce = module {
        includes(overrides)
        single(McpRelayModule.HTTP_CLIENT) { slowMcp }
        single { CallsInProgress(CallCeilingSettings(served = 1, held = 1, queueWait = 2.seconds)) { null } }
    }

    private val toolCall =
        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"query_loki_logs","arguments":{}}}"""

    @Test
    fun slowMcpCallsFillingTheirCeilingDoNotBlockAnOrdinaryRead() =
        serverTest(listOf(SecretsModule, McpRelayModule), oneCallAtOnce) { server ->
            with(server) {
                assign(grafana(), "all", null)
                coroutineScope {
                    val slow = async { rpc("grafana", "member-a", toolCall) }
                    val reached = select {
                        upstreamIn.onAwait { true }
                        slow.onAwait { false }
                    }
                    if (!reached) {
                        val answer = slow.await()
                        fail("The relay answered before the upstream: ${answer.status} ${answer.bodyAsText()}")
                    }
                    // The one ordinary place is free: the slow call holds a held place.
                    val read = client.get("$admin/servers") { bearerAuth("admin-a") }
                    assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
                    upstreamAnswers.complete(Unit)
                    assertEquals(HttpStatusCode.OK, slow.await().status)
                }
            }
        }
}
