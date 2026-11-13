// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readLineStrict
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import run.ostrom.server.kernel.network.PinnedConnectProxy
import run.ostrom.server.kernel.network.dialTcp
import run.ostrom.server.kernel.network.isPublicAddress
import run.ostrom.server.kernel.network.isReachableAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The connection check on real sockets (TC-CODE-SEC-1, TC-GWY-17): a client asks a local proxy to `CONNECT` to a name,
 * the proxy resolves it once through a scripted lookup, and a server on the loopback counts who reached it. The
 * policy is the operator's "allow private targets" one where a tunnel has to reach that server, and the public one
 * where a refusal is the point.
 */
class PinnedConnectProxyTest {
    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(30.seconds) { block() } }
    }

    /** A server on the loopback that echoes each line, and counts the connections it accepted. */
    private class Echo(private val server: ServerSocket, private val selector: SelectorManager) {
        val accepted = Channel<Unit>(Channel.UNLIMITED)
        private val scope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val port = (server.localAddress as InetSocketAddress).port

        init {
            scope.launch {
                while (true) {
                    val peer = server.accept()
                    accepted.trySend(Unit)
                    launch {
                        val input = peer.openReadChannel()
                        val output = peer.openWriteChannel(autoFlush = true)
                        while (true) {
                            val line = input.readLineStrict(LIMIT)
                            if (line == null || line == "bye") break
                            output.writeStringUtf8("echo $line\n")
                        }
                        peer.close()
                    }
                }
            }
        }

        fun connections(): Int {
            var count = 0
            while (accepted.tryReceive().isSuccess) count++
            return count
        }

        fun close() {
            scope.cancel()
            server.close()
            selector.close()
        }
    }

    /** One client connection to the proxy: what it answered, and the channels of the tunnel it opened. */
    private class Exchange(
        val socket: Socket,
        val status: String?,
        val input: ByteReadChannel,
        val output: ByteWriteChannel,
    )

    private class Harness(val echo: Echo, val selector: SelectorManager) {
        suspend fun ask(proxyPort: Int, request: String): Exchange {
            val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", proxyPort))
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)
            output.writeStringUtf8(request)
            output.flush()
            val status = input.readLineStrict(LIMIT)
            if (status != null) while (input.readLineStrict(LIMIT).orEmpty().isNotEmpty()) continue
            return Exchange(socket, status, input, output)
        }

        suspend fun connect(proxyPort: Int, authority: String) =
            ask(proxyPort, "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n")
    }

    private suspend fun withHarness(block: suspend Harness.() -> Unit) {
        val selector = SelectorManager(Dispatchers.Default)
        val echoSelector = SelectorManager(Dispatchers.Default)
        val echo = Echo(aSocket(echoSelector).tcp().bind("127.0.0.1", 0), echoSelector)
        try {
            Harness(echo, selector).block()
        } finally {
            echo.close()
            selector.close()
        }
    }

    @Test
    fun aTunnelReachesTheCheckedAddressAndCarriesBytesBothWays() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress, { listOf("127.0.0.1") })
            try {
                val tunnel = connect(proxy.port, "pinned.test:${echo.port}")
                assertEquals("HTTP/1.1 200 Connection established", tunnel.status)
                tunnel.output.writeStringUtf8("hello\n")
                assertEquals("echo hello", tunnel.input.readLineStrict(LIMIT))
                tunnel.output.writeStringUtf8("again\n")
                assertEquals("echo again", tunnel.input.readLineStrict(LIMIT))
                // The client closing its side ends the tunnel for the server, then for the client.
                tunnel.socket.close()
                assertEquals(1, echo.connections())
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun theClientEndingItsSideEndsTheTunnelForTheServerThenForTheClient() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress, { listOf("127.0.0.1") })
            try {
                val tunnel = connect(proxy.port, "pinned.test:${echo.port}")
                tunnel.output.writeStringUtf8("last\n")
                tunnel.output.flushAndClose()
                assertEquals("echo last", tunnel.input.readLineStrict(LIMIT))
                assertNull(tunnel.input.readLineStrict(LIMIT))
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun theSystemLookupAndTheTcpDialAreTheDefaults() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress)
            try {
                val tunnel = connect(proxy.port, "localhost:${echo.port}")
                assertEquals("HTTP/1.1 200 Connection established", tunnel.status)
                tunnel.output.writeStringUtf8("hello\n")
                assertEquals("echo hello", tunnel.input.readLineStrict(LIMIT))
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun onlyTheFirstReachableAddressIsDialled() = realTime {
        withHarness {
            val dialled = mutableListOf<String>()
            val proxy = PinnedConnectProxy.start(
                ::isReachableAddress,
                { listOf("127.0.0.1", "127.0.0.2") },
                { selector, address, port ->
                    dialled += address
                    dialTcp(selector, address, port)
                },
            )
            try {
                val tunnel = connect(proxy.port, "pinned.test:${echo.port}")
                assertEquals("HTTP/1.1 200 Connection established", tunnel.status)
                assertEquals(listOf("127.0.0.1"), dialled)
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun theServerEndingTheConnectionEndsTheTunnel() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress, { listOf("127.0.0.1") })
            try {
                val tunnel = connect(proxy.port, "pinned.test:${echo.port}")
                tunnel.output.writeStringUtf8("bye\n")
                assertNull(tunnel.input.readLineStrict(LIMIT))
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun aPublicNameThatResolvesToAPrivateRangeIsRefusedAndNeverDialled() = realTime {
        withHarness {
            listOf(
                "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1",
                "fe80::1", "fd00:ec2::254", "fc00::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1",
                "2001::1", "2001:db8::1",
            ).forEach { address ->
                val proxy = PinnedConnectProxy.start(::isPublicAddress, { listOf(address) })
                try {
                    val reply = connect(proxy.port, "pinned.test:${echo.port}")
                    assertEquals("HTTP/1.1 403 Forbidden", reply.status, address)
                } finally {
                    proxy.close()
                }
            }
            assertEquals(0, echo.connections())
        }
    }

    @Test
    fun aLiteralPrivateAddressInTheRequestIsRefusedToo() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isPublicAddress, { error("a literal needs no lookup") })
            try {
                val port = echo.port
                listOf("127.0.0.1:$port", "[::1]:$port", "[::ffff:127.0.0.1]:$port", "10.0.0.1:443")
                    .forEach { assertEquals("HTTP/1.1 403 Forbidden", connect(proxy.port, it).status, it) }
                assertEquals(0, echo.connections())
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun aNameWithOnePrivateAnswerAmongPublicOnesIsRefusedWhole() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isPublicAddress, { listOf("93.184.216.34", "10.0.0.1") })
            try {
                assertEquals("HTTP/1.1 403 Forbidden", connect(proxy.port, "pinned.test:443").status)
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun aNameThatChangesItsAnswerIsResolvedOncePerTunnelAndDialledOnTheCheckedAnswer() = realTime {
        withHarness {
            // DNS rebinding: the first answer is the server (allowed), the next ones the metadata address.
            var lookups = 0
            val proxy = PinnedConnectProxy.start(::isReachableAddress, {
                if (lookups++ == 0) listOf("127.0.0.1") else listOf("169.254.169.254")
            })
            try {
                val first = connect(proxy.port, "rebind.test:${echo.port}")
                assertEquals("HTTP/1.1 200 Connection established", first.status)
                first.output.writeStringUtf8("one\n")
                assertEquals("echo one", first.input.readLineStrict(LIMIT))
                // One lookup served the check and the connection of that tunnel.
                assertEquals(1, lookups)
                assertEquals("HTTP/1.1 403 Forbidden", connect(proxy.port, "rebind.test:${echo.port}").status)
                assertEquals(2, lookups)
                assertEquals(1, echo.connections())
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun theFirstReachableAddressIsDialledAndAnUnreachableOneFallsThroughToTheNext() = realTime {
        withHarness {
            val dialled = mutableListOf<String>()
            val proxy = PinnedConnectProxy.start(
                ::isReachableAddress,
                { listOf("10.9.9.9", "127.0.0.1") },
                { selector, address, port ->
                    dialled += address
                    if (address == "10.9.9.9") throw IOException("unreachable") else dialTcp(selector, address, port)
                },
            )
            try {
                val tunnel = connect(proxy.port, "pinned.test:${echo.port}")
                assertEquals("HTTP/1.1 200 Connection established", tunnel.status)
                assertEquals(listOf("10.9.9.9", "127.0.0.1"), dialled)
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun aHostWithNoReachableAddressClosesTheConnectionWithoutAnAnswer() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(
                ::isReachableAddress,
                { listOf("127.0.0.1", "127.0.0.2") },
                { _, _, _ -> throw IOException("refused") },
            )
            try {
                assertNull(connect(proxy.port, "pinned.test:443").status)
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun aHostThatDoesNotResolveClosesTheConnectionWithoutAnAnswer() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isPublicAddress, { throw IOException("no such host") })
            try {
                assertNull(connect(proxy.port, "pinned.test:443").status)
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun aCancelledDialEndsTheTunnelQuietly() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress, { listOf("127.0.0.1") }, { _, _, _ ->
                throw CancellationException("cancelled")
            })
            try {
                assertNull(connect(proxy.port, "pinned.test:443").status)
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun anythingButAWellFormedConnectIsMethodNotAllowed() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress, { listOf("127.0.0.1") })
            try {
                listOf(
                    // A plain proxied request is no way to a plain HTTP host.
                    "GET http://pinned.test:${echo.port}/ HTTP/1.1\r\nHost: pinned.test\r\n\r\n",
                    "CONNECT\r\n\r\n",
                    "CONNECT pinned.test:${echo.port}\r\n\r\n",
                    "CONNECT :${echo.port} HTTP/1.1\r\n\r\n",
                    "CONNECT pinned.test HTTP/1.1\r\n\r\n",
                    "CONNECT pinned.test:abc HTTP/1.1\r\n\r\n",
                    "CONNECT pinned.test:0 HTTP/1.1\r\n\r\n",
                    "CONNECT pinned.test:65536 HTTP/1.1\r\n\r\n",
                ).forEach { assertEquals("HTTP/1.1 405 Method Not Allowed", ask(proxy.port, it).status, it) }
                assertEquals(0, echo.connections())
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun aClientThatLeavesBeforeItsRequestIsOverIsForgotten() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress, { listOf("127.0.0.1") })
            try {
                val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", proxy.port))
                socket.close()
                // The proxy still serves the next client.
                val tunnel = connect(proxy.port, "pinned.test:${echo.port}")
                assertEquals("HTTP/1.1 200 Connection established", tunnel.status)
            } finally {
                proxy.close()
            }
        }
    }

    @Test
    fun closingTheProxyEndsItsTunnels() = realTime {
        withHarness {
            val proxy = PinnedConnectProxy.start(::isReachableAddress, { listOf("127.0.0.1") })
            val tunnel = connect(proxy.port, "pinned.test:${echo.port}")
            assertEquals("HTTP/1.1 200 Connection established", tunnel.status)
            proxy.close()
            assertNull(tunnel.input.readLineStrict(LIMIT))
        }
    }

    private companion object {
        const val LIMIT = 8192L
    }
}
