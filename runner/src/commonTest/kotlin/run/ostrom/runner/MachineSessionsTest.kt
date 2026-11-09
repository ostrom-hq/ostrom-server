// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** An engine in memory: only this runner's objects, as [HttpDockerEngine] lists them. */
internal class FakeDockerEngine : DockerEngine {
    val containers = mutableListOf<HeldContainer>()
    val volumes = mutableListOf<HeldVolume>()
    val created = mutableListOf<PlannedObject>()
    var failStart: Exception? = null
    var failRemove: Exception? = null
    var onStart: suspend (PlannedObject) -> Unit = {}

    var info: EngineInfo? = EngineInfo(cpus = 2, memoryBytes = 8L shl 30)

    override suspend fun containers(): List<HeldContainer> = containers.toList()

    override suspend fun info(): EngineInfo =
        info ?: throw DockerApiException("read the engine", HttpStatusCode.Forbidden)

    override suspend fun volumes(): List<HeldVolume> = volumes.toList()

    override suspend fun createVolume(volume: PlannedObject) {
        created += volume
        if (volumes.none { it.name == volume.name }) volumes += HeldVolume(volume.name, session(volume.body))
    }

    override suspend fun startContainer(container: PlannedObject) {
        failStart?.let { throw it }
        onStart(container)
        created += container
        val labels = container.body["Labels"]!!.jsonObject
        val epoch = (labels["ostrom.run/epoch"] as JsonPrimitive).content.toLong()
        containers += HeldContainer("id-${container.name}", container.name, session(container.body), epoch, "running")
    }

    override suspend fun removeContainer(id: String) {
        failRemove?.let { throw it }
        containers.removeAll { it.id == id }
    }

    override suspend fun removeVolume(name: String) {
        volumes.removeAll { it.name == name }
    }

    private fun session(body: JsonObject): Uuid =
        Uuid.parseHex((body["Labels"]!!.jsonObject["ostrom.run/session"] as JsonPrimitive).content)
}

class MachineSessionsTest {
    private val runner = Uuid.random()
    private val a = Uuid.random()
    private val b = Uuid.random()
    private val lines = mutableListOf<String>()
    private val engine = FakeDockerEngine()
    private val sessions = MachineSessions(engine, PlanGuard(runner, null, null), null) { lines += it }

    private fun work(vararg executions: AssignedExecution, workspaces: List<Uuid> = emptyList()) =
        MachineWork(executions.toList(), workspaces.map(Uuid::toString))

    private fun handed(session: Uuid, epoch: Long) = AssignedExecution(session.toString(), epoch, plan(session, epoch))

    private fun held(session: Uuid, epoch: Long) = AssignedExecution(session.toString(), epoch)

    private fun state(report: MachineReport, session: Uuid, epoch: Long) =
        report.executions.single { it.sessionId == session.toString() && it.epoch == epoch }.state

    @Test
    fun aHandedOverExecutionIsCreatedAsThisRunnersAndReportedRunning() = runTest {
        assertEquals(MachineReport(emptyList(), emptyList(), 1), sessions.report())

        val first = work(handed(a, 1))
        sessions.receive(first)
        assertEquals("pending", state(sessions.report(), a, 1), "Received, not created yet")

        sessions.apply(first)
        assertEquals(6, engine.created.size, "Three volumes, then three containers")
        assertTrue(engine.created.all { PlanGuard.RUNNER_LABEL in it.body["Labels"]!!.jsonObject })
        val report = sessions.report()
        assertEquals("running", state(report, a, 1))
        assertEquals(listOf(a.toString()), report.workspaces, "Its three volumes are one workspace")
        assertTrue(lines.any { "started session $a epoch 1" in it }, lines.toString())

        // The next answers name it without its plan: nothing is created again.
        sessions.receive(work(held(a, 1)))
        sessions.apply(work(held(a, 1)))
        assertEquals(6, engine.created.size)
    }

    @Test
    fun theEnginesStateIsWhatTheServerReads() = runTest {
        fun container(session: Uuid, epoch: Long, state: String, suffix: String = ""): HeldContainer {
            val name = "ostrom-s-${session.toHexString()}-e$epoch$suffix"
            return HeldContainer("id-$name", name, session, epoch, state)
        }
        engine.containers += listOf(
            container(a, 1, "running"),
            container(a, 2, "paused"),
            container(a, 3, "restarting"),
            container(a, 4, "created"),
            container(a, 5, "exited"),
            // Only its agent's container is left: the execution has ended.
            container(b, 1, "running", "-agent"),
        )
        val report = sessions.report()
        val states = (1L..5L).map { state(report, a, it) }
        assertEquals(listOf("running", "running", "running", "pending", "ended"), states)
        assertEquals("ended", state(report, b, 1))
    }

