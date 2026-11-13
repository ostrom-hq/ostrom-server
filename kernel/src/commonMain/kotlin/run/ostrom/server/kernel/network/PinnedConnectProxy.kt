// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readLineStrict
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private const val LOOPBACK = "127.0.0.1"
private const val MAX_TUNNELS = 256
private const val MAX_LINE = 8192L
private const val MAX_PORT = 65535
private const val REQUEST_PARTS = 3
private const val STATUS_OK = 200
private const val STATUS_FORBIDDEN = 403
private const val STATUS_METHOD_NOT_ALLOWED = 405
private val REASONS = mapOf(
    STATUS_OK to "Connection established",
    STATUS_FORBIDDEN to "Forbidden",
    STATUS_METHOD_NOT_ALLOWED to "Method Not Allowed",
)
private val HEAD_DEADLINE = 10.seconds
private val CONNECT_DEADLINE = 10.seconds

/** A TCP connection to an address that was checked; it gives up after ten seconds. */
internal suspend fun dialTcp(selector: SelectorManager, address: String, port: Int): Socket =
    withTimeout(CONNECT_DEADLINE) { aSocket(selector).tcp().connect(InetSocketAddress(address, port)) }

/** [dialTcp] as a value: the default connector (a lambda in a default argument crashes the compiler). */
internal val TCP_DIAL: suspend (SelectorManager, String, Int) -> Socket = ::dialTcp

/**
 * The connection check of a client whose engine cannot replace its DNS resolver: a local HTTP `CONNECT` proxy, on a
 * port of the loopback, that the client is configured to use (Curl on Linux and Darwin on macOS, the engines of the
 * server on Kotlin/Native; Ktor's CIO client has no TLS there). For `CONNECT host:port` it resolves the host once
 * ([pinnedResolver]), has every answer checked, and dials one of the addresses that were checked, then copies bytes
 * both ways. The client keeps the TLS session with the real host, so the SNI, the certificate check and the `Host`
 * header are those of the URL, and the proxy never sees a clear byte. There is no second lookup between the check
 * and the connection (no DNS rebinding), and a redirect to another host opens another tunnel, checked the same way.
 *
 * Anything but `CONNECT` is answered 405, so the proxy reaches no plain HTTP host, and a host with an address the
 * policy refuses is answered 403 without the proxy ever connecting. A host that does not resolve or connect closes
 * the connection with no answer: the client reports a failure, and nothing the network said is echoed.
 */
class PinnedConnectProxy private constructor(
    private val scope: CoroutineScope,
    private val selector: SelectorManager,
    private val server: ServerSocket,
    private val resolver: suspend (String) -> List<String>,
    private val connector: suspend (SelectorManager, String, Int) -> Socket,
    /** The port of the loopback this proxy listens on. */
    val port: Int,
) : AutoCloseable {
    private val tunnels = Semaphore(MAX_TUNNELS)

    private fun accepting() {
        scope.launch {
            while (true) {
                val client = server.accept()
                launch { tunnels.withPermit { serve(client) } }
            }
        }
    }

    // A tunnel's failure (an unresolvable name, a reset, an over-long line) ends that tunnel, never the accept loop.
    private suspend fun serve(client: Socket) {
        try {
            val input = client.openReadChannel()
            val output = client.openWriteChannel(autoFlush = true)
            val target = withTimeoutOrNull(HEAD_DEADLINE) { readConnect(input) }
            if (target == null) {
                answer(output, STATUS_METHOD_NOT_ALLOWED)
            } else {
                open(target.first, target.second, output)?.let { relay(it, input, output) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Nothing the network said is echoed; the client sees the connection close.
        } finally {
            withContext(NonCancellable) { client.close() }
        }
    }

    private suspend fun relay(upstream: Socket, input: ByteReadChannel, output: ByteWriteChannel) {
        try {
            answer(output, STATUS_OK)
            tunnel(input, output, upstream)
        } finally {
            upstream.close()
        }
    }

    /** The checked connection to a host, or null after the 403 (refused) or with no answer (nothing reachable). */
    private suspend fun open(host: String, port: Int, output: ByteWriteChannel): Socket? = try {
        dial(resolver(host), port)
    } catch (_: PinnedDnsRefusal) {
        answer(output, STATUS_FORBIDDEN)
        null
    }

    /** The host and port of a `CONNECT` request, or null for anything else. */
    private suspend fun readConnect(input: ByteReadChannel): Pair<String, Int>? {
        val line = input.readLineStrict(MAX_LINE)?.split(' ').orEmpty()
        // The headers of a CONNECT request carry nothing the tunnel needs.
        var header = input.readLineStrict(MAX_LINE)
        while (!header.isNullOrEmpty()) header = input.readLineStrict(MAX_LINE)
        val authority = line.getOrNull(1).orEmpty()
        val host = authority.substringBeforeLast(':').removeSurrounding("[", "]")
        val port = authority.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..MAX_PORT }
        val connect = line.size == REQUEST_PARTS && line[0] == "CONNECT"
        return if (connect && host.isNotEmpty() && port != null) host to port else null
    }

    /** Every address was checked; the first that connects is the one dialled. Null when none does. */
    private suspend fun dial(addresses: List<String>, port: Int): Socket? {
        for (address in addresses) {
            val socket = attempt(address, port)
            if (socket != null) return socket
        }
        return null
    }

    private suspend fun attempt(address: String, port: Int): Socket? = try {
        connector(selector, address, port)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Unreachable (an IPv6 address on a host without IPv6, say): the next one.
        null
    }

    private suspend fun answer(output: ByteWriteChannel, status: Int) {
        output.writeStringUtf8("HTTP/1.1 $status ${REASONS.getValue(status)}\r\nConnection: close\r\n\r\n")
        output.flush()
    }

    private suspend fun tunnel(input: ByteReadChannel, output: ByteWriteChannel, upstream: Socket) {
        val upstreamInput = upstream.openReadChannel()
        val upstreamOutput = upstream.openWriteChannel(autoFlush = true)
        // When one direction ends, the write end of the other side closes, so its peer sees the end; the tunnel is
        // over when both directions are. A failure of one cancels the other.
        coroutineScope {
            launch {
                input.copyTo(upstreamOutput)
                upstreamOutput.flushAndClose()
            }
            launch {
                upstreamInput.copyTo(output)
                output.flushAndClose()
            }
        }
    }

    /** Stops accepting and ends every tunnel. */
    override fun close() {
        scope.cancel()
        server.close()
        selector.close()
    }

    /** Starting the proxy. */
    companion object {
        /**
         * Starts a proxy on a free port of the loopback.
         *
         * @param allowed Whether an address may be dialled ([isPublicAddress], [isReachableAddress]).
         * @param lookup The system lookup, replaced by tests.
         * @param connector Opens the connection to a checked address, replaced by tests.
         *
         * @return The running proxy; the caller closes it.
         */
        suspend fun start(
            allowed: (ByteArray) -> Boolean,
            lookup: (String) -> List<String> = ::systemLookup,
            connector: suspend (SelectorManager, String, Int) -> Socket = TCP_DIAL,
        ): PinnedConnectProxy {
            // A tunnel that fails (a name that does not resolve, a peer that resets) ends alone, and never the process.
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
            val selector = SelectorManager(blockingDispatcher)
            val server = aSocket(selector).tcp().bind(LOOPBACK, 0)
            val port = (server.localAddress as InetSocketAddress).port
            return PinnedConnectProxy(scope, selector, server, pinnedResolver(allowed, lookup), connector, port)
                .also { it.accepting() }
        }
    }
}
