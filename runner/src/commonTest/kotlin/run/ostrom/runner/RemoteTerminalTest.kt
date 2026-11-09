// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RemoteTerminalTest {
    /** A connection whose two ends the test holds. */
    private class Pipe : AgentConnection {
        val fromHost = ByteChannel()
        val toHost = ByteChannel()
        var closed = false
        override val input: ByteReadChannel = fromHost
        override val output: ByteWriteChannel = toHost

        override fun close() {
            closed = true
        }
    }

    @Test
    fun theStarterRetriesUntilTheHostListensThenAsksForTheTerminal() = runTest {
        val pipe = Pipe()
        var attempts = 0
        val starter = RemoteTerminalStarter({
            attempts++
            check(attempts == 3) { "not yet" }
            pipe
        }, attempts = 5, retryDelay = 10.milliseconds)
        starter.start("run", mapOf("A" to "b"), "/workspace")
        assertEquals(3, attempts)
        assertEquals(AgentFrame.Start("run", mapOf("A" to "b"), "/workspace"), AgentFrames.read(pipe.toHost))
    }

    @Test
    fun theStarterGivesUpAfterItsAttemptsAndNeverHidesACancellation() = runTest {
        var attempts = 0
        val failing = RemoteTerminalStarter({
            attempts++
            error("no host")
        }, attempts = 2, retryDelay = 10.milliseconds)
        assertFailsWith<IllegalStateException> { failing.start("run", emptyMap(), "/") }
        assertEquals(2, attempts)
        val cancelled = RemoteTerminalStarter({ throw CancellationException("stop") }, attempts = 2)
        assertFailsWith<CancellationException> { cancelled.start("run", emptyMap(), "/") }
    }

    @Test
    fun theTerminalReadsOutputUntilTheHostReportsTheEnd() = runTest {
        val pipe = Pipe()
        val terminal = RemoteTerminal(pipe, 5.seconds)
        AgentFrames.write(pipe.fromHost, AgentFrame.Output("hi".encodeToByteArray()))
        // Frames only the runner sends are ignored.
        AgentFrames.write(pipe.fromHost, AgentFrame.Terminate)
        AgentFrames.write(pipe.fromHost, AgentFrame.Exit(4))
        assertEquals("hi", terminal.read()?.decodeToString())
        assertNull(terminal.exitCode)
        assertNull(terminal.read())
        assertEquals(4, terminal.exitCode)
        terminal.close()
        assertTrue(pipe.closed)
    }

    @Test
    fun aBrokenConnectionEndsTheTerminal() = runTest {
        val closed = Pipe()
        closed.fromHost.flushAndClose()
        val ended = RemoteTerminal(closed, 5.seconds)
        assertNull(ended.read())
        // No code was reported.
        assertNull(ended.exitCode)
        val garbage = Pipe()
        garbage.fromHost.writeByte(42)
        garbage.fromHost.writeInt(0)
        garbage.fromHost.flushAndClose()
        assertNull(RemoteTerminal(garbage, 5.seconds).read())
    }

    @Test
    fun aCancelledReadStaysCancelled() = runTest {
        val terminal = RemoteTerminal(Pipe(), 5.seconds)
        var cancelled = false
        val reading = launch {
            try {
                terminal.read()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        testScheduler.advanceUntilIdle()
        reading.cancel()
        reading.join()
        assertTrue(cancelled)
    }

    @Test
    fun keystrokesSizesAndTheStopGoToTheHost() = runTest {
        val pipe = Pipe()
        val terminal = RemoteTerminal(pipe, 5.seconds)
        terminal.write("ls\r".encodeToByteArray())
        terminal.resize(80, 24)
        assertEquals("ls\r", assertIs<AgentFrame.Input>(AgentFrames.read(pipe.toHost)).bytes.decodeToString())
        assertEquals(AgentFrame.Resize(80, 24), AgentFrames.read(pipe.toHost))
        val reader = launch { while (terminal.read() != null) Unit }
        val stopping = launch { terminal.terminate() }
        assertEquals(AgentFrame.Terminate, AgentFrames.read(pipe.toHost))
        AgentFrames.write(pipe.fromHost, AgentFrame.Exit(143))
        stopping.join()
        reader.join()
    }

    @Test
    fun aStopWithoutAnswerGivesUpAfterTheWait() = runTest {
        val pipe = Pipe()
        RemoteTerminal(pipe, 1.seconds).terminate()
        assertEquals(AgentFrame.Terminate, AgentFrames.read(pipe.toHost))
    }

    @Test
    fun aFrameToAClosedHostIsDroppedButACancellationIsNot() = runTest {
        val closed = Pipe()
        closed.toHost.flushAndClose()
        RemoteTerminal(closed, 5.seconds).write("lost".encodeToByteArray())
        // A frame larger than the channel's buffer suspends until read: cancelling it propagates.
        val terminal = RemoteTerminal(Pipe(), 5.seconds)
        var cancelled = false
        val writing = launch {
            try {
                terminal.write(ByteArray(AgentFrames.MAX_PAYLOAD))
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        testScheduler.advanceUntilIdle()
        writing.cancel()
        writing.join()
        assertTrue(cancelled)
    }
}
