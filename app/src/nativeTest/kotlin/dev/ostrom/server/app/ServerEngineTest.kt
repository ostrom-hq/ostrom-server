// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServerEngineTest {
    /**
     * chart-load 41612342204: after a crash under load, the restarted container failed on EADDRINUSE, a second crash.
     * The old connections wait in TIME_WAIT on the port, in the pod's network namespace that outlives the container.
     * Here the server closes a `Connection: close` exchange first, so its side of it waits in TIME_WAIT: a plain bind
     * of the port fails, then a new engine binds it at once.
     */
    @Test
    fun aRestartedServerBindsItsPortWhileTheOldConnectionsWait() = runBlocking {
        val first = serverEngine(port = 0, host = LOOPBACK) { routing { get("/") { call.respondText("ok") } } }
            .start(wait = false)
        val port = first.engine.resolvedConnectors().single().port
        val selector = SelectorManager(Dispatchers.Default)
        try {
            withTimeout(10.seconds) {
                val socket = aSocket(selector).tcp().connect(LOOPBACK, port)
                try {
                    socket.openWriteChannel(autoFlush = true)
                        .writeStringUtf8("GET / HTTP/1.1\r\nHost: test\r\nConnection: close\r\n\r\n")
                    // The server answers then closes first: TIME_WAIT is on its side.
                    val answer = socket.openReadChannel().readRemaining().readString()
                    assertTrue(answer.startsWith("HTTP/1.1 200"), answer)
                } finally {
                    socket.close()
                }
            }
        } finally {
            first.stop(0, STOP_TIMEOUT_MILLIS)
        }
        try {
            // The listener is closed, yet the port is held: without SO_REUSEADDR the bind fails.
            assertFails("TIME_WAIT holds the port") { aSocket(selector).tcp().bind(LOOPBACK, port).close() }
            val second = serverEngine(port = port, host = LOOPBACK) {
                routing { get("/") { call.respondText("again") } }
            }.start(wait = false)
            try {
                assertEquals(port, second.engine.resolvedConnectors().single().port)
            } finally {
                second.stop(0, STOP_TIMEOUT_MILLIS)
            }
        } finally {
            selector.close()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
