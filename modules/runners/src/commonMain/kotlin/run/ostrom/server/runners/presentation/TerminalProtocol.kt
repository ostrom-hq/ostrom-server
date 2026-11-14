// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.infrastructure.TerminalRelay
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.time.Duration.Companion.seconds

/** Installs the WebSockets plugin, unless another module already did. */
internal fun Application.installTerminalWebSockets() {
    if (pluginOrNull(WebSockets) != null) return
    install(WebSockets) {
        pingPeriodMillis = PING_PERIOD.inWholeMilliseconds
        timeoutMillis = PING_TIMEOUT.inWholeMilliseconds
        maxFrameSize = MAX_FRAME_BYTES
    }
}

/**
 * Sends the clients' input and resizes to the runner's socket until a newer runner replaces it (the relay closes
 * its queue), then closes the socket.
 *
 * @param outbound The runner's queue.
 * @param send Sends a frame on the socket.
 */
internal suspend fun forwardToRunner(
    outbound: ReceiveChannel<TerminalRelay.RunnerBound>,
    send: suspend (Frame) -> Unit,
) {
    for (frame in outbound) {
        when (frame) {
            is TerminalRelay.RunnerBound.Input -> send(Frame.Binary(true, frame.bytes))
            is TerminalRelay.RunnerBound.Resize -> send(controlFrame(TerminalControl.Resize(frame.columns, frame.rows)))
        }
    }
    send(Frame.Close(CloseReason(TerminalCloseCodes.CONFLICT, NEWER_EXECUTION)))
}

/**
 * Sends the output and the runner's state to a client's socket until the relay drops the client for falling
 * behind (it closes its queue), then closes the socket.
 *
 * @param outbound The client's queue.
 * @param closing The close sent then: falling behind, unless the caller knows better (a hop client moved).
 * @param joined Where the client now is in the room's output: kept by a member's replica, sent on by a hop.
 * @param send Sends a frame on the socket.
 */
internal suspend fun forwardToClient(
    outbound: ReceiveChannel<TerminalRelay.ClientBound>,
    closing: () -> CloseReason = { CloseReason(TerminalCloseCodes.TOO_SLOW, TOO_SLOW) },
    joined: suspend (TerminalRelay.Cursor) -> Unit,
    send: suspend (Frame) -> Unit,
) {
    for (frame in outbound) {
        when (frame) {
            is TerminalRelay.ClientBound.Output -> send(Frame.Binary(true, frame.bytes))
            is TerminalRelay.ClientBound.Runner -> send(controlFrame(TerminalControl.Runner(frame.connected)))
            is TerminalRelay.ClientBound.Joined -> joined(frame.cursor)
        }
    }
    send(Frame.Close(closing()))
}

/** 1 MiB: a pasted file, never more. */
private const val MAX_FRAME_BYTES = 1024L * 1024

private val PING_PERIOD = 20.seconds
private val PING_TIMEOUT = 60.seconds

internal const val NEWER_EXECUTION = "A newer execution holds this terminal."
private const val TOO_SLOW = "This terminal did not keep up with the output: attach again."

/** A text frame of the protocol. */
internal fun controlFrame(control: TerminalControl): Frame.Text =
    Frame.Text(TerminalJson.encodeToString(TerminalControl.serializer(), control))

/**
 * The close reason of a refusal.
 *
 * @param error The refusal.
 *
 * @return Its close code and message.
 */
internal fun closeReason(error: OstromException): CloseReason {
    val code = when (error.kind) {
        ErrorKind.UNAUTHORIZED -> TerminalCloseCodes.UNAUTHORIZED
        ErrorKind.FORBIDDEN -> TerminalCloseCodes.FORBIDDEN
        ErrorKind.NOT_FOUND -> TerminalCloseCodes.NOT_FOUND
        ErrorKind.CONFLICT -> TerminalCloseCodes.CONFLICT
        else -> TerminalCloseCodes.BAD_REQUEST
    }
    return CloseReason(code, error.message)
}
