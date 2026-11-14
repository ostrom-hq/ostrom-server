// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.infrastructure.TerminalRelay
import run.ostrom.server.runners.infrastructure.TerminalReplica
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** The member's frames read ahead while the terminal moves from one place to another. */
private const val CLIENT_FRAMES = 64

/**
 * Attaches a member, wherever the runner's socket is (TC-REP-1): in this replica's room when the runner is here, or
 * when no replica holds it (the member then waits here, and is moved once another replica takes the runner); else
 * through the replica that holds it ([TerminalReplica.forwarder]), as a hop client. When the runner moves, the member
 * is told it left, then attached where it now is, on the same socket, from where it was in the output
 * ([TerminalRelay.Cursor]): nothing is printed twice. With one replica, every terminal is here.
 *
 * @param dependencies The relay, the tickets and the replica's place.
 * @param caller The member, already allowed on the session.
 * @param id The session.
 */
internal suspend fun DefaultWebSocketServerSession.attachMember(
    dependencies: TerminalRoutesDependencies,
    caller: Caller,
    id: Uuid,
) = coroutineScope {
    val fromClient = Channel<Frame>(CLIENT_FRAMES)
    val reader = launch {
        try {
            for (frame in incoming) fromClient.send(frame)
        } finally {
            fromClient.close()
        }
    }
    val attachment = MemberAttachment(this@attachMember, dependencies, caller, id, fromClient)
    var ended: MemberAttachment.Ended = MemberAttachment.Ended.LookAgain
    while (ended == MemberAttachment.Ended.LookAgain) ended = attachment.once()
    if (ended is MemberAttachment.Ended.Closed) close(ended.reason)
    reader.cancel()
}

/**
 * One member's socket, attached to one place of the terminal after another.
 *
 * @param socket The member's socket.
 * @param dependencies The relay, the tickets and the replica's place.
 * @param caller The member.
 * @param id The session.
 * @param fromClient The member's frames, read once for every place.
 */
