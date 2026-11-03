// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun aDockerEngineMakesTheRunnerRunSessions() {
        val env = mapOf(
            "OSTROM_SERVER_URL" to "http://s",
            "OSTROM_RUNNER_ID" to id.toString(),
            "OSTROM_RUNNER_KEY" to key,
            "OSTROM_DOCKER_API_URL" to "http://127.0.0.1:2375/",
        )
        val plain = MachineConfig.from(env) { error("no file") }
        assertEquals(MachineDocker("http://127.0.0.1:2375", null, null, null), plain.docker)
        assertTrue("docker=MachineDocker(apiUrl=http://127.0.0.1:2375" in plain.toString())
        val placed = MachineConfig.from(
            env + ("OSTROM_DOCKER_NETWORK" to "sessions") + ("OSTROM_RUNNER_SESSION_IMAGE" to "img:1"),
        ) { error("no file") }
        assertEquals(MachineDocker("http://127.0.0.1:2375", "sessions", "img:1", null), placed.docker)
        for (max in listOf(0, 256)) {
            val capped = MachineConfig.from(env + ("OSTROM_RUNNER_MAX_SESSIONS" to "$max")) { error("no file") }
            assertEquals(max, capped.docker?.maxSessions)
        }
        for (max in listOf("-1", "257", "two")) {
            assertFailsWith<IllegalArgumentException> {
                MachineConfig.from(env + ("OSTROM_RUNNER_MAX_SESSIONS" to max)) { error("no file") }
            }
        }
        assertEquals(null, MachineConfig.from(env + ("OSTROM_DOCKER_API_URL" to " ")) { error("no file") }.docker)
        val remote = MachineConfig.from(env + ("OSTROM_DOCKER_API_URL" to "https://docker.lan")) { error("no file") }
        assertEquals("https://docker.lan", remote.docker?.apiUrl)
        assertFailsWith<IllegalArgumentException> {
            MachineConfig.from(env + ("OSTROM_DOCKER_API_URL" to "unix:///var/run/docker.sock")) { error("no file") }
        }
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
            ok + ("OSTROM_RUNNER_ID" to " "),
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

        assertEquals(MachineWork(), api.heartbeat(null))
        assertEquals(listOf("${HttpMethod.Post.value} /api/v1/runners/$id/heartbeat Bearer $key"), seen)
        status = HttpStatusCode.Unauthorized
        assertFailsWith<RunnerKeyRefusedException> { api.heartbeat(null) }
        status = HttpStatusCode.ServiceUnavailable
        assertFailsWith<IllegalStateException> { api.heartbeat(null) }
    }

    @Test
    fun theMachineBeatsEveryIntervalOutlivesAnOutageAndStopsWhenItsKeyIsRefusedThreeTimesInARow() = runTest {
        val beats = mutableListOf<Long>()
        val lines = mutableListOf<String>()
        var answer: () -> Unit = {}
        val api = object : MachineApi {
            override suspend fun heartbeat(report: MachineReport?): MachineWork {
                assertEquals(null, report, "A runner without Docker reports nothing")
                beats += testScheduler.currentTime
                answer()
                return MachineWork()
            }
        }
        val machine = MachineRunner(api, 10.seconds, { lines += it })
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

    @Test
    fun theReportIsTheBodyAndTheAnswerTheWork() = runTest {
        var sent = ""
        val engine = MockEngine { request ->
            assertTrue(request.body.contentType!!.match(ContentType.Application.Json))
            sent = request.body.toByteArray().decodeToString()
            respond(
                """{"sessions":[],"executions":[{"sessionId":"s","epoch":2}],"workspaces":["s"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = HttpMachineApi(HttpClient(engine) { expectSuccess = false }, MachineConfig("http://s", id, key))
        val work = api.heartbeat(MachineReport(listOf(ReportedExecution("s", 2, "running")), listOf("s"), 3))
        assertEquals(MachineWork(listOf(AssignedExecution("s", 2)), listOf("s")), work)
        assertEquals(
            """{"executions":[{"sessionId":"s","epoch":2,"state":"running"}],"workspaces":["s"]}""",
            sent,
        )
    }

    @Test
    fun aMachineWithDockerReportsWhatItRunsAndAppliesTheAnswerInTheBackground() = runTest {
        val session = Uuid.random()
        val docker = FakeDockerEngine()
        val lines = mutableListOf<String>()
        val reports = mutableListOf<MachineReport?>()
        val handed = AssignedExecution(session.toString(), 1, plan(session, 1))
        var answer: () -> MachineWork = { MachineWork(listOf(handed)) }
        val api = object : MachineApi {
            override suspend fun heartbeat(report: MachineReport?): MachineWork {
                reports += report
                return answer()
            }
        }
        val sessions = MachineSessions(docker, PlanGuard(id, null, null)) { lines += it }
        val machine = MachineRunner(api, 10.seconds, { lines += it }, sessions)
        val code = async { machine.run() }

        runCurrent()
        assertEquals(MachineReport(emptyList(), emptyList(), 1), reports.single())
        assertEquals(3, docker.containers.size, "Applied after the beat, in the background")

        answer = { MachineWork(listOf(AssignedExecution(session.toString(), 1))) }
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals("running", reports.last()!!.executions.single().state)

        // The engine cannot be read: a beat without a report, and nothing applied.
        val engineDown = object : DockerEngine by docker {
            override suspend fun containers(): List<HeldContainer> = error("socket closed")
        }
        val unread = MachineSessions(engineDown, PlanGuard(id, null, null)) {}
        val blind = MachineRunner(api, 10.seconds, { lines += it }, unread)
        val blindCode = async { blind.run() }
        runCurrent()
        assertEquals(null, reports.last())
        assertTrue(lines.any { "docker unreadable, no session this time" in it }, lines.toString())
        assertEquals(3, docker.containers.size, "An unread engine is never swept")

        // An answer that cannot be applied is logged; the beats go on.
        docker.failRemove = IllegalStateException("engine busy")
        answer = { MachineWork() }
        advanceTimeBy(10.seconds)
        runCurrent()
        assertTrue(lines.any { "sessions not applied: IllegalStateException: engine busy" in it }, lines.toString())

        answer = { throw RunnerKeyRefusedException() }
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(MachineRunner.EXIT_KEY_REFUSED, code.await())
        assertEquals(MachineRunner.EXIT_KEY_REFUSED, blindCode.await())
    }

    @Test
    fun aCancelledMachineStopsWhileReadingOrApplying() = runTest {
        val api = object : MachineApi {
            override suspend fun heartbeat(report: MachineReport?) = MachineWork()
        }
        val reading = object : DockerEngine by FakeDockerEngine() {
            override suspend fun containers(): List<HeldContainer> = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> {
            MachineRunner(api, 10.seconds, {}, MachineSessions(reading, PlanGuard(id, null, null)) {}).run()
        }
        var reads = 0
        val applying = object : DockerEngine by FakeDockerEngine() {
            // The report reads first, then the background pass.
            override suspend fun containers(): List<HeldContainer> =
                if (reads++ == 0) emptyList() else throw CancellationException("stop")
        }
        val stopped = async {
            MachineRunner(api, 10.seconds, {}, MachineSessions(applying, PlanGuard(id, null, null)) {}).run()
        }
        runCurrent()
        stopped.cancel()
        assertTrue(stopped.isCancelled)
    }
}

