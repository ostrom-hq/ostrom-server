// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A registered runner on a machine (`ostrom-runner machine`): its configuration, its heartbeat and its loop. */
class MachineRunnerTest {
    private val id = Uuid.random()
    private val key = "ostrom_runner_" + "ab".repeat(32)

    @Test
    fun theConfigurationComesFromTheEnvironmentAndNeverPrintsTheKey() {
        val env = mapOf("OSTROM_SERVER_URL" to "https://api.example.com/", "OSTROM_RUNNER_ID" to id.toString())
        val inline = MachineConfig.from(env + ("OSTROM_RUNNER_KEY" to key)) { error("no file") }
        assertEquals(MachineConfig("https://api.example.com", id, key), inline)
        assertFalse(key in inline.toString())
        assertEquals(20.seconds, inline.interval)

        val fromFile = MachineConfig.from(
            env + ("OSTROM_RUNNER_KEY_FILE" to "/run/key") + ("OSTROM_RUNNER_INTERVAL_SECONDS" to "5"),
        ) { path ->
            assertEquals("/run/key", path)
            "$key\n"
        }
        assertEquals(key, fromFile.key)
        assertEquals(5.seconds, fromFile.interval)
    }

    @Test
    fun aMissingOrMalformedVariableIsRefused() {
        val ok = mapOf(
            "OSTROM_SERVER_URL" to "http://s",
            "OSTROM_RUNNER_ID" to id.toString(),
            "OSTROM_RUNNER_KEY" to key,
        )
        val broken = listOf(
            ok - "OSTROM_SERVER_URL",
            ok + ("OSTROM_SERVER_URL" to "ftp://s"),
            ok - "OSTROM_RUNNER_ID",
            ok + ("OSTROM_RUNNER_ID" to "not-a-uuid"),
            ok - "OSTROM_RUNNER_KEY",
            ok - "OSTROM_RUNNER_KEY" + ("OSTROM_RUNNER_KEY_FILE" to "/empty"),
            ok + ("OSTROM_RUNNER_INTERVAL_SECONDS" to "0"),
            ok + ("OSTROM_RUNNER_INTERVAL_SECONDS" to "31"),
            ok + ("OSTROM_RUNNER_INTERVAL_SECONDS" to "x"),
        )
        for (env in broken) {
            assertFailsWith<IllegalArgumentException>(env.toString()) { MachineConfig.from(env) { " " } }
        }
    }

    @Test
    fun theHeartbeatCarriesTheKeyToTheRunnersRouteAndTellsARefusalApart() = runTest {
        val seen = mutableListOf<String>()
        var status = HttpStatusCode.OK
        val engine = MockEngine { request ->
            seen += "${request.method.value} ${request.url.encodedPath} ${request.headers[HttpHeaders.Authorization]}"
            respond("""{"sessions":[]}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = HttpMachineApi(HttpClient(engine) { expectSuccess = false }, MachineConfig("http://s", id, key))

        api.heartbeat()
        assertEquals(listOf("${HttpMethod.Post.value} /api/v1/runners/$id/heartbeat Bearer $key"), seen)
        status = HttpStatusCode.Unauthorized
        assertFailsWith<RunnerKeyRefusedException> { api.heartbeat() }
        status = HttpStatusCode.ServiceUnavailable
        assertFailsWith<IllegalStateException> { api.heartbeat() }
    }

    @Test
    fun theMachineBeatsEveryIntervalOutlivesAnOutageAndStopsWhenItsKeyIsRefusedThreeTimesInARow() = runTest {
        val beats = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        var answer: () -> Unit = {}
        val api = object : MachineApi {
            override suspend fun heartbeat() {
                beats += testScheduler.currentTime
                answer()
            }
        }
        val machine = MachineRunner(api, 10.seconds) { lines += it }
        val code = async { machine.run() }

        runCurrent()
        advanceTimeBy(25.seconds)
        assertEquals(listOf(0L, 10_000L, 20_000L), beats, "One beat at start, then one per interval")

        answer = { error("connection refused") }
        advanceTimeBy(10.seconds)
        runCurrent()
        assertTrue("heartbeat failed: IllegalStateException: connection refused" in lines, lines.toString())
        assertFalse(code.isCompleted, "An outage does not stop the machine")

        // Two refusals then a success: a proxy's or a restarting server's 401 does not stop it.
        var refusals = 0
        answer = { if (refusals++ < 2) throw RunnerKeyRefusedException() }
        advanceTimeBy(30.seconds)
        runCurrent()
        assertFalse(code.isCompleted, "Two refusals then a success: the machine goes on")
        assertTrue("key refused (2/3)" in lines, lines.toString())

        answer = { throw RunnerKeyRefusedException() }
        advanceTimeBy(20.seconds)
        runCurrent()
        assertFalse(code.isCompleted, "The count started again after the success")
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(MachineRunner.EXIT_KEY_REFUSED, code.await())
        assertTrue(lines.any { "refused" in it }, lines.toString())
        assertFalse(lines.any { key in it })
    }
}