internal class MemberAttachment(
    private val socket: DefaultWebSocketServerSession,
    private val dependencies: TerminalRoutesDependencies,
    private val caller: Caller,
    private val id: Uuid,
    private val fromClient: ReceiveChannel<Frame>,
) {
    private val relay = dependencies.relay
    private val replica = dependencies.replica

    /** Where the member is in the output of the room they are attached to (or were last), once they joined one. */
    internal var cursor: TerminalRelay.Cursor? = null
        private set

    /** How the member's attachment to one place of the terminal (this replica's room, or another replica) ended. */
    sealed interface Ended {
        /** The member closed their socket. */
        data object ClientLeft : Ended

        /** The member's socket is closed with this reason. */
        data class Closed(val reason: CloseReason) : Ended

        /** The runner is not where it was looked for: the member is told no runner is there; it is looked for again. */
        data object LookAgain : Ended
    }

    /**
     * Attaches the member once where the runner is now: here (or nowhere yet), or on the replica that holds it. When it
     * is not found there, the member is told no runner is connected, and it is looked for again a moment later.
     *
     * @return How it ended.
     */
    suspend fun once(): Ended {
        val replica = replica ?: return local(null)
        val host = replica.hosts.host(caller.orgId, id)
        val ended = if (host == null || host == replica.url) local(replica) else remote(replica, host)
        if (ended == Ended.LookAgain) {
            socket.send(controlFrame(TerminalControl.Runner(false)))
            delay(replica.lookEvery)
        }
        return ended
    }

    /**
     * The member in this replica's room, until they leave, fall behind, send an unknown frame, or (while no runner is
     * here) another [replica] takes the runner.
     */
    private suspend fun local(replica: TerminalReplica?): Ended {
        val link = relay.connectClient(id, cursor)
        try {
            return coroutineScope {
                val ended = CompletableDeferred<Ended>()
                launch {
                    // Dropped for falling behind (told so), or cancelled once the attachment ended otherwise.
                    try {
                        forwardToClient(link.outbound, joined = { cursor = it }) { sent(it) }
                    } finally {
                        ended.complete(Ended.ClientLeft)
                    }
                }
                launch {
                    for (frame in fromClient) {
                        when (frame) {
                            is Frame.Binary -> {
                                relay.input(id, frame.readBytes())
                                dependencies.activity.touch(id)
                            }
                            is Frame.Text -> if (!resized(relay, id, frame.readText())) {
                                ended.complete(Ended.Closed(UNKNOWN_FRAME))
                            }
                            else -> Unit
                        }
                    }
                    ended.complete(Ended.ClientLeft)
                }
                if (replica != null) {
                    launch {
                        while (true) {
                            delay(replica.lookEvery)
                            if (!relay.runnerHere(id) && heldElsewhere(replica)) ended.complete(Ended.LookAgain)
                        }
                    }
                }
                ended.await().also { coroutineContext.cancelChildren() }
            }
        } finally {
            relay.disconnectClient(id, link)
        }
    }

    /** Whether another replica than this one holds the session's runner now. */
    private suspend fun heldElsewhere(replica: TerminalReplica): Boolean {
        val host = replica.hosts.host(caller.orgId, id)
        return host != null && host != replica.url
    }

    /**
     * The member relayed through the replica at [host], which holds the runner, on a ticket of theirs: frames are
     * passed both ways as they are. A failure to reach it, or its socket ending without a refusal of the protocol, is a
     * look again.
     */
    private suspend fun remote(replica: TerminalReplica, host: String): Ended {
        val ticket = dependencies.tickets.issue(caller, id).value
        var ended: Ended = Ended.LookAgain
        try {
            replica.forwarder.forward(host, id, ticket, cursor) { other ->
                ended = coroutineScope {
                    val result = CompletableDeferred<Ended>()
                    launch {
                        for (frame in other.incoming) {
                            // The other replica's cursor is kept here, never passed on to the member.
                            val joined = (frame as? Frame.Text)?.let { joinedIn(it.readText()) }
                            if (joined != null) cursor = joined else sent(frame)
                        }
                        result.complete(afterHop(other.closeReason.await()))
                    }
                    launch {
                        for (frame in fromClient) {
                            other.send(frame)
                            if (frame is Frame.Binary) dependencies.activity.touch(id)
                        }
                        result.complete(Ended.ClientLeft)
                    }
                    result.await().also { coroutineContext.cancelChildren() }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The other replica is gone or unreachable: the runner is looked for again.
        }
        return ended
    }

    /** Sends a frame to the member, counting the output in their [cursor]. */
    private suspend fun sent(frame: Frame) {
        if (frame is Frame.Binary) cursor = cursor?.let { it.copy(offset = it.offset + frame.data.size) }
        socket.send(frame)
    }

    /** Readers of the hops' ends. */
    companion object {
        /**
         * The cursor a hop's text frame carries ([TerminalControl.Joined]).
         *
         * @param text The frame's text.
         *
         * @return The cursor, or null for any other frame.
         */
        fun joinedIn(text: String): TerminalRelay.Cursor? {
            val control = try {
                TerminalJson.decodeFromString(TerminalControl.serializer(), text)
            } catch (_: IllegalArgumentException) {
                // SerializationException included: it extends IllegalArgumentException.
                return null
            }
            val joined = control as? TerminalControl.Joined ?: return null
            return TerminalRelay.Cursor.parse("${joined.room}.${joined.offset}")
        }

        /** The first close code of the terminal protocol (4000 + an HTTP status). */
        private const val PROTOCOL_CODES: Short = 4000

        /**
         * What the end of a hop socket means for the member: a refusal of the protocol is theirs (an unknown frame,
         * falling behind, a session now over), anything else (the runner moved, the replica stopped) is a look again.
         *
         * @param reason The hop socket's close.
         *
         * @return How the member's attachment there ended.
         */
        fun afterHop(reason: CloseReason?): Ended =
            if (reason == null || reason.code == TerminalCloseCodes.MOVED || reason.code < PROTOCOL_CODES) {
                Ended.LookAgain
            } else {
                Ended.Closed(reason)
            }
    }
}

/**
 * Another replica relaying its member to the runner here: attached to this room only, from where the member was
 * ([after]), told where it now is ([TerminalControl.Joined]), and closed with [TerminalCloseCodes.MOVED] as soon as
 * the runner is not here, so that the other replica looks again.
 *
 * @param relay The relay.
 * @param id The session.
 * @param after Where the member was in the output, when they come back.
 */
internal suspend fun DefaultWebSocketServerSession.attachHop(
    relay: TerminalRelay,
    id: Uuid,
    after: TerminalRelay.Cursor?,
) {
    val link = relay.connectHopClient(id, after) ?: return close(MOVED)
    try {
        coroutineScope {
            val writer = launch {
                forwardToClient(
                    link.outbound,
                    { hopClosing(link) },
                    { send(controlFrame(TerminalControl.Joined(it.room.toString(), it.offset))) },
                ) { send(it) }
            }
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> relay.input(id, frame.readBytes())
                    is Frame.Text -> if (!resized(relay, id, frame.readText())) {
                        close(UNKNOWN_FRAME)
                        break
                    }
                    else -> Unit
                }
            }
            writer.cancel()
        }
    } finally {
        relay.disconnectClient(id, link)
    }
}

/**
 * The close of a hop client the relay dropped.
 *
 * @param link Its link.
 *
 * @return [TerminalCloseCodes.MOVED] when the runner left, else falling behind.
 */
internal fun hopClosing(link: TerminalRelay.ClientLink): CloseReason = if (link.moved) MOVED else TOO_SLOW_HOP

private val MOVED = CloseReason(TerminalCloseCodes.MOVED, "The runner is not on this replica.")
private val TOO_SLOW_HOP = CloseReason(TerminalCloseCodes.TOO_SLOW, "This terminal did not keep up with the output.")
