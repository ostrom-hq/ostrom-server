// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import run.ostrom.mcphost.HostHarness.Companion.KEY
import run.ostrom.mcphost.HostHarness.Companion.OTHER
import run.ostrom.mcphost.HostHarness.Companion.SECRET
import run.ostrom.mcphost.HostHarness.Companion.call
import run.ostrom.mcphost.HostHarness.Companion.list
import run.ostrom.mcphost.HostHarness.Companion.spec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SupervisorTest {
    private fun hostTest(
        config: HostConfig = HostHarness.CONFIG,
        prepare: HostHarness.() -> Unit = {},
        block: suspend TestScope.(HostHarness) -> Unit,
    ) = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val harness = HostHarness(backgroundScope, io, { testScheduler.currentTime }, config, prepare)
        try {
            block(harness)
        } finally {
            harness.supervisor.shutdown()
        }
    }

    private fun OpsResponse.text(): String =
        result!!["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    private fun OpsResponse.tools(): List<String> =
        (result!!["tools"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> if (value is Number) put(key, value) else put(key, value.toString()) }
    }

    @Test
    fun aListingStartsTheProcessOnceAndKeepsItsTools() = hostTest { host ->
        val first = host.supervisor.op(list())
        assertTrue(first.cold)
        assertEquals(ProcessStates.READY, first.state)
        assertTrue("echo" in first.tools())
        val second = host.supervisor.op(list())
        assertFalse(second.cold)
        assertEquals(first.result, second.result)
        val child = host.last()
        assertEquals(1, child.received.count { it["method"]?.jsonPrimitive?.content == "initialize" })
        assertEquals(1, child.received.count { it["method"]?.jsonPrimitive?.content == "tools/list" })
        assertEquals(
            listOf(
                "/usr/bin/prlimit", "--nproc=1024", "--nofile=1024", "--", "npx", "--yes", "--", "@acme/server@1.2.3",
                "--token", SECRET,
            ),
            child.argv,
        )
        assertEquals("/var/mcp/$KEY/home", child.environment["HOME"])
        assertEquals("2023-11-14T22:13:20Z", child.environment["npm_config_before"])
        assertTrue(host.events.any { it["event"]?.jsonPrimitive?.content == "started" })
        assertTrue(host.files.directories.contains("/var/mcp/$KEY/work"))
    }

    @Test
    fun concurrentCallsShareOneStartAndAreMultiplexed() = hostTest { host ->
        val answers = (1..16).map { index ->
            async { host.supervisor.op(call("echo", arguments = args("text" to "n$index"))) }
        }.awaitAll()
        assertEquals((1..16).map { "n$it" }, answers.map { it.text() })
        assertEquals(1, host.spawner.started.size)
    }

    @Test
    fun aPaginatedListingIsReadWhole() = hostTest { host ->
        val listed = host.supervisor.op(list(spec = spec(extra = mapOf("FIXTURE_PAGE_SIZE" to "5"))))
        assertEquals(12, listed.tools().size)
    }

    @Test
    fun aListChangedNotificationClearsTheKeptTools() = hostTest { host ->
        assertFalse("added" in host.supervisor.op(list()).tools())
        assertEquals("changed", host.supervisor.op(call("change")).text())
        assertTrue("added" in host.supervisor.op(list()).tools())
        val status = host.supervisor.status().processes.single()
        assertTrue("[notifications/message] \"logged\"" in status.stderr, status.stderr)
    }

    @Test
    fun serverRequestsAreAnsweredAtOnce() = hostTest { host ->
        assertTrue("-32601" in host.supervisor.op(call("ask")).text())
        assertTrue("\"result\":{}" in host.supervisor.op(call("ping")).text())
    }

    @Test
    fun secretsAreScrubbedFromResultsErrorsStderrAndEvents() = hostTest { host ->
        assertEquals("the secret is [REDACTED]", host.supervisor.op(call("secret")).text())
        val failed = host.supervisor.op(call("fail"))
        assertEquals(HostErrors.TOOL_ERROR, failed.error!!.code)
        assertTrue("[REDACTED]" in failed.error.message && SECRET !in failed.error.message)
        val stderr = host.supervisor.status().processes.single().stderr
        assertTrue("secret=[REDACTED]" in stderr, stderr)
        assertTrue(host.events.none { SECRET in it.toString() })
    }

    @Test
    fun anUnknownToolIsAToolError() = hostTest { host ->
        assertEquals(HostErrors.TOOL_ERROR, host.supervisor.op(call("nope")).error!!.code)
        assertEquals("The MCP server returned error 0: ", host.supervisor.op(call("bare-error")).error!!.message)
        assertEquals(HostErrors.PROTOCOL, host.supervisor.op(call("bad")).error!!.code)
    }

    @Test
    fun aBannerIsIgnoredAndANonJsonFloodRestartsTheProcess() = hostTest { host ->
        val spammed = host.supervisor.op(call("spam", spec = spec("banner"), arguments = args("lines" to 50)))
        assertEquals("spammed", spammed.text())
        assertEquals(
            "spammed",
            host.supervisor.op(call("spam", spec = spec("banner"), arguments = args("lines" to 60))).text(),
        )
        delay(10_000)
        assertFalse(host.spawner.started.first().isAlive)
        host.supervisor.op(list(spec = spec("banner")))
        assertEquals(2, host.spawner.started.size)
        assertEquals("non_json", host.supervisor.status().processes.single().lastFailure)
    }

    @Test
    fun aCrashFailsTheCallAndRepeatedFailuresBackOffUntilARestart() = hostTest { host ->
        repeat(4) {
            val crashed = host.supervisor.op(call("crash"))
            assertEquals(HostErrors.CRASHED, crashed.error!!.code)
            delay(100)
        }
        val refused = host.supervisor.op(list())
        assertEquals(HostErrors.BACKOFF, refused.error!!.code)
        assertEquals(ProcessStates.BACKOFF, refused.state)
        assertTrue(host.events.any { it["event"]?.jsonPrimitive?.content == "backoff" })
        assertNotNull(host.supervisor.status().processes.single().backoffUntil)
        assertTrue(host.supervisor.stop(KEY))
        assertNull(host.supervisor.op(list()).error)
        assertFalse(host.supervisor.stop(OTHER))
    }

    @Test
    fun aDeadlineCancelsTheCallAndTellsTheProcess() = hostTest { host ->
        val timedOut = host.supervisor.op(call("slow", deadline = 1_000, arguments = args("ms" to 120_000)))
        assertEquals(HostErrors.TIMEOUT, timedOut.error!!.code)
        runCurrent()
        assertTrue("cancelled=[2]" in host.supervisor.op(call("stats")).text())
    }

    @Test
    fun aStartLongerThanTheDeadlineGoesOnForTheNextCall() = hostTest { host ->
        val slow = spec("slow-start", mapOf("FIXTURE_START_DELAY" to "5000"))
        val first = host.supervisor.op(list(spec = slow, deadline = 1_000))
        assertEquals(HostErrors.TIMEOUT, first.error!!.code)
        assertEquals(ProcessStates.STARTING, first.state)
        delay(5_000)
        assertNull(host.supervisor.op(list(spec = slow)).error)
        assertEquals(1, host.spawner.started.size)
    }

    @Test
    fun callsBeyondTheProcessSlotsAreBusy() = hostTest(HostHarness.CONFIG.copy(maxInFlightPerProcess = 1)) { host ->
        host.supervisor.op(list())
        val held = launch { host.supervisor.op(call("slow", arguments = args("ms" to 10_000))) }
        runCurrent()
        assertEquals(HostErrors.BUSY, host.supervisor.op(call("echo", deadline = 1_000)).error!!.code)
        held.join()
    }

    @Test
    fun aFullInputQueueIsBusy() = hostTest(HostHarness.CONFIG.copy(maxInFlightPerProcess = 1)) { host ->
        host.supervisor.op(list())
        host.supervisor.op(call("stuck"))
        // Each call times out and queues its cancellation behind the blocked write, until the queue is full.
        val codes = (1..40).map { host.supervisor.op(call("echo", deadline = 100)).error!!.code }
        assertTrue(HostErrors.BUSY in codes, codes.toString())
    }

    @Test
    fun anAnswerOverTheLimitIsTooLarge() = hostTest { host ->
        val big = host.supervisor.op(call("big", max = 2_048, arguments = args("bytes" to 4_096)))
        assertEquals(HostErrors.TOO_LARGE, big.error!!.code)
    }

    @Test
    fun oversizedLinesFailTheirCallAtOnceAndRestartTheProcessOnTheThird() =
        hostTest(HostHarness.CONFIG.copy(maxLineBytes = 1_024)) { host ->
            val head = host.supervisor.op(call("oversize", arguments = args("bytes" to 3_000)))
            assertEquals(HostErrors.TOO_LARGE, head.error!!.code)
            val tail = host.supervisor.op(call("oversize", arguments = args("bytes" to 3_000, "where" to "tail")))
            assertEquals(HostErrors.TOO_LARGE, tail.error!!.code)
            // The third restarts the process: the call whose id could not be read fails with it.
            val none = host.supervisor.op(call("oversize", arguments = args("bytes" to 3_000, "where" to "none")))
            assertEquals(HostErrors.CRASHED, none.error!!.code)
            delay(10_000)
            assertEquals("oversize", host.supervisor.status().processes.single().lastFailure)
            assertEquals(3, host.events.count { it["event"]?.jsonPrimitive?.content == "oversize" })
        }

    @Test
    fun failedStartsSayWhy() = hostTest { host ->
        val cases = mapOf(
            "exit-at-start" to "exited during startup (exit code 7)",
            "no-tools" to "does not offer tools",
            "old-protocol" to "does not support a protocol version",
            "no-result" to "without a result",
            "init-error" to "refused initialize",
        )
        cases.entries.forEachIndexed { index, (mode, message) ->
            val key = index.toString().repeat(64)
            val failed = host.supervisor.op(list(key = key, spec = spec(mode)))
            assertEquals(HostErrors.START_FAILED, failed.error!!.code, mode)
            assertTrue(message in failed.error.message, failed.error.message)
            assertEquals(ProcessStates.FAILED, failed.state)
        }
        assertTrue(host.events.count { it["event"]?.jsonPrimitive?.content == "start_failed" } >= cases.size)
    }

    @Test
    fun aListingErrorIsAProtocolError() = hostTest { host ->
        assertEquals(HostErrors.PROTOCOL, host.supervisor.op(list(spec = spec("list-error"))).error!!.code)
        assertEquals(HostErrors.PROTOCOL, host.supervisor.op(list(key = OTHER, spec = spec("bad-list"))).error!!.code)
    }

    @Test
    fun aMissingLauncherOrAFailedSpawnFailsTheStart() = hostTest { host ->
        host.spawner.installed -= "prlimit"
        val missing = host.supervisor.op(list())
        assertTrue("prlimit is not installed" in missing.error!!.message, missing.toString())
        host.spawner.installed += "prlimit"
        host.spawner.failSpawn = true
        val spawn = host.supervisor.op(list(key = OTHER))
        assertTrue("could not be started" in spawn.error!!.message)
    }

    @Test
    fun aStartThatNeverAnswersTimesOut() = hostTest(HostHarness.CONFIG.copy(startTimeoutMillis = 2_000)) { host ->
        val failed = host.supervisor.op(list(spec = spec("stuck-start")))
        assertEquals(HostErrors.START_FAILED, failed.error!!.code)
        assertTrue("within 2 seconds" in failed.error.message)
    }

    @Test
    fun anUnexpectedStartErrorIsAGenericStartFailure() = hostTest { host ->
        host.files.failCreate = true
        val failed = host.supervisor.op(list())
        assertEquals(HostErrors.START_FAILED, failed.error!!.code)
        assertEquals("The MCP host could not start the server.", failed.error.message)
    }

    @Test
    fun anInvalidDefinitionIsRefused() = hostTest { host ->
        val invalid = host.supervisor.op(list(spec = spec().copy(pkg = "left-pad@latest")))
        assertEquals(HostErrors.INVALID_SPEC, invalid.error!!.code)
    }

    @Test
    fun malformedRequestsAreBadRequests() = hostTest { host ->
        val requests = listOf(
            list(key = "nope"),
            list().copy(op = "delete"),
            call("echo").copy(tool = null),
            call("echo").copy(tool = ""),
            list(deadline = 0),
            list(deadline = 600_001),
            list(max = 10),
            list(max = Int.MAX_VALUE),
        )
        for (request in requests) {
            assertEquals(HostErrors.BAD_REQUEST, assertFailsWith<HostFailure> { host.supervisor.op(request) }.code)
        }
        assertFailsWith<HostFailure> { host.supervisor.retain(listOf("x")) }
        assertFailsWith<HostFailure> { host.supervisor.stop("x") }
        assertFailsWith<HostFailure> { host.supervisor.warm(WarmRequest("x", spec())) }
    }

    @Test
    fun aNewKeyStopsTheIdleProcessUsedTheLongestAgo() = hostTest(HostHarness.CONFIG.copy(maxProcesses = 1)) { host ->
        host.supervisor.op(list())
        assertNull(host.supervisor.op(list(key = OTHER)).error)
        delay(10_000)
        assertFalse(host.spawner.started.first().isAlive)
        val busy = launch { host.supervisor.op(call("slow", key = OTHER, arguments = args("ms" to 30_000))) }
        runCurrent()
        assertEquals(HostErrors.CAPACITY, host.supervisor.op(list()).error!!.code)
        busy.join()
    }

    @Test
    fun anIdleProcessStopsAndAnOldOneToo() =
        hostTest(HostHarness.CONFIG.copy(idleMillis = 60_000, maxLifetimeMillis = 30_000)) { host ->
            host.supervisor.op(list())
            delay(20_000)
            host.supervisor.op(list())
            delay(20_000)
            assertFalse(host.spawner.started.first().isAlive, "lifetime")
            assertEquals(ProcessStates.IDLE, host.supervisor.status().processes.single().state)
        }

    @Test
    fun aProcessOverItsMemoryIsStopped() = hostTest { host ->
        var reading: Long? = 100
        host.table.memory = { reading ?: error("unreadable") }
        host.supervisor.op(list())
        delay(6_000)
        assertEquals(100L, host.supervisor.status().processes.single().rssBytes)
        reading = null
        delay(6_000)
        reading = 1L shl 40
        delay(6_000)
        assertFalse(host.last().isAlive)
        assertEquals("memory", host.supervisor.status().processes.single().lastFailure)
    }

    @Test
    fun aProcessThatStopsReadingIsRestarted() = hostTest { host ->
        host.supervisor.op(call("stuck"))
        val pending = host.supervisor.op(call("echo", deadline = 30_000))
        assertNotNull(pending.error)
        delay(1_000)
        assertEquals("write_stall", host.supervisor.status().processes.single().lastFailure)
    }

    @Test
    fun aClosedInputOrOutputStopsTheProcess() = hostTest { host ->
        assertEquals("closing", host.supervisor.op(call("close-stdin")).text())
        assertNotNull(host.supervisor.op(call("echo")).error)
        delay(10_000)
        assertEquals("stdin_closed", host.supervisor.status().processes.single().lastFailure)
        val closed = host.supervisor.op(call("close-stdout", key = OTHER, deadline = 5_000))
        assertNotNull(closed.error)
        delay(10_000)
        assertEquals("stdout_closed", host.supervisor.status().processes.last().lastFailure)
    }

    @Test
    fun retainStopsWhatIsNoLongerAssignedAndRemovesItsDirectory() = hostTest { host ->
        host.supervisor.op(list())
        host.supervisor.op(list(key = OTHER))
        host.files.directories += "/var/mcp/stray"
        assertEquals(1, host.supervisor.retain(listOf(KEY)))
        delay(20_000)
        assertTrue("/var/mcp/$OTHER" in host.files.deleted)
        assertFalse("/var/mcp/stray" in host.files.deleted)
        assertEquals(listOf(KEY), host.supervisor.status().processes.map { it.key })
        assertEquals(0, host.supervisor.retain(listOf(KEY)))
    }

    @Test
    fun retainSparesAKeySeenWithinItsGrace() = hostTest(HostHarness.CONFIG.copy(retainGraceMillis = 60_000)) { host ->
        host.supervisor.op(list())
        assertEquals(0, host.supervisor.retain(emptyList()))
        delay(61_000)
        assertEquals(1, host.supervisor.retain(emptyList()))
    }

    @Test
    fun aKeyRevokedWhileItStartsIsNotKept() = hostTest { host ->
        val slow = spec("slow-start", mapOf("FIXTURE_START_DELAY" to "5000"))
        val started = async { host.supervisor.op(list(spec = slow)) }
        runCurrent()
        host.supervisor.retain(emptyList())
        assertEquals(HostErrors.SHUTTING_DOWN, started.await().error!!.code)
    }

    @Test
    fun aNewDefinitionReplacesTheProcess() = hostTest { host ->
        host.supervisor.op(list())
        assertNull(host.supervisor.op(list(spec = spec(extra = mapOf("OTHER" to "1")))).error)
        delay(10_000)
        assertEquals(2, host.spawner.started.size)
        assertFalse(host.spawner.started.first().isAlive)
    }

    @Test
    fun aDefinitionReplacedWhileItStartsFailsItsCallers() = hostTest { host ->
        val slow = spec("slow-start", mapOf("FIXTURE_START_DELAY" to "5000"))
        val old = async { host.supervisor.op(list(spec = slow)) }
        runCurrent()
        val replacing = async { host.supervisor.op(list()) }
        assertEquals(HostErrors.CRASHED, old.await().error!!.code)
        assertNull(replacing.await().error)
    }

    @Test
    fun aDefinitionReplacedWhileItFailsToStartIsNotCounted() =
        hostTest(HostHarness.CONFIG.copy(startTimeoutMillis = 3_000)) { host ->
            val stuck = host.supervisor.op(list(spec = spec("stuck-start"), deadline = 1_000))
            assertEquals(HostErrors.TIMEOUT, stuck.error!!.code)
            assertNull(host.supervisor.op(list()).error)
            delay(5_000)
            val status = host.supervisor.status().processes.single()
            assertEquals(0, status.failures)
            assertEquals(ProcessStates.READY, status.state)
        }

    @Test
    fun warmStartsTheProcessInTheBackground() = hostTest { host ->
        assertEquals(ProcessStates.IDLE, host.supervisor.warm(WarmRequest(KEY, spec())).state)
        delay(1_000)
        assertEquals(ProcessStates.READY, host.supervisor.status().processes.single().state)
        host.supervisor.warm(WarmRequest(OTHER, spec("exit-at-start")))
        delay(1_000)
        assertEquals(ProcessStates.FAILED, host.supervisor.status().processes.last().state)
        // The failed key has no process to stop.
        assertEquals(0, host.supervisor.retain(listOf(KEY)))
        host.supervisor.shutdown()
        val refused = assertFailsWith<HostFailure> { host.supervisor.warm(WarmRequest(KEY, spec())) }
        assertEquals(HostErrors.SHUTTING_DOWN, refused.code)
    }

    @Test
    fun readinessNeedsEveryLauncherCommand() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        suspend fun ready(config: HostConfig = HostHarness.CONFIG, missing: String? = null): Boolean {
            val harness = HostHarness(backgroundScope, io, { 0 }, config) { missing?.let { spawner.installed -= it } }
            return harness.supervisor.ready().also { harness.supervisor.shutdown() }
        }
        assertTrue(ready())
        assertTrue(ready(HostHarness.CONFIG.copy(prlimit = null), missing = "prlimit"))
        for (command in listOf("npx", "node", "uvx", "prlimit")) assertFalse(ready(missing = command), command)
    }

    @Test
    fun aDrainWaitsForRunningOperationsThenTheShutdownStopsEverything() = hostTest { host ->
        assertTrue(host.supervisor.ready())
        host.supervisor.op(list())
        val running = async { host.supervisor.op(call("slow", arguments = args("ms" to 8_000))) }
        runCurrent()
        host.supervisor.drain()
        assertFalse(host.supervisor.ready())
        assertTrue(host.supervisor.status().draining)
        assertEquals("done", running.await().text())
        host.supervisor.shutdown()
        host.supervisor.shutdown()
        assertFalse(host.last().isAlive)
        assertEquals(HostErrors.SHUTTING_DOWN, host.supervisor.op(list()).error!!.code)
    }

    @Test
    fun aProcessThatIgnoresSigtermAndItsDescendantsAreKilled() = hostTest { host ->
        val stubborn = spec("ignore-term")
        host.supervisor.op(list(spec = stubborn))
        val child = host.supervisor.op(call("spawn", spec = stubborn)).text().toLong()
        host.table.ignoringTerm += child
        delay(2_000)
        host.supervisor.retain(emptyList())
        delay(20_000)
        assertFalse(host.table.alive(child))
        assertTrue(child to true in host.table.signals)
        assertTrue(host.events.any { it["event"]?.jsonPrimitive?.content == "killed" })
    }

    @Test
    fun descendantsLeftAfterTheProcessExitsAreKilled() = hostTest { host ->
        host.supervisor.op(list())
        val child = host.supervisor.op(call("spawn")).text().toLong()
        host.table.ignoringTerm += child
        delay(2_000)
        host.supervisor.op(call("crash"))
        delay(10_000)
        assertFalse(host.table.alive(child))
    }

    @Test
    fun aGracefulStopWithAStubbornDescendantSignalsIt() = hostTest { host ->
        host.supervisor.op(list())
        val child = host.supervisor.op(call("spawn")).text().toLong()
        host.table.ignoringTerm += child
        delay(2_000)
        host.supervisor.stop(KEY)
        delay(10_000)
        assertTrue(child to false in host.table.signals)
        assertTrue(child to true in host.table.signals)
        assertFalse(host.table.alive(child))
    }

    @Test
    fun aFailingTickIsReportedAndTheNextOneRuns() = hostTest { host ->
        host.supervisor.op(list())
        host.table.failDescendants = true
        delay(2_000)
        host.table.failDescendants = false
        assertTrue(host.events.any { it["event"]?.jsonPrimitive?.content == "tick_failed" })
    }

    @Test
    fun aCallToADeadProcessFailsAtOnce() = hostTest(HostHarness.CONFIG.copy(maxInFlightPerProcess = 1)) { host ->
        host.supervisor.op(list(spec = spec("ignore-term")))
        val calls = listOf(
            async { host.supervisor.op(call("crash", spec = spec("ignore-term"))) },
            async { host.supervisor.op(call("echo", spec = spec("ignore-term"))) },
        ).awaitAll()
        assertTrue(calls.all { it.error != null })
    }

    @Test
    fun failuresOlderThanTheWindowDoNotCount() = hostTest { host ->
        host.supervisor.op(call("crash"))
        delay(11 * 60_000)
        repeat(3) {
            host.supervisor.op(call("crash"))
            delay(100)
        }
        assertNull(host.supervisor.op(list()).error)
        assertEquals(4, host.supervisor.status().processes.single().failures)
    }

    @Test
    fun aCallRightAfterACrashStartsAnotherProcess() = hostTest { host ->
        host.supervisor.op(call("crash"))
        assertEquals("again", host.supervisor.op(call("echo", arguments = args("text" to "again"))).text())
    }

    @Test
    fun aDrainGivesUpAfterItsLimit() = hostTest(HostHarness.CONFIG.copy(drainMillis = 6_000)) { host ->
        host.supervisor.op(list())
        val running = async { host.supervisor.op(call("slow", arguments = args("ms" to 30_000))) }
        runCurrent()
        val started = testScheduler.currentTime
        host.supervisor.drain()
        assertTrue(testScheduler.currentTime - started in 6_000L..6_100L)
        running.await()
    }

    @Test
    fun aShutdownKillsAProcessStillStarting() = hostTest { host ->
        val slow = spec("slow-start", mapOf("FIXTURE_START_DELAY" to "60000"))
        val pending = async { host.supervisor.op(list(spec = slow)) }
        runCurrent()
        val child = host.last()
        host.supervisor.shutdown()
        assertFailsWith<kotlinx.coroutines.CancellationException> { pending.await() }
        assertFalse(child.isAlive)
    }

    @Test
    fun aStartEndingDuringTheShutdownIsNotKept() = hostTest { host ->
        host.supervisor.op(list(spec = spec("ignore-term")))
        val slow = spec("slow-start", mapOf("FIXTURE_START_DELAY" to "2000"))
        val pending = async { host.supervisor.op(list(key = OTHER, spec = slow)) }
        runCurrent()
        val shutdown = launch { host.supervisor.shutdown() }
        assertEquals(HostErrors.SHUTTING_DOWN, pending.await().error!!.code)
        shutdown.join()
        assertTrue(host.spawner.started.none { it.isAlive })
    }

    @Test
    fun aProcessStillStartingTakesItsSlot() = hostTest(HostHarness.CONFIG.copy(maxProcesses = 1)) { host ->
        val slow = spec("slow-start", mapOf("FIXTURE_START_DELAY" to "5000"))
        val pending = async { host.supervisor.op(list(spec = slow)) }
        runCurrent()
        assertEquals(HostErrors.CAPACITY, host.supervisor.op(list(key = OTHER)).error!!.code)
        assertNull(pending.await().error)
    }

    @Test
    fun anIdleProcessStops() = hostTest(HostHarness.CONFIG.copy(idleMillis = 60_000)) { host ->
        host.supervisor.op(list())
        delay(62_000)
        assertFalse(host.last().isAlive)
    }

    @Test
    fun listingsAreBoundedAndChecked() = hostTest { host ->
        val endless = host.supervisor.op(list(spec = spec(extra = mapOf("FIXTURE_PAGE_SIZE" to "0"))))
        assertEquals(0, endless.tools().size)
        assertEquals(16, host.last().received.count { it["method"]?.jsonPrimitive?.content == "tools/list" })
        val pages = mapOf("FIXTURE_TOOL_COUNT" to "400", "FIXTURE_PAGE_SIZE" to "100")
        val many = host.supervisor.op(list(key = OTHER, spec = spec(extra = pages)))
        assertEquals(256, many.tools().size)
        val third = "3".repeat(64)
        val numeric =
            host.supervisor.op(list(key = third, spec = spec("numeric-cursor", mapOf("FIXTURE_PAGE_SIZE" to "5"))))
        assertEquals(5, numeric.tools().size)
        val unanswered = host.supervisor.op(list(key = "4".repeat(64), spec = spec("unanswered-list")))
        assertEquals(HostErrors.PROTOCOL, unanswered.error!!.code)
        val bare = host.supervisor.op(list(key = "5".repeat(64), spec = spec("no-capabilities")))
        assertTrue("does not offer tools" in bare.error!!.message)
    }

    @Test
    fun oversizedLinesWithoutAKnownIdFailNoCall() = hostTest(HostHarness.CONFIG.copy(maxLineBytes = 1_024)) { host ->
        for (where in listOf("huge", "unknown")) {
            val arguments = args("bytes" to 3_000, "where" to where)
            val answer = host.supervisor.op(call("oversize", deadline = 1_000, arguments = arguments))
            assertEquals(HostErrors.TIMEOUT, answer.error!!.code, where)
        }
    }

    @Test
    fun nonJsonLinesOlderThanAMinuteDoNotCount() = hostTest { host ->
        host.supervisor.op(call("spam", arguments = args("lines" to 90)))
        delay(61_000)
        host.supervisor.op(call("spam", arguments = args("lines" to 90)))
        delay(2_000)
        assertTrue(host.last().isAlive)
        assertEquals(1, host.spawner.started.size)
    }

    @Test
    fun aShutdownDuringTheLaunchLeavesNothingToKill() = hostTest { host ->
        host.files.gate = kotlinx.coroutines.CompletableDeferred()
        val pending = async { host.supervisor.op(list()) }
        runCurrent()
        host.supervisor.shutdown()
        assertFailsWith<kotlinx.coroutines.CancellationException> { pending.await() }
        assertTrue(host.spawner.started.isEmpty())
    }
}
