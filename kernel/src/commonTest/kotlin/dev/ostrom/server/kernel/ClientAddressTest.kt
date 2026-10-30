// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.http.ClientAddress
import dev.ostrom.server.kernel.network.TrustedProxies
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.engine.cio.CIO as ClientCIO

/**
 * The client address on real sockets: two clients behind a proxy on 127.0.0.1 (S2-02). The test engine of Ktor has no
 * peer address, so this test runs CIO on a free port of the loopback.
 */
class ClientAddressTest {
    /** Asks `/origin`, the address the server sees for the call, as a client sending [forwardedFor]. */
    private fun behindAProxy(proxies: TrustedProxies, block: suspend (suspend (String?) -> String) -> Unit) = runTest {
        // On the real clock: the sockets are real.
        withContext(Dispatchers.Default) {
            val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                install(ClientAddress) { this.proxies = proxies }
                routing { get("/origin") { call.respondText(call.request.origin.remoteAddress) } }
            }.startSuspend(wait = false)
            val client = HttpClient(ClientCIO)
            try {
                val port = server.engine.resolvedConnectors().single().port
                block { forwardedFor ->
                    client.get("http://127.0.0.1:$port/origin") {
                        forwardedFor?.let { header("X-Forwarded-For", it) }
                    }.bodyAsText()
                }
            } finally {
                client.close()
                server.stopSuspend(0, 1_000)
            }
        }
    }

    @Test
    fun twoClientsBehindATrustedProxyAreTwoAddresses() = behindAProxy(TrustedProxies.parse("127.0.0.1/32")) { ask ->
        assertEquals("203.0.113.7", ask("203.0.113.7"))
        assertEquals("198.51.100.9", ask("198.51.100.9"))
        // What a client writes itself is to the left of what the proxy appends: it is never believed.
        assertEquals("198.51.100.9", ask("6.6.6.6, 198.51.100.9"))
        assertEquals("127.0.0.1", ask(null), "Without the header, the client is the proxy")
    }

    @Test
    fun anUntrustedPeerIsTheClientWhateverItForwards() = behindAProxy(TrustedProxies.parse("10.0.0.0/8")) { ask ->
        assertEquals("127.0.0.1", ask("203.0.113.7"))
    }

    @Test
    fun withoutTrustedProxiesTheHeaderIsIgnored() = behindAProxy(TrustedProxies.NONE) { ask ->
        assertEquals("127.0.0.1", ask("203.0.113.7"))
    }
}
