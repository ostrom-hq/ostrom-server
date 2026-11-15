// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import run.ostrom.server.kernel.network.PinnedDnsRefusal
import run.ostrom.server.kernel.network.outboundHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The client of a URL someone else supplied (TC-CODE-SEC-1, TC-GWY-17), against real receivers on the loopback. */
class OutboundHttpClientTest {
    private fun realTime(block: suspend () -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(30.seconds) { block() } }
    }

    /** A receiver on the loopback; [onRequest] gets the `Host` header of each request. */
    private fun serve(
        status: Int = 200,
        location: String? = null,
        onRequest: (host: String) -> Unit = {},
    ): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            onRequest(exchange.requestHeaders.getFirst("Host").orEmpty())
            location?.let { exchange.responseHeaders.add("Location", it) }
            val body = "ok".encodeToByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server to server.address.port
    }

    private val loopback = { _: String -> listOf(InetAddress.getLoopbackAddress()) }

    @Test
    fun aRedirectIsNotFollowedSoItsTargetIsNeverTried() = realTime {
        var hits = 0
        val (target, targetPort) = serve { hits++ }
        val (server, port) = serve(302, "http://127.0.0.1:$targetPort/")
        val client = outboundHttpClient(allowPrivate = true)
        try {
            val response = client.get("http://127.0.0.1:$port/")
            assertEquals(HttpStatusCode.Found, response.status)
            assertContains(response.headers[HttpHeaders.Location].orEmpty(), "$targetPort")
            assertEquals(0, hits)
        } finally {
            client.close()
            server.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun aPublicNameThatResolvesToALoopbackAddressIsRefusedAtTheConnection() = realTime {
        val hosts = mutableListOf<String>()
        val (server, port) = serve { hosts += it }
        var lookups = 0
        val client = outboundHttpClient(lookup = { lookups++; loopback(it) })
        try {
            val failure = assertFailsWith<Exception> { client.get("http://rebind.example.test:$port/hook") }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is PinnedDnsRefusal }, "$failure")
            assertTrue(hosts.isEmpty(), "The refused answer is never dialled")
            assertEquals(1, lookups)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun theConnectionDialsTheAddressItCheckedWithTheNameKeptInHost() = realTime {
        val hosts = mutableListOf<String>()
        val (server, port) = serve { hosts += it }
        var lookups = 0
        val client = outboundHttpClient(allowPrivate = true, lookup = { lookups++; loopback(it) })
        try {
            assertEquals("ok", client.get("http://receiver.example.test:$port/hook").bodyAsText())
            assertEquals(listOf("receiver.example.test:$port"), hosts)
            assertEquals(1, lookups, "One lookup per connection, the one that was checked")
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun anOperatorWhoAllowsPrivateTargetsStillCannotReachMetadata() = realTime {
        var lookups = 0
        val client = outboundHttpClient(
            allowPrivate = true,
            lookup = { lookups++; listOf(InetAddress.getByName("169.254.169.254")) },
        )
        try {
            val failure = assertFailsWith<Exception> { client.get("https://metadata.test/") }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is PinnedDnsRefusal }, "$failure")
            assertEquals(1, lookups)
        } finally {
            client.close()
        }
    }

    @Test
    fun theCallerCanAddItsOwnSettingsLast() = realTime {
        var configured = false
        outboundHttpClient(configure = { configured = true }).close()
        assertEquals(true, configured)
    }
}
