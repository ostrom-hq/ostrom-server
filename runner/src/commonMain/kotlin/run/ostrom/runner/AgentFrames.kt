// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A frame between the runner and the agent host (S2-49: the agent runs in its own container, under its own uid). */
sealed interface AgentFrame {
    /**
     * Runner to host: start `sh -c script` on a new PTY. The environment is the agent's, without the runner's
     * private variables.
     *
     * @property script The script.
     * @property environment Its environment.
     * @property directory Its working directory.
     */
    @Serializable
    data class Start(val script: String, val environment: Map<String, String>, val directory: String) : AgentFrame

    /** Runner to host: keystrokes. */
    class Input(val bytes: ByteArray) : AgentFrame

    /** Runner to host: a new size. */
    data class Resize(val columns: Int, val rows: Int) : AgentFrame

    /** Runner to host: stop the program (SIGTERM, then SIGKILL after the grace). */
    data object Terminate : AgentFrame

    /** Host to runner: output. */
    class Output(val bytes: ByteArray) : AgentFrame

    /**
     * Host to runner: the program ended and its output is drained.
     *
     * @property code Its exit code (128 + the signal when a signal ended it), or null when the host could not read it.
     */
    data class Exit(val code: Int?) : AgentFrame
}

/** A frame that breaks the protocol: an unknown type, or a payload over [AgentFrames.MAX_PAYLOAD]. */
class AgentProtocolException(message: String) : Exception(message)

/** The wire form of [AgentFrame]: a type byte, a payload length (4 bytes, big-endian), then the payload. */
object AgentFrames {
    /** The largest payload: a START carries the agent's environment, an OUTPUT a PTY read (16 KiB). */
    const val MAX_PAYLOAD = 1 shl 20

    private const val START: Byte = 1
    private const val INPUT: Byte = 2
    private const val RESIZE: Byte = 3
    private const val TERMINATE: Byte = 4
    private const val OUTPUT: Byte = 5
    private const val EXIT: Byte = 6
    private const val INT_BYTES = 4
    private const val BYTE_MASK = 0xff
    private const val BYTE_BITS = 8

    /**
     * Writes a frame and flushes it.
     *
     * @param channel Where.
     * @param frame The frame.
     */
    suspend fun write(channel: ByteWriteChannel, frame: AgentFrame) {
        val (type, payload) = when (frame) {
            is AgentFrame.Start -> START to startBytes(frame)
            is AgentFrame.Input -> INPUT to frame.bytes
            is AgentFrame.Resize -> RESIZE to intBytes(frame.columns) + intBytes(frame.rows)
            AgentFrame.Terminate -> TERMINATE to ByteArray(0)
            is AgentFrame.Output -> OUTPUT to frame.bytes
            is AgentFrame.Exit -> EXIT to (frame.code?.let(::intBytes) ?: ByteArray(0))
        }
        channel.writeByte(type)
        channel.writeInt(payload.size)
        channel.writeByteArray(payload)
        channel.flush()
    }

    /**
     * Reads the next frame.
     *
     * @param channel Where.
     *
     * @return The frame, or null at the end of the stream.
     *
     * @throws AgentProtocolException On an unknown type or a payload too large.
     */
    suspend fun read(channel: ByteReadChannel): AgentFrame? {
        if (channel.exhausted()) return null
        val type = channel.readByte()
        val size = channel.readInt()
        if (size !in 0..MAX_PAYLOAD) throw AgentProtocolException("A frame of $size bytes.")
        val payload = channel.readByteArray(size)
        return when (type) {
            START -> Json.decodeFromString(AgentFrame.Start.serializer(), payload.decodeToString())
            INPUT -> AgentFrame.Input(payload)
            RESIZE -> AgentFrame.Resize(intAt(payload, 0), intAt(payload, INT_BYTES))
            TERMINATE -> AgentFrame.Terminate
            OUTPUT -> AgentFrame.Output(payload)
            // An empty payload: a code the host could not read.
            EXIT -> AgentFrame.Exit(if (payload.size == INT_BYTES) intAt(payload, 0) else null)
            else -> throw AgentProtocolException("An unknown frame type $type.")
        }
    }

    private fun startBytes(frame: AgentFrame.Start): ByteArray =
        Json.encodeToString(AgentFrame.Start.serializer(), frame).encodeToByteArray()

    private fun intBytes(value: Int): ByteArray =
        ByteArray(INT_BYTES) { index -> (value shr (BYTE_BITS * (INT_BYTES - 1 - index))).toByte() }

    private fun intAt(bytes: ByteArray, offset: Int): Int = (0 until INT_BYTES).fold(0) { value, index ->
        (value shl BYTE_BITS) or (bytes[offset + index].toInt() and BYTE_MASK)
    }
}
