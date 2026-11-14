// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Relays the terminal of each cloud session between its runner (the PTY in the pod) and the members attached to
 * it (CLI `ostrom attach`, `ostrom claude --cloud`, the web console).
 *
 * One room per session: at most one runner link (a newer epoch replaces an older one, an older epoch is refused),
 * any number of client links, and a scrollback of the last [scrollbackBytes] bytes of output that a new client
 * receives first. A client that does not keep up (its queue of [clientQueue] frames is full) is dropped rather
 * than slowing the others or the runner. The relay lives in the memory of the replica holding the runner's socket;
 * a member attached on another replica is relayed to it as a hop client ([connectHopClient], `TerminalHosts`).
 *
 * @param scrollbackBytes The scrollback kept per session.
 * @param clientQueue Frames queued per client before it is dropped.
 */
@Suppress("TooManyFunctions") // One function per event of the two sides, each under the room's lock.
class TerminalRelay(
    private val scrollbackBytes: Int = DEFAULT_SCROLLBACK_BYTES,
    private val clientQueue: Int = DEFAULT_CLIENT_QUEUE,
) {
    private val mutex = Mutex()
    private val rooms = mutableMapOf<Uuid, Room>()

    /** A frame for a client. */
    sealed interface ClientBound {
        /** Terminal output. */
        class Output(val bytes: ByteArray) : ClientBound

        /** Whether the runner is connected. */
        data class Runner(val connected: Boolean) : ClientBound
    }

    /** A frame for the runner. */
    sealed interface RunnerBound {
        /** Keystrokes. */
        class Input(val bytes: ByteArray) : RunnerBound

        /** The terminal's new size. */
        data class Resize(val columns: Int, val rows: Int) : RunnerBound
    }

    /**
     * A runner's link.
     *
     * @property epoch Its execution.
     */
    class RunnerLink internal constructor(val epoch: Long, capacity: Int) {
        /** What to send to the runner; closed when another runner replaces it. */
        val outbound = Channel<RunnerBound>(capacity)
    }

    /**
     * A client's link.
     *
     * @property hop Whether it is another replica relaying its own client: dropped as soon as the runner is not here.
     */
    class ClientLink internal constructor(capacity: Int, val hop: Boolean = false) {
        /** What to send to the client; closed when the client is dropped for not keeping up, or for [moved]. */
        val outbound = Channel<ClientBound>(capacity)

        /** Whether this hop client was dropped because the runner left, not for falling behind. */
        @Volatile
        var moved: Boolean = false
            internal set
    }

    private class Room(scrollbackBytes: Int) {
        var runner: RunnerLink? = null
        var lastEpoch = -1L
        val clients = mutableSetOf<ClientLink>()
        val scrollback = Scrollback(scrollbackBytes)
    }

    /**
     * Connects a runner.
     *
     * @param sessionId The session.
     * @param epoch The runner's execution.
     *
     * @return Its link, or null when a newer execution already connected.
     */
    suspend fun connectRunner(sessionId: Uuid, epoch: Long): RunnerLink? = mutex.withLock {
        val room = rooms.getOrPut(sessionId) { Room(scrollbackBytes) }
        if (epoch < room.lastEpoch) return@withLock null
        room.runner?.let { it.outbound.close() }
        room.lastEpoch = epoch
        RunnerLink(epoch, RUNNER_QUEUE).also {
            room.runner = it
            room.broadcast(ClientBound.Runner(true))
        }
    }

    /**
     * Disconnects a runner; nothing happens if a newer one replaced it.
     *
     * @param sessionId The session.
     * @param link The runner's link.
     *
     * @return Whether it was the session's runner until now (false when another replaced it).
     */
    suspend fun disconnectRunner(sessionId: Uuid, link: RunnerLink): Boolean = mutex.withLock {
        val room = rooms[sessionId]?.takeIf { it.runner === link } ?: return@withLock false
        link.outbound.close()
        room.runner = null
        room.broadcast(ClientBound.Runner(false))
        room.dropIfIdle(sessionId)
        true
    }

    /**
     * Relays the runner's output to the clients and the scrollback.
     *
     * @param sessionId The session.
     * @param link The runner's link; output of a replaced runner is ignored.
     * @param bytes The output.
     */
    suspend fun output(sessionId: Uuid, link: RunnerLink, bytes: ByteArray) = mutex.withLock {
        val room = rooms[sessionId]?.takeIf { it.runner === link } ?: return@withLock
        room.scrollback.append(bytes)
        room.broadcast(ClientBound.Output(bytes))
    }

    /**
     * Connects a client. Its queue starts with the scrollback and the runner's state.
     *
     * @param sessionId The session.
     *
     * @return Its link.
     */
    suspend fun connectClient(sessionId: Uuid): ClientLink = mutex.withLock {
        rooms.getOrPut(sessionId) { Room(scrollbackBytes) }.join(ClientLink(clientQueue))
    }

    private fun Room.join(link: ClientLink): ClientLink {
        val history = scrollback.snapshot()
        if (history.isNotEmpty()) link.outbound.trySend(ClientBound.Output(history))
        link.outbound.trySend(ClientBound.Runner(runner != null))
        clients += link
        return link
    }

    /**
     * Connects another replica relaying its member to this one's runner. Its queue starts with the scrollback and the
     * runner's state; it is dropped (its queue closed) once the runner leaves, so that the other replica looks again
     * for where the runner is.
     *
     * @param sessionId The session.
     *
     * @return Its link, or null when the runner is not here.
     */
    suspend fun connectHopClient(sessionId: Uuid): ClientLink? = mutex.withLock {
        val room = rooms[sessionId]?.takeIf { it.runner != null } ?: return@withLock null
        room.join(ClientLink(clientQueue, hop = true))
    }

    /**
     * Disconnects a client.
     *
     * @param sessionId The session.
     * @param link The client's link.
     */
    suspend fun disconnectClient(sessionId: Uuid, link: ClientLink) = mutex.withLock {
        val room = rooms[sessionId] ?: return@withLock
        room.clients -= link
        link.outbound.close()
        room.dropIfIdle(sessionId)
    }

    /**
     * Sends keystrokes to the runner.
     *
     * @param sessionId The session.
     * @param bytes The keystrokes.
     *
     * @return Whether a runner took them.
     */
    suspend fun input(sessionId: Uuid, bytes: ByteArray): Boolean = toRunner(sessionId, RunnerBound.Input(bytes))

    /**
     * Resizes the runner's terminal.
     *
     * @param sessionId The session.
     * @param columns The columns.
     * @param rows The rows.
     *
     * @return Whether a runner took it.
     */
    suspend fun resize(sessionId: Uuid, columns: Int, rows: Int): Boolean =
        toRunner(sessionId, RunnerBound.Resize(columns, rows))

    /**
     * Whether the session's runner is connected to this replica.
     *
     * @param sessionId The session.
     *
     * @return True while its socket is open here.
     */
    suspend fun runnerHere(sessionId: Uuid): Boolean = mutex.withLock { rooms[sessionId]?.runner != null }

    /**
     * The number of rooms, runners and clients, for tests and metrics.
     *
     * @return Rooms, connected runners, connected clients.
     */
    suspend fun counts(): Triple<Int, Int, Int> = mutex.withLock {
        Triple(rooms.size, rooms.values.count { it.runner != null }, rooms.values.sumOf { it.clients.size })
    }

    private suspend fun toRunner(sessionId: Uuid, frame: RunnerBound): Boolean = mutex.withLock {
        val runner = rooms[sessionId]?.runner ?: return@withLock false
        runner.outbound.trySend(frame).isSuccess
    }

    private fun Room.broadcast(frame: ClientBound) {
        // A hop client is told nothing of a runner gone: it is dropped, and its replica looks for the runner again.
        if (frame == ClientBound.Runner(false)) clients.filter { it.hop }.forEach { it.moved = true }
        val slow = clients.filter { it.moved || !it.outbound.trySend(frame).isSuccess }
        slow.forEach {
            it.outbound.close()
            clients -= it
        }
    }

    private fun Room.dropIfIdle(sessionId: Uuid) {
        if (runner == null && clients.isEmpty()) rooms.remove(sessionId)
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** 256 KiB of output: a few screens of a busy agent. */
        const val DEFAULT_SCROLLBACK_BYTES = 256 * 1024
        const val DEFAULT_CLIENT_QUEUE = 256
        private const val RUNNER_QUEUE = 256
    }
}

/**
 * A byte ring that keeps the last [capacity] bytes appended.
 *
 * @param capacity The bytes kept.
 */
class Scrollback(private val capacity: Int) {
    private val buffer = ByteArray(capacity)
    private var start = 0
    private var size = 0

    /**
     * Appends bytes, forgetting the oldest beyond the capacity.
     *
     * @param bytes The bytes.
     */
    fun append(bytes: ByteArray) {
        val kept = if (bytes.size > capacity) bytes.copyOfRange(bytes.size - capacity, bytes.size) else bytes
        for (byte in kept) {
            buffer[(start + size) % capacity] = byte
            if (size < capacity) size++ else start = (start + 1) % capacity
        }
    }

    /**
     * Copies the bytes kept, oldest first.
     *
     * @return The bytes.
     */
    fun snapshot(): ByteArray = ByteArray(size) { buffer[(start + it) % capacity] }
}
