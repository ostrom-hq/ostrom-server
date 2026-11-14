// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.MachineReport
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import run.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCase
import run.ostrom.server.runners.domain.RunnerAssignmentsRepository
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentitiesWith
import run.ostrom.server.runners.infrastructure.RegisteredRuntimeDriver
import run.ostrom.server.runners.presentation.RegisterRunnerRequest
import run.ostrom.server.runners.presentation.RegisteredRunnerDto
import run.ostrom.server.runners.presentation.RegisteredRunnerHeartbeatRequest
import run.ostrom.server.runners.presentation.RegisteredRunnerHeartbeatResponse
import run.ostrom.server.runners.presentation.ReportedExecutionDto
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Against a real PostgreSQL, as the serving role: cloud sessions on registered runners (`OSTROM_RUNNERS_DRIVER=
 * registered`), from the reconciler's start to a runner's heartbeat that takes the execution, its report, the loss of
 * its machine and the takeover by another, then the deletion of the workspace.
 */
class RegisteredSessionsPostgresTest {
    private val clock = SettableClock()
    private val overrides = module {
        includes(
            testIdentitiesWith(
                mapOf(
                    "OSTROM_RUNNERS_DRIVER" to "registered",
                    "OSTROM_RUNNERS_SERVER_URL" to "http://host.docker.internal:4600",
                    "OSTROM_RUNNERS_IMAGE" to "ghcr.io/ostrom-hq/ostrom-runner:test",
                ),
            ),
        )
        single<Clock> { clock }
    }

