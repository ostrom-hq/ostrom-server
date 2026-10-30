// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnerTest {
    private val config = RunnerConfig("http://s", Uuid.random(), 1, "claude", null, null, "t", "/workspace")

    private class FakeTerminal(val script: String) : Terminal {
        val output = Channel<ByteArray?>(Channel.UNLIMITED)
        val written = mutableListOf<String>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        var terminated = false
        var closed = false

        override suspend fun read(): ByteArray? = output.receive()
        override suspend fun write(bytes: ByteArray) {
            written += bytes.decodeToString()
        }
        override fun resize(columns: Int, rows: Int) {
            sizes += columns to rows
        }
        override suspend fun terminate() {
            terminated = true
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeLink : TerminalLink {
        val frames = Channel<ServerFrame>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        var closed = false
        override val incoming: ReceiveChannel<ServerFrame> = frames
        override suspend fun send(bytes: ByteArray) {
            sent.send(bytes.decodeToString())
        }
        override suspend fun close() {
            closed = true
        }
    }

    private class FakeServer : ServerApi {
        val instructions = Channel<Result<Instruction>>(Channel.UNLIMITED)
        val beats = mutableListOf<Triple<Boolean, Boolean, String?>>()
        val links = Channel<Result<TerminalLink>>(Channel.UNLIMITED)
        override suspend fun heartbeat(ready: Boolean, stopped: Boolean, lastModelRequestAt: String?): Instruction {
            beats += Triple(ready, stopped, lastModelRequestAt)
            if (stopped) return Instruction.STOP
            return instructions.receive().getOrThrow()
        }
        override suspend fun openTerminal(): TerminalLink = links.receive().getOrThrow()
    }

    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-30T10:00:00Z")
    }

    @Test
    fun theRunnerRelaysTheTerminalAndStopsWhenTold() = runTest {
        val server = FakeServer()
        val terminals = mutableListOf<FakeTerminal>()
        val logs = mutableListOf<String>()
        val runner = Runner(config, server, { script, environment, directory ->
            assertEquals("/workspace", directory)
            assertTrue("OSTROM_RUNNER_TOKEN" !in environment)
            FakeTerminal(script).also { terminals += it }
        }, mapOf("OSTROM_RUNNER_TOKEN" to "t"), clock, { logs += it })
        val link = FakeLink()
        server.links.send(Result.failure(IllegalStateException("down")))
        server.links.send(Result.success(link))
        val done = CompletableDeferred<Int>()
        val job = backgroundScope.launchRunner(runner, done)

        server.instructions.send(Result.success(Instruction.RUN))
        // The runner starts its terminal once it has read the instruction: let it run before looking.
        testScheduler.runCurrent()
        terminals.first().output.send("hello".encodeToByteArray())
        assertEquals("hello", link.sent.receive())
        link.frames.send(ServerFrame.Input("ls\r".encodeToByteArray()))
        link.frames.send(ServerFrame.Resize(100, 30))
        server.instructions.send(Result.failure(IllegalStateException("blip")))
        server.instructions.send(Result.success(Instruction.STOP))

        assertEquals(0, done.await())
        job.cancel()
        val terminal = terminals.first()
        assertEquals(SessionScript.FIRST, terminal.script)
        assertEquals(listOf("ls\r"), terminal.written)
        assertEquals(listOf(100 to 30), terminal.sizes)
        assertTrue(terminal.terminated && terminal.closed)
        assertEquals(Triple(true, false, null), server.beats.first())
        assertEquals(Triple(true, true, "2026-10-30T10:00:00Z"), server.beats.last())
        assertTrue(logs.any { it.startsWith("terminal link failed") })
        assertTrue(logs.any { it.startsWith("heartbeat failed") })
    }

    @Test
    fun anEndedTerminalIsFollowedByAShell() = runTest {
        val server = FakeServer()
        val terminals = mutableListOf<FakeTerminal>()
        val runner = Runner(
            config,
            server,
            { script, _, _ -> FakeTerminal(script).also { terminals += it } },
            emptyMap(),
            clock,
            {},
        )
        val done = CompletableDeferred<Int>()
        backgroundScope.launchRunner(runner, done)

        server.instructions.send(Result.success(Instruction.RUN))
        // The runner starts its terminal once it has read the instruction: let it run before looking.
        testScheduler.runCurrent()
        terminals.first().output.send(null)
        testScheduler.advanceTimeBy(16.seconds)
        assertEquals(SessionScript.AGAIN, terminals.last().script)
        assertTrue(terminals.first().closed)
        server.instructions.send(Result.failure(ExecutionEndedException("replaced")))
        assertEquals(1, done.await())
        assertTrue(terminals.last().terminated)
    }

    @Test
    fun aDroppedLinkIsReopened() = runTest {
        val server = FakeServer()
        val runner = Runner(config, server, { script, _, _ -> FakeTerminal(script) }, emptyMap(), clock, {})
        val first = FakeLink()
        val second = FakeLink()
        server.links.send(Result.success(first))
        server.links.send(Result.success(second))
        val done = CompletableDeferred<Int>()
        backgroundScope.launchRunner(runner, done)

        first.frames.close()
        testScheduler.advanceTimeBy(3.seconds)
        assertTrue(first.closed)
        second.frames.close(IllegalStateException("reset"))
        testScheduler.advanceTimeBy(3.seconds)
        assertTrue(second.closed)
        server.instructions.send(Result.success(Instruction.STOP))
        assertEquals(0, done.await())
    }

    private fun CoroutineScope.launchRunner(runner: Runner, done: CompletableDeferred<Int>) =
        launch { done.complete(runner.run()) }
}
