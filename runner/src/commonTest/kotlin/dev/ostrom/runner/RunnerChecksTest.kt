// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class RunnerChecksTest {
    /** The server: records the reports, answers with [answer]. */
    private class Reports : ServerApi {
        val checks = mutableListOf<RunnerCheck>()
        var answer: () -> Boolean = { true }

        override suspend fun heartbeat(ready: Boolean, stopped: Boolean, lastModelRequestAt: String?): Instruction =
            error("no heartbeat")

        override suspend fun openTerminal(): TerminalLink = error("no terminal")

        override suspend fun openLaunch(): RunnerLaunch = error("no launch")

        override suspend fun reportCheck(check: RunnerCheck): Boolean {
            checks += check
            return answer()
        }
    }

    /** A clock that moves 250 ms at each reading. */
    private class Ticking : Clock {
        private var at = Instant.parse("2026-11-02T18:00:00Z")

        override fun now(): Instant = at.also { at += 250.milliseconds }
    }

    private fun request(
        script: String = "abc",
        directory: String = "/workspace/api",
        timeoutMs: Long = RunnerChecks.DEFAULT_TIMEOUT_MS,
    ) = CheckRequest("s-1", "sha256:pkg", "n-lint", "lint", script, directory, timeoutMs, "c0ffee")

    private fun checks(
        starter: TerminalStarter,
        server: ServerApi = Reports(),
        environment: Map<String, String> = mapOf("PATH" to "/opt/bin:/usr/bin", "LANG" to "fr_FR.UTF-8"),
    ) = RunnerChecks(starter, server, "/workspace", environment, Ticking())

    @Test
    fun aCheckRunsOnTheCheckHostAndIsReportedWithTheRunnersHash() = runTest {
        val terminal = ScriptedTerminal().apply {
            exitCode = 0
            output.send("lint ok\n".encodeToByteArray())
            output.send(null)
        }
        val starter = RecordingStarter(terminal)
        val server = Reports()

        val reply = checks(starter, server).run(request())

        // SHA-256 of "abc", computed by the runner on what it sends.
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(CheckReply(true, 0, RunnerChecks.PASSED, sha, "lint ok\n", reported = true), reply)
        val (script, environment, directory) = starter.starts.single()
        assertEquals("abc", script)
        assertEquals("/workspace/api", directory)
        assertEquals(
            mapOf(
                "PATH" to "/opt/bin:/usr/bin",
                "LANG" to "fr_FR.UTF-8",
                "HOME" to "/tmp",
                "TMPDIR" to "/tmp",
                "CI" to "1",
                "PYTHONDONTWRITEBYTECODE" to "1",
                "OSTROM_CHECK" to "lint",
            ),
            environment,
        )
        val expected = RunnerCheck(
            "s-1", "sha256:pkg", "n-lint", "lint", sha, 0, RunnerChecks.PASSED, 250, "c0ffee", RunnerChecks.SANDBOX,
            "lint ok\n",
        )
        assertEquals(listOf(expected), server.checks)
        assertTrue(terminal.closed)
    }

    @Test
    fun aFailureIsAFailureAndAnUnreachableServerLeavesItUnreported() = runTest {
        val terminal = ScriptedTerminal().apply {
            exitCode = 2
            output.send(null)
        }
        val starter = RecordingStarter(terminal)
        val server = Reports().apply { answer = { error("down") } }

        val reply = checks(starter, server, environment = emptyMap()).run(request(directory = "/workspace"))

        assertEquals(RunnerChecks.FAILED, reply.outcome)
        assertEquals(2, reply.exit)
        assertTrue(reply.started)
        assertFalse(reply.reported)
        assertEquals("/usr/local/bin:/usr/bin:/bin", starter.starts.single().second["PATH"])
        assertEquals("C.UTF-8", starter.starts.single().second["LANG"])
        // A host that lost the code: failed too.
        val lost = ScriptedTerminal().apply { output.send(null) }
        assertEquals(RunnerChecks.FAILED, checks(RecordingStarter(lost)).run(request()).outcome)
    }

    @Test
    fun aCheckPastItsTimeLimitIsStopped() = runTest {
        val terminal = ScriptedTerminal(stubborn = true).apply { exitCode = 143 }
        val server = Reports()

        val reply = checks(RecordingStarter(terminal), server).run(request(timeoutMs = 1_000))

        assertEquals(RunnerChecks.TIMEOUT, reply.outcome)
        assertNull(reply.exit)
        assertEquals(1, terminal.terminated)
        assertTrue(terminal.closed)
        assertEquals(RunnerChecks.TIMEOUT, server.checks.single().outcome)
    }

    @Test
    fun onlyTheEndOfALongOutputIsKept() = runTest {
        val terminal = ScriptedTerminal().apply {
            exitCode = 0
            repeat(5) { output.send(ByteArray(20_000) { 'a'.code.toByte() }) }
            output.send("THE END".encodeToByteArray())
            output.send(null)
        }

        val tail = checks(RecordingStarter(terminal)).run(request()).outputTail

        assertEquals(4_000, tail.length)
        assertTrue(tail.endsWith("aTHE END"))
    }

    @Test
    fun aRequestOutsideTheRulesIsNotRun() = runTest {
        val starter = RecordingStarter(ScriptedTerminal())
        val checks = checks(starter)
        val refused = listOf(
            request(script = "x".repeat(RunnerChecks.MAX_SCRIPT + 1)),
            request(directory = "/etc"),
            request(directory = "/workspace-other"),
            request(directory = "/workspace/../etc"),
            request(directory = "/workspace/./api"),
            request(timeoutMs = 0),
            request(timeoutMs = RunnerChecks.MAX_TIMEOUT_MS + 1),
        )
        for (request in refused) {
            val reply = checks.run(request)
            assertFalse(reply.started, request.toString())
            assertTrue(reply.error!!.isNotBlank())
        }
        assertTrue(starter.starts.isEmpty())
    }

    @Test
    fun aCheckHostThatCannotBeReachedRunsNothing() = runTest {
        val down = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                error("no host")
        }
        val server = Reports()

        val reply = checks(down, server).run(request())

        assertFalse(reply.started)
        assertEquals("the check host could not be reached.", reply.error)
        assertEquals(64, reply.scriptSha.length)
        assertTrue(server.checks.isEmpty())
    }

    @Test
    fun aCancellationIsNeverTakenForAFailure() = runTest {
        val cancelled = object : TerminalStarter {
            override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal =
                throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { checks(cancelled).run(request()) }
        val terminal = ScriptedTerminal().apply {
            exitCode = 0
            output.send(null)
        }
        val server = Reports().apply { answer = { throw CancellationException("stop") } }
        assertFailsWith<CancellationException> { checks(RecordingStarter(terminal), server).run(request()) }
    }
}
