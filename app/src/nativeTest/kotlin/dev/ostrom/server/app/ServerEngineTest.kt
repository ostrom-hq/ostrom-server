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
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerEngineTest {
    /**
     * chart-load 41612342204: after a crash under load, the restarted container failed on EADDRINUSE, a second crash.
     * The old connections wait in TIME_WAIT on the port, in the pod's network namespace that outlives the container.
     * Here the server closes a `Connection: close` exchange first, so its side of it waits in TIME_WAIT, then a new
     * engine binds the same port at once.
     */
    @Test
    fun aRestartedServerBindsItsPortWhileTheOldConnectionsWait() = runBlocking {
        val first = serverEngine(port = 0, host = LOOPBACK) { routing { get("/") { call.respondText("ok") } } }
            .start(wait = false)
        val port = first.engine.resolvedConnectors().single().port
        val selector = SelectorManager(Dispatchers.Default)
        try {
            val socket = aSocket(selector).tcp().connect(LOOPBACK, port)
            try {
                socket.openWriteChannel(autoFlush = true)
                    .writeStringUtf8("GET / HTTP/1.1\r\nHost: test\r\nConnection: close\r\n\r\n")
                // The server answers then closes first: TIME_WAIT is on its side.
                val answer = socket.openReadChannel().readRemaining().readString()
                assertEquals(true, answer.startsWith("HTTP/1.1 200"), answer)
            } finally {
                socket.close()
            }
        } finally {
            selector.close()
        }
        first.stop(0, 0)
        val second = serverEngine(port = port, host = LOOPBACK) { routing { get("/") { call.respondText("again") } } }
            .start(wait = false)
        try {
            assertEquals(port, second.engine.resolvedConnectors().single().port)
        } finally {
            second.stop(0, 0)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