    @Test
    fun aRunnerTakesTheExecutionAnotherTakesOverWhenItDiesAndTheWorkspaceGoesLast() =
        serverTest(listOf(RunnersModule), overrides) { server ->
            val driver = assertIs<RegisteredRuntimeDriver>(server.koin.get<RuntimeDriver>())
            val sessions = server.koin.get<CloudSessionsRepository>()
            val reconcile = server.koin.get<ReconcileCloudSessionUseCase>()
            val a = server.register("dana", "machine-a")
            val b = server.register("dana", "machine-b")
            server.beat(b.id, b.token)
            val created = session().copy(prompt = "Fix the tests")
            assertTrue(sessions.createWithinCap(created, 10))
            suspend fun fresh() = sessions.getById(created.id)!!

            reconcile(fresh())
            assertEquals(ExecutionStatus.QUEUED, driver.executionStatus(fresh()))
            reconcile(fresh())
            assertEquals("queued", fresh().phase, "Waiting for a machine")
            val silent = server.beat(b.id, b.token).body<RegisteredRunnerHeartbeatResponse>()
            assertEquals(emptyList(), silent.executions, "A runner that reports nothing runs no session")

            // The first runner to ask takes it, with a token of its own and the plan the server computes.
            val taken = server.beat(a.id, a.token, RegisteredRunnerHeartbeatRequest())
            assertEquals("no-store", taken.headers[HttpHeaders.CacheControl])
            val work = taken.body<RegisteredRunnerHeartbeatResponse>()
            val plan = work.executions.single().plan!!
            assertEquals(1, work.executions.single().epoch)
            assertEquals(listOf(created.id.toString()), work.workspaces)
            assertEquals(3, plan.volumes.size)
            val runner = plan.containers.last().body
            val env = runner["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
            val token = env.single { it.startsWith("OSTROM_RUNNER_TOKEN=") }.substringAfter('=')
            assertEquals(server.koin.get<RunnerTokens>().hash(token), fresh().runnerTokenHash)
            assertTrue("OSTROM_SERVER_URL=http://host.docker.internal:4600" in env)
            assertTrue("OSTROM_AGENT_PROMPT=Fix the tests" in env)
            assertEquals("ghcr.io/ostrom-hq/ostrom-runner:test", runner["Image"]!!.jsonPrimitive.content)
            assertNull(runner["HostConfig"]!!.jsonObject["NetworkMode"], "The runner's engine chooses the network")
            assertEquals(emptyList(), server.work(b.id, b.token).executions, "Taken by one runner only")

            // Its report.
            val running = RegisteredRunnerHeartbeatRequest(
                listOf(ReportedExecutionDto(created.id.toString(), 1, "running")),
                listOf(created.id.toString()),
            )
            assertNull(server.work(a.id, a.token, running).executions.single().plan, "The plan comes once")
            assertEquals(ExecutionStatus.RUNNING, driver.executionStatus(fresh()))

            // Its machine dies: the execution is lost, and the next one goes to the other runner.
            clock.now += 91.seconds
            server.beat(b.id, b.token)
            assertEquals(ExecutionStatus.HOST_LOST, driver.executionStatus(fresh()))
            reconcile(fresh())
            reconcile(fresh())
            assertEquals(2, fresh().epoch)
            val takeover = server.work(b.id, b.token)
            assertEquals(2, takeover.executions.single().epoch)
            assertTrue(takeover.executions.single().plan != null)

            // Deleted: the workspace goes once its holder deleted it.
            val holding = RegisteredRunnerHeartbeatRequest(workspaces = listOf(created.id.toString()))
            server.work(b.id, b.token, holding)
            sessions.setDesiredState(ORG_A, created.id, DesiredState.TERMINATED, clock.now)
            reconcile(fresh())
            assertEquals(SessionState.CREATING, fresh().state, "Its workspace is still held")
            val emptied = server.work(b.id, b.token, RegisteredRunnerHeartbeatRequest())
            assertEquals(emptyList(), emptied.executions)
            assertEquals(emptyList(), emptied.workspaces)
            reconcile(fresh())
            assertEquals(SessionState.TERMINATED, fresh().state)
            assertEquals(ExecutionStatus.MISSING, driver.executionStatus(fresh()))
        }

    /**
     * Placement (PERF-RUNNERS, case 2): 50 sessions asked at once over 10 machines that beat in turn. Each heartbeat
     * takes one waiting execution: all are taken in 5 rounds, 5 per machine, none lost; at the runner's 5 s the last
     * waits 25 s, at its default 20 s, 100 s.
     */
    @Test
    fun fiftySessionsSpreadEvenlyOverTenMachinesOnePerHeartbeat() =
        serverTest(listOf(RunnersModule), overrides) { server ->
            val sessions = server.koin.get<CloudSessionsRepository>()
            val reconcile = server.koin.get<ReconcileCloudSessionUseCase>()
            val machines = List(MACHINES) { server.register("dana", "machine-$it") }
            val asked = List(ASKED) { session().also { assertTrue(sessions.createWithinCap(it, ASKED)) } }
            asked.forEach { reconcile(sessions.getById(it.id)!!) }

            val takenIn = mutableMapOf<String, Int>()
            var round = 0
            while (takenIn.size < ASKED && round < ASKED) {
                round++
                for (machine in machines.shuffled()) {
                    val work = server.work(machine.id, machine.token)
                    work.executions.forEach { takenIn.getOrPut(it.sessionId) { round } }
                }
            }
            assertEquals(ASKED / MACHINES, round, "Rounds of heartbeats until every session is taken")
            val held = machines.map { server.work(it.id, it.token).executions.size }
            assertEquals(List(MACHINES) { ASKED / MACHINES }, held, "Spread evenly")
            assertEquals(asked.map { it.id.toString() }.toSet(), takenIn.keys, "None lost")
            val rounds = takenIn.values.sorted()
            val (p50, p95) = rounds[ASKED / 2] to rounds[ASKED * 95 / 100 - 1]
            println("placement: heartbeats until taken p50 $p50, p95 $p95; held per machine $held")
        }

    /**
     * PERF-RUNNERS, case 2, timed: 50 sessions queued for 10 machines that wait for work (`?wait=`), as the runner
     * does, each asking again at once after a held or fruitful answer and at its 20 s interval otherwise. Every session
     * is handed over within seconds of being queued, not at the machines' next heartbeat.
     */
    @Test
    fun waitingMachinesAreHandedFiftySessionsWithinSeconds() = serverTest(listOf(RunnersModule), overrides) { server ->
        val sessions = server.koin.get<CloudSessionsRepository>()
        val reconcile = server.koin.get<ReconcileCloudSessionUseCase>()
        val heartbeat = server.koin.get<RegisteredRunnerHeartbeatUseCase>()
        val machines = List(MACHINES) { server.register("dana", "machine-$it") }
        val handed = Channel<Pair<String, TimeSource.Monotonic.ValueTimeMark>>(Channel.UNLIMITED)
        val delays = mutableListOf<Duration>()
        coroutineScope {
            val loops = machines.map { machine -> launch { waitForWork(heartbeat, machine, handed) } }
            delay(SETTLE)
            val queued = List(ASKED) {
                val asked = session()
                assertTrue(sessions.createWithinCap(asked, ASKED))
                val at = TimeSource.Monotonic.markNow()
                reconcile(sessions.getById(asked.id)!!)
                asked.id.toString() to at
            }.toMap()
            withTimeoutOrNull(HANDOVER_BOUND) {
                while (delays.size < ASKED) {
                    val (id, at) = handed.receive()
                    delays += at - queued.getValue(id)
                }
            }
            loops.forEach { it.cancel() }
        }
        assertEquals(ASKED, delays.size, "Handed over within $HANDOVER_BOUND")
        val sorted = delays.sorted()
        val (p50, p95) = sorted[ASKED / 2] to sorted[ASKED * 95 / 100 - 1]
        println("placement, waiting machines: queued until handed over p50 $p50, p95 $p95")
        assertTrue(p95 < P95_BOUND, "p95 $p95")
    }

    @Test
    fun aHeartbeatWaitsWhatItAsksWithinTheBoundsThenAnswers() = serverTest(listOf(RunnersModule), overrides) { server ->
        val machine = server.register("dana", "machine")
        val report = RegisteredRunnerHeartbeatRequest()
        suspend fun timed(wait: Int?): Duration {
            val mark = TimeSource.Monotonic.markNow()
            val response = server.beat(machine.id, machine.token, report, wait)
            assertEquals(HttpStatusCode.OK, response.status)
            return mark.elapsedNow()
        }
        assertTrue(timed(-5) < 1.seconds, "Below the bounds: no wait")
        assertTrue(timed(1) >= 1.seconds, "Nothing to hand over: it waits")
        assertTrue(timed(null) < 1.seconds, "Absent: no wait")
    }

    @Test
    fun hasWorkUntilTheExecutionIsHandedOver() = serverTest(listOf(RunnersModule), overrides) { server ->
        val sessions = server.koin.get<CloudSessionsRepository>()
        val assignments = server.koin.get<RunnerAssignmentsRepository>()
        val machine = server.register("dana", "machine")
        assertFalse(assignments.hasWork(ORG_A))
        val asked = session().also { assertTrue(sessions.createWithinCap(it, 1)) }
        server.koin.get<ReconcileCloudSessionUseCase>()(sessions.getById(asked.id)!!)
        assertTrue(assignments.hasWork(ORG_A), "Queued")
        assertEquals(1, server.work(machine.id, machine.token).executions.size)
        assertFalse(assignments.hasWork(ORG_A), "Handed over")
    }

    @Test
    fun runnersAskingAtOnceNeverTakeTheSameExecution() = serverTest(listOf(RunnersModule), overrides) { server ->
        val sessions = server.koin.get<CloudSessionsRepository>()
        val reconcile = server.koin.get<ReconcileCloudSessionUseCase>()
        val machines = List(RACERS) { server.register("dana", "machine-$it") }
        val waiting = List(WAITING) { session().also { assertTrue(sessions.createWithinCap(it, RACERS)) } }
        waiting.forEach { reconcile(sessions.getById(it.id)!!) }

        // Every runner's exchange at once, on the pool (the test server serves its HTTP calls one after another),
        // twice: each execution goes to one runner, one execution per runner and heartbeat.
        val assignments = server.koin.get<RunnerAssignmentsRepository>()
        repeat(2) {
            coroutineScope {
                machines.map { machine ->
                    async { assignments.exchange(ORG_A, Uuid.parse(machine.id), MachineReport()).size }
                }.awaitAll()
            }.forEach { held -> assertTrue(held <= 2, "One more execution per heartbeat at most") }
        }
        val holders = machines.flatMap { machine ->
            server.work(machine.id, machine.token).executions.map { it.sessionId to machine.id }
        }
        assertEquals(waiting.map { it.id.toString() }.toSet(), holders.map { it.first }.toSet())
        assertEquals(WAITING, holders.size, "No execution held twice: $holders")
    }

    @Test
    fun aRunnerBackAfterItsSessionMovedDeletesItsWorkspace() = serverTest(listOf(RunnersModule), overrides) { server ->
        val sessions = server.koin.get<CloudSessionsRepository>()
        val reconcile = server.koin.get<ReconcileCloudSessionUseCase>()
        val a = server.register("dana", "machine-a")
        val b = server.register("dana", "machine-b")
        val created = session()
        assertTrue(sessions.createWithinCap(created, 10))
        reconcile(sessions.getById(created.id)!!)
        val id = created.id.toString()
        val running = RegisteredRunnerHeartbeatRequest(listOf(ReportedExecutionDto(id, 1, "running")), listOf(id))
        server.work(a.id, a.token, running)
        server.work(a.id, a.token, running)

        clock.now += 91.seconds
        server.work(b.id, b.token)
        reconcile(sessions.getById(created.id)!!)
        reconcile(sessions.getById(created.id)!!)
        assertEquals(listOf(2L), server.work(b.id, b.token).executions.map { it.epoch })

        // a comes back: nothing to run, and the workspace it still holds is b's session now, to delete.
        val back = server.work(a.id, a.token, running)
        assertEquals(emptyList(), back.executions)
        assertEquals(emptyList(), back.workspaces)
        assertEquals(listOf(id), server.work(b.id, b.token).workspaces)
    }

    @Test
    fun anotherOrganizationsRunnerNeverSeesTheExecution() = serverTest(listOf(RunnersModule), overrides) { server ->
        val sessions = server.koin.get<CloudSessionsRepository>()
        val reconcile = server.koin.get<ReconcileCloudSessionUseCase>()
        val carols = server.register("carol", "machine-c")
        val created = session()
        assertTrue(sessions.createWithinCap(created, 10))
        reconcile(sessions.getById(created.id)!!)

        assertEquals(emptyList(), server.work(carols.id, carols.token).executions)
        // As the serving role, without an organization, the table shows nothing.
        val direct = server.driver.fetchAll(Statement.create("SELECT session_id FROM runners_assignments")).getOrThrow()
        assertEquals(0, direct.size)

        // Its own organization's runner takes it, but a newer execution replaced it: never handed over.
        val stale = sessions.getById(created.id)!!
        assertTrue(sessions.compareAndSet(stale.copy(epoch = 2), null) != null)
        val danas = server.register("dana", "machine-a")
        assertEquals(emptyList(), server.work(danas.id, danas.token).executions)
        assertEquals(emptyList(), server.work(danas.id, danas.token).executions)
        assertEquals(listOf(created.id.toString()), server.work(danas.id, danas.token).workspaces)
    }

    /** PERF-RUNNERS, case 2: 12 sessions on 2 machines of 5 places: 10 run, 2 wait, none lost, and the queue moves. */
    @Test
    fun machinesTakeNoMoreThanTheirCapacityAndTheRestWaits() = serverTest(listOf(RunnersModule), overrides) { server ->
        val sessions = server.koin.get<CloudSessionsRepository>()
        val reconcile = server.koin.get<ReconcileCloudSessionUseCase>()
        val machines = List(2) { server.register("dana", "machine-$it") }
        val asked = List(12) { session().also { assertTrue(sessions.createWithinCap(it, 12)) } }
        asked.forEach { reconcile(sessions.getById(it.id)!!) }
        val five = RegisteredRunnerHeartbeatRequest(capacity = 5)

        repeat(6) { machines.forEach { server.work(it.id, it.token, five) } }
        val held = machines.map { m -> server.work(m.id, m.token, five).executions.map { it.sessionId } }
        assertEquals(listOf(5, 5), held.map { it.size }, "Never more than 5 each")
        val waiting = asked.map { it.id.toString() } - held.flatten().toSet()
        assertEquals(2, waiting.size, "Two wait, none lost")

        // One ends: its machine takes one that waited at its next heartbeat.
        val ended = held.first().first()
        sessions.setDesiredState(ORG_A, Uuid.parse(ended), DesiredState.TERMINATED, clock.now)
        reconcile(sessions.getById(Uuid.parse(ended))!!)
        val next = server.work(machines.first().id, machines.first().token, five).executions.map { it.sessionId }
        assertEquals(5, next.size)
        assertTrue(ended !in next && next.any { it in waiting }, "The queue moves: $next")
    }

    @Test
    fun aMalformedReportIsABadRequest() = serverTest(listOf(RunnersModule), overrides) { server ->
        val a = server.register("dana", "machine-a")
        val reports = listOf(
            RegisteredRunnerHeartbeatRequest(listOf(ReportedExecutionDto("not-a-uuid", 1, "running"))),
            RegisteredRunnerHeartbeatRequest(listOf(ReportedExecutionDto(created(), 1, "missing"))),
            RegisteredRunnerHeartbeatRequest(listOf(ReportedExecutionDto(created(), 1, "exited"))),
            RegisteredRunnerHeartbeatRequest(workspaces = listOf("not-a-uuid")),
            RegisteredRunnerHeartbeatRequest(capacity = -1),
            RegisteredRunnerHeartbeatRequest(capacity = 257),
        )
        for (report in reports) assertEquals(HttpStatusCode.BadRequest, server.beat(a.id, a.token, report).status)
        assertEquals(HttpStatusCode.OK, server.beat(a.id, a.token, RegisteredRunnerHeartbeatRequest()).status)
        for (capacity in listOf(0, 256)) {
            val accepted = server.beat(a.id, a.token, RegisteredRunnerHeartbeatRequest(capacity = capacity))
            assertEquals(HttpStatusCode.OK, accepted.status)
        }
    }

    private fun created() = Uuid.random().toString()

    private companion object {
        const val MACHINES = 10
        const val ASKED = 50
        const val RACERS = 10
        const val WAITING = 3
        val WAIT = 20.seconds
        val INTERVAL = 20.seconds
        val SETTLE = 1.seconds
        val HANDOVER_BOUND = 10.seconds
        val P95_BOUND = 5.seconds
    }

    /** A machine as the runner drives it: a heartbeat that may wait, again at once after a held or fruitful one. */
    private suspend fun waitForWork(
        heartbeat: RegisteredRunnerHeartbeatUseCase,
        machine: RegisteredRunnerDto,
        handed: SendChannel<Pair<String, TimeSource.Monotonic.ValueTimeMark>>,
    ) {
        while (true) {
            val asked = TimeSource.Monotonic.markNow()
            val work = heartbeat(Uuid.parse(machine.id), machine.token, MachineReport(), WAIT)
            val fresh = work.executions.filter { it.plan != null }
            fresh.forEach { handed.send(it.sessionId.toString() to TimeSource.Monotonic.markNow()) }
            if (fresh.isEmpty() && asked.elapsedNow() < WAIT) delay(INTERVAL)
        }
    }

    private suspend fun TestServer.register(token: String, name: String): RegisteredRunnerDto {
        val response = client.post("/api/v1/runners") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(RegisterRunnerRequest(name, emptyList()))
        }
        assertEquals(HttpStatusCode.Created, response.status, "$token registers $name")
        return response.body()
    }

    /** A heartbeat; without a report, no body, as a runner that runs no session sends it. */
    private suspend fun TestServer.beat(
        id: String,
        key: String,
        report: RegisteredRunnerHeartbeatRequest? = null,
        wait: Int? = null,
    ): HttpResponse = client.post("/api/v1/runners/$id/heartbeat" + (wait?.let { "?wait=$it" } ?: "")) {
        bearerAuth(key)
        if (report != null) {
            contentType(ContentType.Application.Json)
            setBody(report)
        }
    }

    private suspend fun TestServer.work(
        id: String,
        key: String,
        report: RegisteredRunnerHeartbeatRequest = RegisteredRunnerHeartbeatRequest(),
    ): RegisteredRunnerHeartbeatResponse {
        val response = beat(id, key, report)
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }
}
