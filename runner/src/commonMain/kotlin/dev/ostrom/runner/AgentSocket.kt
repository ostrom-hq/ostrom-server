// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.UnixSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The Unix socket between the runner and the agent host, on a volume both containers of the session pod mount. The
 * host listens; the runner connects, one connection per terminal.
 */
object AgentSocket {
    /** Where the socket is when `OSTROM_AGENT_SOCKET` is not set. */
    const val DEFAULT_PATH = "/run/ostrom/agent.sock"

    /**
     * Connects to the host.
     *
     * @param selector The socket selector.
     * @param path The socket's path.
     *
     * @return The connection.
     */
    suspend fun connect(selector: SelectorManager, path: String): AgentConnection =
        SocketConnection(aSocket(selector).tcp().connect(UnixSocketAddress(path)))

    /**
     * Listens and serves each connection with [host], until cancelled. A socket file left by a previous host is
     * replaced.
     *
     * @param selector The socket selector.
     * @param path The socket's path.
     * @param host Serves the connections.
     * @param log Writes a line to the container's log.
     * @param ready Called once the socket listens, with its path (the native host opens it to the runner's group).
     */
    suspend fun serve(
        selector: SelectorManager,
        path: String,
        host: AgentHost,
        log: (String) -> Unit,
        ready: (String) -> Unit,
    ) = listen(selector, path, "agent host", log, ready) { input, output ->
        try {
            host.serve(input, output)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("terminal connection failed: ${error::class.simpleName}")
        }
    }

    /**
     * Listens on a Unix socket and serves each connection with [handle] in its own coroutine, until cancelled; the
     * connection is closed after it. A socket file left by a previous process is replaced.
     *
     * @param selector The socket selector.
     * @param path The socket's path.
     * @param name What listens, for the log.
     * @param log Writes a line to the container's log.
     * @param ready Called once the socket listens, with its path.
     * @param handle Serves one connection; it catches its own failures.
     */
    @Suppress("LongParameterList") // The socket, its log and the handler, named at the call sites.
    suspend fun listen(
        selector: SelectorManager,
        path: String,
        name: String,
        log: (String) -> Unit,
        ready: (String) -> Unit,
        handle: suspend (ByteReadChannel, ByteWriteChannel) -> Unit,
    ) {
        SystemFileSystem.delete(Path(path), mustExist = false)
        val server = aSocket(selector).tcp().bind(UnixSocketAddress(path))
        try {
            ready(path)
            log("$name listening")
            coroutineScope {
                while (true) {
                    val connection = SocketConnection(server.accept())
                    launch {
                        try {
                            handle(connection.input, connection.output)
                        } finally {
                            connection.close()
                        }
                    }
                }
            }
        } finally {
            server.close()
        }
    }

    private class SocketConnection(private val socket: Socket) : AgentConnection {
        override val input: ByteReadChannel = socket.openReadChannel()
        override val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = false)

        override fun close() = socket.close()
    }
}
