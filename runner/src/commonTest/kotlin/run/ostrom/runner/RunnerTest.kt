// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnerTest {
    private val config = RunnerConfig("http://s", Uuid.random(), 1, "claude", null, null, "t", "/workspace")

    private class FakeTerminal(val script: String) : Terminal {
        val output = Channel<ByteArray?>(Channel.UNLIMITED)
        override val exitCode: Int? = null
        val written = mutableListOf<String>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        var terminated = false
        var closed = false

        override suspend fun read(): ByteArray? = output.receive()
        override suspend fun write(bytes: ByteArray) {
            written += bytes.decodeToString()
        }
        override suspend fun resize(columns: Int, rows: Int) {
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
        var lastFails = false
        override suspend fun heartbeat(ready: Boolean, stopped: Boolean, lastModelRequestAt: String?): Instruction {
            beats += Triple(ready, stopped, lastModelRequestAt)
            check(!(stopped && lastFails)) { "down" }
            if (stopped) return Instruction.STOP
            return instructions.receive().getOrThrow()
        }
        override suspend fun openTerminal(): TerminalLink = links.receive().getOrThrow()

        val launches = Channel<Result<RunnerLaunch>>(Channel.UNLIMITED)
        override suspend fun openLaunch(): RunnerLaunch = launches.receive().getOrThrow()
        override suspend fun reportCheck(check: RunnerCheck): Boolean = error("no check in these tests")
    }

    private fun starter(block: (String, Map<String, String>, String) -> Terminal) = object : TerminalStarter {
        override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
            block(script, environment, directory)
    }

    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-10-30T10:00:00Z")
    }

    @Test
    fun theRunnerRelaysTheTerminalAndStopsWhenTold() = runTest {
        val server = FakeServer()
        val terminals = mutableListOf<FakeTerminal>()
        val logs = mutableListOf<String>()
        val runner = Runner(config, server, starter { script, environment, directory ->
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
            starter { script, _, _ -> FakeTerminal(script).also { terminals += it } },
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
        val runner = Runner(config, server, starter { script, _, _ -> FakeTerminal(script) }, emptyMap(), clock, {})
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

    @Test
    fun aFailedLastHeartbeatIsLoggedAndTheRunnerStillStops() = runTest {
        val server = FakeServer().apply { lastFails = true }
        val logs = mutableListOf<String>()
        val terminals = starter { script, _, _ -> FakeTerminal(script) }
        val runner = Runner(config, server, terminals, emptyMap(), clock, { logs += it })
        server.instructions.send(Result.success(Instruction.STOP))

        assertEquals(0, runner.run())
        assertTrue(logs.any { it.startsWith("last heartbeat failed") })
    }

    /** Runs a governed runner until its first terminal starts, then stops it; returns that terminal's environment. */
    private suspend fun TestScope.firstEnvironment(
        server: FakeServer,
        logs: MutableList<String> = mutableListOf(),
    ): Map<String, String> {
        val environments = mutableListOf<Map<String, String>>()
        val runner = Runner(
            config.copy(agent = "claude-code"),
            server,
            starter { script, environment, _ -> FakeTerminal(script).also { environments += environment } },
            mapOf("OSTROM_RUNNER_CHECKS" to "/elsewhere", "OSTROM_CHECK_HOST_SOCKET" to "/run/ostrom-check/check.sock"),
            clock,
            { logs += it },
        )
        server.instructions.send(Result.success(Instruction.STOP))
        assertEquals(0, runner.run())
        return environments.single()
    }

    @Test
    fun aGovernedAgentStartsWithItsLaunch() = runTest {
        val server = FakeServer()
        val logs = mutableListOf<String>()
        server.launches.send(Result.failure(IllegalStateException("down")))
        server.launches.send(Result.success(RunnerLaunch.Opened("L-1", "ostrom_agent_secret")))
        val environment = firstEnvironment(server, logs)

        assertEquals("L-1", environment["OSTROM_LAUNCH_ID"])
        assertEquals("ostrom_agent_secret", environment["OSTROM_AGENT_TOKEN"])
        assertEquals("http://s", environment["OSTROM_URL"])
        // The checks' socket is the runner's own, and the check host's is never the agent's to know.
        assertEquals("/run/ostrom/checks.sock", environment["OSTROM_RUNNER_CHECKS"])
        assertNull(environment["OSTROM_CHECK_HOST_SOCKET"])
        assertNull(environment["OSTROM_LAUNCH_REFUSED"])
        assertTrue("launch failed: IllegalStateException" in logs)
        assertTrue("launch Opened(launchId=L-1)" in logs)
        assertFalse(logs.any { "ostrom_agent_secret" in it })
    }

    @Test
    fun aRefusedLaunchLeavesTheAgentOut() = runTest {
        val server = FakeServer()
        server.launches.send(Result.success(RunnerLaunch.Refused("Claude Code is not allowed here.")))
        val environment = firstEnvironment(server)

        assertEquals("Claude Code is not allowed here.", environment["OSTROM_LAUNCH_REFUSED"])
        assertNull(environment["OSTROM_LAUNCH_ID"])
        assertNull(environment["OSTROM_RUNNER_CHECKS"])
    }

    @Test
    fun aServerThatNeverAnswersTheLaunchLeavesTheAgentOut() = runTest {
        val server = FakeServer()
        repeat(10) { server.launches.send(Result.failure(IllegalStateException("down"))) }
        val environment = firstEnvironment(server)

        assertEquals("the server could not be reached.", environment["OSTROM_LAUNCH_REFUSED"])
    }

    @Test
    fun anExecutionEndedBeforeItsLaunchEndsTheRunner() = runTest {
        val server = FakeServer()
        val logs = mutableListOf<String>()
        server.launches.send(Result.failure(ExecutionEndedException("replaced")))
        val runner = Runner(
            config.copy(agent = "codex"),
            server,
            starter { _, _, _ -> error("no terminal") },
            emptyMap(),
            clock,
            { logs += it },
        )

        assertEquals(1, runner.run())
        assertEquals(listOf("execution ended: replaced"), logs)
    }

    @Test
    fun aCancelledLaunchStaysCancelled() = runTest {
        val server = FakeServer()
        server.launches.send(Result.failure(CancellationException("stop")))
        val terminals = starter { _, _, _ -> error("no terminal") }
        val runner = Runner(config.copy(agent = "codex"), server, terminals, emptyMap(), clock, {})
        assertFailsWith<CancellationException> { runner.run() }
    }

    private fun CoroutineScope.launchRunner(runner: Runner, done: CompletableDeferred<Int>) =
        launch { done.complete(runner.run()) }
}
