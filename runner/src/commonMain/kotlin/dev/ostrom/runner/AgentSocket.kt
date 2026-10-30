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
    ) {
        SystemFileSystem.delete(Path(path), mustExist = false)
        val server = aSocket(selector).tcp().bind(UnixSocketAddress(path))
        try {
            ready(path)
            log("agent host listening")
            coroutineScope {
                while (true) {
                    val connection = SocketConnection(server.accept())
                    launch { serveOne(host, connection, log) }
                }
            }
        } finally {
            server.close()
        }
    }

    private suspend fun serveOne(host: AgentHost, connection: AgentConnection, log: (String) -> Unit) {
        try {
            host.serve(connection.input, connection.output)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("terminal connection failed: ${error::class.simpleName}")
        } finally {
            connection.close()
        }
    }

    private class SocketConnection(private val socket: Socket) : AgentConnection {
        override val input: ByteReadChannel = socket.openReadChannel()
        override val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = false)

        override fun close() = socket.close()
    }
}
