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
 * receives first. A client that comes back with its [Cursor] (a member whose terminal moved between replicas) gets
 * only what it has not seen yet, as TeamCode's attach resumes after the last event it printed, so nothing is printed
 * twice; when the room is another one, or its scrollback no longer reaches that far (TeamCode's `gap`), the terminal
 * is reset ([RESET]) before the scrollback, so the screen is drawn again rather than doubled. A client that does not
 * keep up (its queue of [clientQueue] frames is full) is dropped rather
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

        /**
         * Where the client now is in the room's output: what it was sent before is up to [cursor], what follows is
         * counted from it. Never sent to a member: their replica keeps it to come back with.
         *
         * @property cursor The room and the bytes of its output sent so far.
         */
        data class Joined(val cursor: Cursor) : ClientBound
    }

    /**
     * A place in a room's output.
     *
     * @property room The room: one stay of a session's terminal in a replica's memory.
     * @property offset The bytes of output the room relayed before this place.
     */
    data class Cursor(val room: Uuid, val offset: Long) {
        /** `<room>.<offset>`, as a hop's `after` query parameter. */
        override fun toString(): String = "$room.$offset"

        /** Reading a cursor. */
        companion object {
            /**
             * @param text `<room>.<offset>`, or null.
             *
             * @return The cursor, or null when [text] is null or not one.
             */
            fun parse(text: String?): Cursor? {
                val (room, offset) = text?.split('.', limit = 2)?.takeIf { it.size == 2 } ?: return null
                val id = Uuid.parseOrNull(room) ?: return null
                return offset.toLongOrNull()?.takeIf { it >= 0 }?.let { Cursor(id, it) }
            }
        }
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
        val id = Uuid.random()
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
     * Connects a client. Its queue starts with the scrollback (from [after], or reset first: [join]), where it now is
     * ([ClientBound.Joined]) and the runner's state.
     *
     * @param sessionId The session.
     * @param after Where the client was, when it comes back.
     *
     * @return Its link.
     */
    suspend fun connectClient(sessionId: Uuid, after: Cursor? = null): ClientLink = mutex.withLock {
        rooms.getOrPut(sessionId) { Room(scrollbackBytes) }.join(ClientLink(clientQueue), after)
    }

    /**
     * Adds a client: a new one gets the scrollback; one coming back to this room, what followed its cursor; one coming
     * from another room, or from further back than the scrollback, a terminal reset, then the scrollback.
     */
    private fun Room.join(link: ClientLink, after: Cursor?): ClientLink {
        val history = when {
            after == null -> scrollback.snapshot()
            after.room == id && scrollback.reaches(after.offset) -> scrollback.since(after.offset)
            else -> RESET + scrollback.snapshot()
        }
        if (history.isNotEmpty()) link.outbound.trySend(ClientBound.Output(history))
        link.outbound.trySend(ClientBound.Joined(Cursor(id, scrollback.total)))
        link.outbound.trySend(ClientBound.Runner(runner != null))
        clients += link
        return link
    }

    /**
     * Connects another replica relaying its member to this one's runner. Its queue starts as a client's
     * ([connectClient]); it is dropped (its queue closed) once the runner leaves, so that the other replica looks again
     * for where the runner is.
     *
     * @param sessionId The session.
     * @param after Where the member was, when they come back.
     *
     * @return Its link, or null when the runner is not here.
     */
    suspend fun connectHopClient(sessionId: Uuid, after: Cursor? = null): ClientLink? = mutex.withLock {
        val room = rooms[sessionId]?.takeIf { it.runner != null } ?: return@withLock null
        room.join(ClientLink(clientQueue, hop = true), after)
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

        /** ESC c, the terminal's full reset (RIS): the screen is cleared before a scrollback it may have shown. */
        val RESET: ByteArray = byteArrayOf(0x1b, 'c'.code.toByte())
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

    /** The bytes ever appended. */
    var total: Long = 0
        private set

    /**
     * Appends bytes, forgetting the oldest beyond the capacity.
     *
     * @param bytes The bytes.
     */
    fun append(bytes: ByteArray) {
        total += bytes.size
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

    /**
     * @param offset A count of bytes appended.
     *
     * @return Whether the bytes appended after [offset] are all kept.
     */
    fun reaches(offset: Long): Boolean = offset in total - size..total

    /**
     * Copies the bytes appended after [offset], which must be [reaches].
     *
     * @param offset A count of bytes appended.
     *
     * @return The bytes.
     */
    fun since(offset: Long): ByteArray {
        val count = (total - offset).toInt()
        return ByteArray(count) { buffer[(start + size - count + it) % capacity] }
    }
}
