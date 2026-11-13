// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class AgentFramesTest {
    private suspend fun roundTrip(frame: AgentFrame): AgentFrame? {
        val channel = ByteChannel()
        AgentFrames.write(channel, frame)
        channel.flushAndClose()
        return AgentFrames.read(channel)
    }

    @Test
    fun everyFrameCrossesTheWireUnchanged() = runTest {
        val start = AgentFrame.Start("echo hi", mapOf("HOME" to "/workspace/.home"), "/workspace")
        assertEquals(start, roundTrip(start))
        val input = assertIs<AgentFrame.Input>(roundTrip(AgentFrame.Input(byteArrayOf(1, 2))))
        assertContentEquals(byteArrayOf(1, 2), input.bytes)
        assertEquals(AgentFrame.Resize(120, 40), roundTrip(AgentFrame.Resize(120, 40)))
        assertEquals(AgentFrame.Resize(70_000, 1), roundTrip(AgentFrame.Resize(70_000, 1)))
        assertEquals(AgentFrame.Terminate, roundTrip(AgentFrame.Terminate))
        val output = assertIs<AgentFrame.Output>(roundTrip(AgentFrame.Output(byteArrayOf(-1))))
        assertContentEquals(byteArrayOf(-1), output.bytes)
        assertEquals(AgentFrame.Exit(3), roundTrip(AgentFrame.Exit(3)))
        assertEquals(AgentFrame.Exit(-1), roundTrip(AgentFrame.Exit(-1)))
        // A code the host could not read crosses as an empty payload.
        assertEquals(AgentFrame.Exit(null), roundTrip(AgentFrame.Exit(null)))
    }

    @Test
    fun theEndOfTheStreamReadsAsNull() = runTest {
        assertNull(AgentFrames.read(ByteReadChannel.Empty))
    }

    private suspend fun raw(type: Byte, size: Int): ByteChannel = ByteChannel().apply {
        writeByte(type)
        writeInt(size)
        flushAndClose()
    }

    @Test
    fun anUnknownTypeOrAnOversizedPayloadBreaksTheProtocol() = runTest {
        assertFailsWith<AgentProtocolException> { AgentFrames.read(raw(9, 0)) }
        assertFailsWith<AgentProtocolException> { AgentFrames.read(raw(2, AgentFrames.MAX_PAYLOAD + 1)) }
        assertFailsWith<AgentProtocolException> { AgentFrames.read(raw(2, -1)) }
    }
}