    @Test
    fun anExecutionBeingCreatedIsPendingWhateverTheEngineAlreadyShows() = runTest {
        val first = work(handed(a, 1))
        sessions.receive(first)
        var during: MachineReport? = null
        engine.onStart = { planned ->
            // The report while the last container, the runner's, is being started.
            if (planned.name == "ostrom-s-${a.toHexString()}-e1") during = sessions.report()
        }
        sessions.apply(first)
        assertEquals("pending", state(during!!, a, 1))
    }

    @Test
    fun whatTheServerNoLongerListsIsDeletedAndAWorkspaceIsKeptWhileListed() = runTest {
        sessions.receive(work(handed(a, 1), handed(b, 1)))
        sessions.apply(work(handed(a, 1), handed(b, 1)))
        assertEquals(6, engine.containers.size)

        // a is paused: its execution goes, its workspace stays. b is gone.
        sessions.receive(work(workspaces = listOf(a)))
        sessions.apply(work(workspaces = listOf(a)))
        assertEquals(emptyList(), engine.containers)
        assertEquals(setOf(a), engine.volumes.map { it.sessionId }.toSet())

        sessions.apply(work())
        assertEquals(emptyList(), engine.volumes)
    }

    @Test
    fun aPlanTheServerTakesBackBeforeItIsCreatedIsForgotten() = runTest {
        sessions.receive(work(handed(a, 1)))
        sessions.receive(work())
        assertEquals(emptyList(), sessions.report().executions)
        sessions.apply(work(held(a, 1)))
        assertEquals(emptyList(), engine.created, "No plan left to create")
    }

    @Test
    fun aRefusedOrFailedPlanIsLoggedAndNeverRetried() = runTest {
        val root = plan(a, 1).let { sent ->
            val runnerContainer = sent.containers.last()
            val body = JsonObject(runnerContainer.body + ("User" to JsonPrimitive("0:0")))
            sent.copy(containers = sent.containers.dropLast(1) + PlannedObject(runnerContainer.name, body))
        }
        val refused = work(AssignedExecution(a.toString(), 1, root))
        sessions.receive(refused)
        sessions.apply(refused)
        assertTrue(lines.any { "refused the plan of session $a epoch 1" in it }, lines.toString())
        assertEquals(emptyList(), sessions.report().executions, "Reported missing: the server moves it on")

        engine.failStart = DockerApiException("create container", HttpStatusCode.InternalServerError)
        val failed = work(handed(b, 1))
        sessions.receive(failed)
        sessions.apply(failed)
        assertTrue(
            lines.any { "could not start session $b epoch 1: DockerApiException" in it && "answered 500" in it },
            lines.toString(),
        )
        assertTrue(sessions.report().executions.none { it.sessionId == b.toString() })
    }

    @Test
    fun aCancelledCreationIsNotSwallowed() = runTest {
        engine.failStart = CancellationException("stop")
        val first = work(handed(a, 1))
        sessions.receive(first)
        assertFailsWith<CancellationException> { sessions.apply(first) }
    }

    @Test
    fun theCapacityIsTheOneSetOrWhatTheMachineHoldsReadOnce() = runTest {
        // 2 CPUs and 4.5 GiB per session: 16 CPUs and 64 GiB hold 8, 16 CPUs and 16 GiB hold 3.
        engine.info = EngineInfo(cpus = 16, memoryBytes = 64L shl 30)
        assertEquals(8, sessions.report().capacity)
        engine.info = EngineInfo(cpus = 16, memoryBytes = 16L shl 30)
        assertEquals(8, sessions.report().capacity, "Read once")
        assertEquals(3, MachineSessions(engine, PlanGuard(runner, null, null), null) {}.report().capacity)
        // A machine too small for one still runs one; a capacity set wins over the machine's.
        engine.info = EngineInfo(cpus = 1, memoryBytes = 2L shl 30)
        assertEquals(1, MachineSessions(engine, PlanGuard(runner, null, null), null) {}.report().capacity)
        assertEquals(0, MachineSessions(engine, PlanGuard(runner, null, null), 0) {}.report().capacity)

        // A socket proxy that hides the engine's info: no capacity (no limit), said once.
        engine.info = null
        val hidden = MachineSessions(engine, PlanGuard(runner, null, null), null) { lines += it }
        assertEquals(null, hidden.report().capacity)
        engine.info = EngineInfo(cpus = 16, memoryBytes = 64L shl 30)
        assertEquals(null, hidden.report().capacity, "Read once")
        assertEquals(1, lines.count { "OSTROM_RUNNER_MAX_SESSIONS" in it }, lines.toString())
    }
}
