// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A terminal whose output the test writes; [terminate] ends it unless [stubborn]. */
internal class ScriptedTerminal(private val stubborn: Boolean = false) : Terminal {
    val output = Channel<ByteArray?>(Channel.UNLIMITED)
    override var exitCode: Int? = null
    val written = mutableListOf<String>()
    val sizes = mutableListOf<Pair<Int, Int>>()
    var terminated = 0
    var closed = false

    override suspend fun read(): ByteArray? = output.receive()

    override suspend fun write(bytes: ByteArray) {
        written += bytes.decodeToString()
    }

    override suspend fun resize(columns: Int, rows: Int) {
        sizes += columns to rows
    }

    override suspend fun terminate() {
        terminated++
        if (!stubborn) output.trySend(null)
    }

    override fun close() {
        closed = true
    }
}

/** Starts [terminal] and records what it was asked. */
internal class RecordingStarter(private val terminal: Terminal) : TerminalStarter {
    val starts = mutableListOf<Triple<String, Map<String, String>, String>>()

    override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
        starts += Triple(script, environment, directory)
        return terminal
    }
}

class AgentHostTest {
    private val start = AgentFrame.Start("run", mapOf("TERM" to "xterm"), "/workspace")

    @Test
    fun aConnectionThatDoesNotStartWithStartStartsNothing() = runTest {
        val starter = RecordingStarter(ScriptedTerminal())
        AgentHost(starter).serve(ByteReadChannel.Empty, ByteChannel())
        val input = ByteChannel()
        AgentFrames.write(input, AgentFrame.Input(byteArrayOf(1)))
        input.flushAndClose()
        AgentHost(starter).serve(input, ByteChannel())
        assertTrue(starter.starts.isEmpty())
    }

    @Test
    fun theHostRunsTheTerminalTheRunnerAsksFor() = runTest {
        val terminal = ScriptedTerminal()
        val starter = RecordingStarter(terminal)
        val fromRunner = ByteChannel()
        val toRunner = ByteChannel()
        val served = launch { AgentHost(starter).serve(fromRunner, toRunner) }
        AgentFrames.write(fromRunner, start)
        terminal.output.send("$ ".encodeToByteArray())
        assertEquals("$ ", assertIs<AgentFrame.Output>(AgentFrames.read(toRunner)).bytes.decodeToString())
        AgentFrames.write(fromRunner, AgentFrame.Input("ls\r".encodeToByteArray()))
        AgentFrames.write(fromRunner, AgentFrame.Resize(100, 30))
        // Frames the runner has no reason to send are ignored.
        AgentFrames.write(fromRunner, AgentFrame.Exit(0))
        AgentFrames.write(fromRunner, start)
        terminal.exitCode = 7
        terminal.output.send(null)
        assertEquals(AgentFrame.Exit(7), AgentFrames.read(toRunner))
        fromRunner.flushAndClose()
        served.join()
        assertEquals(listOf(Triple("run", mapOf("TERM" to "xterm"), "/workspace")), starter.starts)
        assertEquals(listOf("ls\r"), terminal.written)
        assertEquals(listOf(100 to 30), terminal.sizes)
        assertTrue(terminal.closed)
    }

    @Test
    fun aStopEndsTheTerminalAndReportsTheEnd() = runTest {
        val terminal = ScriptedTerminal()
        val fromRunner = ByteChannel()
        val toRunner = ByteChannel()
        val served = launch { AgentHost(RecordingStarter(terminal)).serve(fromRunner, toRunner) }
        AgentFrames.write(fromRunner, start)
        AgentFrames.write(fromRunner, AgentFrame.Terminate)
        assertEquals(AgentFrame.Exit(null), AgentFrames.read(toRunner))
        served.join()
        assertEquals(2, terminal.terminated)
        assertTrue(terminal.closed)
    }

    @Test
    fun aTerminalThatDoesNotDrainIsLeftAfterTheGrace() = runTest {
        val terminal = ScriptedTerminal(stubborn = true)
        val fromRunner = ByteChannel()
        val toRunner = ByteChannel()
        val served = launch { AgentHost(RecordingStarter(terminal), drain = 1.seconds).serve(fromRunner, toRunner) }
        AgentFrames.write(fromRunner, start)
        AgentFrames.write(fromRunner, AgentFrame.Terminate)
        served.join()
        assertTrue(terminal.closed)
    }

    @Test
    fun theRunnerLeavingStopsTheTerminal() = runTest {
        val terminal = ScriptedTerminal(stubborn = true)
        val fromRunner = ByteChannel()
        val served = launch { AgentHost(RecordingStarter(terminal)).serve(fromRunner, ByteChannel()) }
        AgentFrames.write(fromRunner, start)
        fromRunner.flushAndClose()
        served.join()
        assertEquals(1, terminal.terminated)
        assertTrue(terminal.closed)
        assertNull(terminal.output.tryReceive().getOrNull())
    }
}
