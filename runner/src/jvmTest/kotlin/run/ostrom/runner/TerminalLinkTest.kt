// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/** The runner's terminal link against a real WebSocket server. On the JVM only: Ktor's test host needs it. */
class TerminalLinkTest {
    private val id = Uuid.random()
    private val config = RunnerConfig("http://localhost", id, 2, "claude", null, null, "t", "/workspace")

    @Test
    fun theLinkCarriesBothWaysAndEndsWhenAnotherExecutionTakesOver() = testApplication {
        val received = CompletableDeferred<String>()
        install(WebSockets)
        routing {
            webSocket("/api/v1/cloud/runner/terminal") {
                assertEquals("Bearer t", call.request.headers[HttpHeaders.Authorization])
                assertEquals(id.toString(), call.parameters["sessionId"])
                assertEquals("2", call.parameters["epoch"])
                send(Frame.Binary(true, "ls\r".encodeToByteArray()))
                send(Frame.Text("""{"type":"resize","columns":80,"rows":24}"""))
                send(Frame.Text("""{"type":"other"}"""))
                received.complete((incoming.receive() as Frame.Binary).readBytes().decodeToString())
                close(CloseReason(4409.toShort(), "A newer execution holds this terminal."))
            }
        }
        val client = createClient { install(ClientWebSockets) }
        coroutineScope {
            val link = HttpServerApi(client, config, this).openTerminal()
            assertEquals("ls\r", (link.incoming.receive() as ServerFrame.Input).bytes.decodeToString())
            assertEquals(ServerFrame.Resize(80, 24), link.incoming.receive())
            link.send("$ ".encodeToByteArray())
            assertEquals("$ ", received.await())
            assertFailsWith<ExecutionEndedException> { link.incoming.receive() }
            link.close()
        }
    }

    @Test
    fun aNormalCloseLetsTheRunnerOpenTheLinkAgain() = testApplication {
        install(WebSockets)
        routing {
            webSocket("/api/v1/cloud/runner/terminal") { close(CloseReason(CloseReason.Codes.NORMAL, "restart")) }
        }
        val client = createClient { install(ClientWebSockets) }
        coroutineScope {
            val link = HttpServerApi(client, config, this).openTerminal()
            assertEquals(null, link.incoming.receiveCatching().getOrNull())
            link.close()
        }
    }
}
