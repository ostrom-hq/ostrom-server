// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerEngineTest {
    /**
     * chart-load 41612342204: after a crash under load, the restarted container failed on EADDRINUSE, a second crash.
     * The old connections wait in TIME_WAIT on the port, in the pod's network namespace that outlives the container.
     * Here the server closes a `Connection: close` exchange, then a new engine binds the same port at once.
     */
    @Test
    fun aRestartedServerBindsItsPortWhileTheOldConnectionsWait() = runBlocking {
        val first = serverEngine(port = 0, host = LOOPBACK) { routing { get("/") { call.respondText("ok") } } }
            .start(wait = false)
        val port = first.engine.resolvedConnectors().single().port
        val client = HttpClient(CIO)
        try {
            val answer = client.get("http://$LOOPBACK:$port/") { header(HttpHeaders.Connection, "close") }
            assertEquals("ok", answer.bodyAsText())
        } finally {
            first.stop(0, STOP_TIMEOUT_MILLIS)
        }
        val second = serverEngine(port = port, host = LOOPBACK) {
            routing { get("/") { call.respondText("again") } }
        }.start(wait = false)
        try {
            assertEquals(port, second.engine.resolvedConnectors().single().port)
            assertEquals("again", client.get("http://$LOOPBACK:$port/").bodyAsText())
        } finally {
            client.close()
            second.stop(0, STOP_TIMEOUT_MILLIS)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
