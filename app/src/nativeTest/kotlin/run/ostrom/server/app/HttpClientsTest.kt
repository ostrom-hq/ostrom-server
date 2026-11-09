// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class HttpClientsTest {
    /**
     * chart-load 41868912529: the MCP relay answered a tool call of a server that answers at once in 315 ms (p95 321,
     * over the 250 ms of the load pass), 4 exchanges of about 75 ms each, while the hanging and the slow servers kept
     * other calls of the same client open. Ktor's Curl engine hands a finished exchange back only after its next
     * `curl_multi_poll`, which waits for another socket or for its timeout (100 ms by default) while any other
     * exchange of the client runs. Here a call held open by the server keeps one exchange running: each call after it
     * must come back in a few milliseconds, never a poll timeout.
     */
    @Test
    fun aFinishedCallComesBackAtOnceWhileAnotherCallOfTheClientIsOpen() = runBlocking {
        configureHttpClients()
        val heldIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = serverEngine(port = 0, host = LOOPBACK) {
            routing {
                get("/held") {
                    heldIn.complete(Unit)
                    release.await()
                    call.respondText("held")
                }
                get("/at-once") { call.respondText("at once") }
            }
        }.start(wait = false)
        val base = "http://$LOOPBACK:${server.engine.resolvedConnectors().single().port}"
        val client = HttpClient(Curl)
        try {
            withTimeout(30.seconds) {
                coroutineScope {
                    val held = async { client.get("$base/held").bodyAsText() }
                    heldIn.await()
                    val took = measureTime {
                        repeat(CALLS) { assertEquals("at once", client.get("$base/at-once").bodyAsText()) }
                    }
                    release.complete(Unit)
                    assertEquals("held", held.await())
                    // Ktor's default poll alone costs 100 ms a call here; a few milliseconds each are expected.
                    assertTrue(took < 25.milliseconds * CALLS, "$CALLS calls took $took while another call was open")
                }
            }
        } finally {
            release.complete(Unit)
            client.close()
            server.stop(0, STOP_TIMEOUT_MILLIS)
        }
    }

    private companion object {
        const val CALLS = 20
        const val LOOPBACK = "127.0.0.1"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
