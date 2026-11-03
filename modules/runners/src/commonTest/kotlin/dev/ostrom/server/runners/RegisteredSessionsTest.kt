// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.AssignedExecution
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.ExecutionPlan
import dev.ostrom.server.runners.domain.ExecutionStatus
import dev.ostrom.server.runners.domain.MachineExecutionState
import dev.ostrom.server.runners.domain.MachineReport
import dev.ostrom.server.runners.domain.MachineWork
import dev.ostrom.server.runners.domain.NewRunner
import dev.ostrom.server.runners.domain.PlannedObject
import dev.ostrom.server.runners.domain.RegisterRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCaseImpl
import dev.ostrom.server.runners.domain.RunnerRegistration
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.runners.domain.executionSpec
import dev.ostrom.server.runners.fakes.FakeRunnerTokens
import dev.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import dev.ostrom.server.runners.fakes.InMemoryRunnerAssignments
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.infrastructure.RegisteredRuntimeDriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Cloud sessions on registered runners: the driver, and the heartbeat that hands executions over, on fakes. */
class RegisteredSessionsTest {
    private val clock = SettableClock()
    private val runners = InMemoryRegisteredRunners()
    private val tokens = FakeRunnerTokens()
    private val sessions = InMemoryCloudSessionsRepository()
    private val assignments = InMemoryRunnerAssignments { id ->
        runners.runners.firstOrNull { it.id == id }?.lastSeenAt
    }
    private val driver = RegisteredRuntimeDriver(assignments, clock)
    private val register = RegisterRunnerUseCaseImpl(runners, tokens, clock)
    private val heartbeat = RegisteredRunnerHeartbeatUseCaseImpl(
        runners,
        tokens,
        clock,
        assignments,
        sessions,
    ) { spec ->
        val runner = buildJsonObject {
            put("token", spec.runnerToken)
            put("prompt", spec.prompt)
        }
        ExecutionPlan(listOf(PlannedObject("ws", buildJsonObject {})), listOf(PlannedObject("runner", runner)))
    }

    private suspend fun runner(name: String, org: Uuid = ORG_A) =
        register(Caller(org, "dana", admin = true), RunnerRegistration(name, emptyList(), null))

    private suspend fun beat(runner: NewRunner, report: MachineReport = MachineReport()) =
        heartbeat(runner.runner.id, runner.key, report)

    /** A session whose first execution the reconciler just started, as it stores it. */
    private suspend fun starting(org: Uuid = ORG_A) = session(orgId = org)
        .copy(epoch = 1, executionStartedAt = clock.now, runnerTokenHash = "hash(reconciler)", prompt = "Fix it")
        .also {
            sessions.put(it)
            driver.startExecution(it.executionSpec("reconciler"))
        }

    private fun report(session: CloudSession, state: MachineExecutionState) =
        MachineReport(mapOf((session.id to 1L) to state), setOf(session.id))

    @Test
    fun theFirstRunnerToAskTakesTheExecutionWithAFreshTokenThenHoldsItWithoutThePlan() = runTest {
        val a = runner("a")
        val b = runner("b")
        val session = starting()
        assertEquals(ExecutionStatus.PENDING, driver.executionStatus(session))

        val work = beat(a)

        val plan = work.executions.single().plan!!
        assertEquals(session.id to 1L, work.executions.single().let { it.sessionId to it.epoch })
        val token = tokens.minted.last()
        assertEquals(token, plan.containers.single().body["token"]!!.jsonPrimitive.content)
        assertEquals("Fix it", plan.containers.single().body["prompt"]!!.jsonPrimitive.content)
        assertEquals("hash($token)", sessions.sessions.getValue(session.id).runnerTokenHash)
        assertEquals(listOf(session.id), work.workspaces)
        val other = beat(b)
        assertEquals(MachineWork(emptyList(), emptyList()), other, "Taken by one runner only")

        val again = beat(a)
        assertEquals(listOf(AssignedExecution(session.id, 1, null)), again.executions, "The plan comes once")
        assertEquals(ExecutionStatus.ENDED, driver.executionStatus(session), "Handed over, not reported: missing")

        beat(a, report(session, MachineExecutionState.RUNNING))
        assertEquals(ExecutionStatus.RUNNING, driver.executionStatus(session))
        beat(a, report(session, MachineExecutionState.PENDING))
        assertEquals(ExecutionStatus.PENDING, driver.executionStatus(session))
        beat(a, report(session, MachineExecutionState.ENDED))
        assertEquals(ExecutionStatus.ENDED, driver.executionStatus(session))
    }

    @Test
    fun aRunnerSilentForTheWindowIsLostAndTheNextExecutionGoesToAnother() = runTest {
        val a = runner("a")
        val b = runner("b")
        val session = starting()
        beat(a)
        beat(b)
        assertTrue(driver.relocates)

        clock.now += 90.seconds
        beat(b)
        assertEquals(ExecutionStatus.PENDING, driver.executionStatus(session), "Online at the edge of the window")
        clock.now += 1.seconds
        assertEquals(ExecutionStatus.HOST_LOST, driver.executionStatus(session))

        // The reconciler stops the lost execution and starts the next one.
        driver.stopExecutions(session)
        val next = sessions.sessions.getValue(session.id).copy(epoch = 2, state = SessionState.RESUMING)
        sessions.put(next)
        driver.startExecution(next.executionSpec("reconciler"))
        val taken = beat(b)
        assertEquals(listOf(2L), taken.executions.map { it.epoch })
        assertEquals(JsonNull, taken.executions.single().plan!!.containers.single().body["prompt"], "First one only")
    }

    @Test
    fun aRunnerBackAfterBeingDeclaredLostDestroysWhatItHeldAndNeverRunsItAgain() = runTest {
        val a = runner("a")
        val b = runner("b")
        val session = starting()
        beat(a, report(session, MachineExecutionState.RUNNING))
        beat(a, report(session, MachineExecutionState.RUNNING))
        clock.now += 91.seconds
        beat(b)
        assertEquals(ExecutionStatus.HOST_LOST, driver.executionStatus(session))
        driver.stopExecutions(session)
        val next = sessions.sessions.getValue(session.id).copy(epoch = 2, state = SessionState.RESUMING)
        sessions.put(next)
        driver.startExecution(next.executionSpec("reconciler"))
        assertEquals(listOf(2L), beat(b).executions.map { it.epoch })

        // a comes back with the execution and the workspace it had: it must delete both, and gets nothing to run.
        val back = beat(a, report(session, MachineExecutionState.RUNNING))
        assertEquals(MachineWork(emptyList(), emptyList()), back)
        assertEquals(ExecutionStatus.PENDING, driver.executionStatus(next), "b's execution, not a's report")
        assertEquals(listOf(session.id), beat(b).workspaces, "The workspace is b's now")
    }

    @Test
    fun aRunnerBackBeforeAnyoneTookTheNextExecutionKeepsItsWorkspace() = runTest {
        val a = runner("a")
        val session = starting()
        beat(a, report(session, MachineExecutionState.RUNNING))
        beat(a, report(session, MachineExecutionState.RUNNING))
        clock.now += 91.seconds
        assertEquals(ExecutionStatus.HOST_LOST, driver.executionStatus(session))
        driver.stopExecutions(session)
        val next = sessions.sessions.getValue(session.id).copy(epoch = 2, state = SessionState.RESUMING)
        sessions.put(next)
        driver.startExecution(next.executionSpec("reconciler"))

        // Nobody took epoch 2: a, back, keeps the workspace it has and is given the new execution, never the old one.
        val back = beat(a, report(session, MachineExecutionState.RUNNING))
        assertEquals(listOf(2L), back.executions.map { it.epoch })
        assertEquals(listOf(session.id), back.workspaces)
    }

    @Test
    fun aRemovedRunnersExecutionIsLost() = runTest {
        val a = runner("a")
        val session = starting()
        beat(a)
        runners.runners.clear()
        assertEquals(ExecutionStatus.HOST_LOST, driver.executionStatus(session))
    }

    @Test
    fun aPausedSessionsNextExecutionGoesBackToItsRunnerWhileOnline() = runTest {
        val a = runner("a")
        val b = runner("b")
        val session = starting()
        beat(a)
        driver.stopExecutions(session)
        assertEquals(MachineWork(emptyList(), listOf(session.id)), beat(a), "Stopped, kept")

        val next = sessions.sessions.getValue(session.id).copy(epoch = 2, state = SessionState.RESUMING)
        sessions.put(next)
        driver.startExecution(next.executionSpec("reconciler"))
        driver.startExecution(next.executionSpec("reconciler"))
        assertEquals(MachineWork(emptyList(), emptyList()), beat(b), "Not for another runner")
        assertEquals(listOf(2L), beat(a).executions.map { it.epoch })
    }

    @Test
    fun aWorkspaceIsForgottenOnceItsOnlineHolderDeletedIt() = runTest {
        val a = runner("a")
        val session = starting()
        beat(a)
        beat(a, MachineReport(workspaces = setOf(session.id)))
        driver.ensureWorkspace(session)

        assertEquals(false, driver.deleteWorkspace(session), "Still held")
        assertEquals(MachineWork(emptyList(), emptyList()), beat(a))
        assertEquals(true, driver.deleteWorkspace(session))
        assertEquals(ExecutionStatus.MISSING, driver.executionStatus(session))

        // A dead holder holds nothing.
        val other = starting()
        beat(a)
        beat(a, MachineReport(workspaces = setOf(other.id)))
        clock.now += 91.seconds
        assertEquals(true, driver.deleteWorkspace(other))
    }

    @Test
    fun anExecutionIsHandedOverOnlyWhileItsSessionWaitsForIt() = runTest {
        val a = runner("a")
        val gone = starting()
        sessions.sessions.remove(gone.id)
        assertEquals(emptyList(), beat(a).executions, "Its session is gone")

        val moved = starting()
        sessions.put(sessions.sessions.getValue(moved.id).copy(epoch = 2))
        assertEquals(emptyList(), beat(a).executions, "A newer execution replaced it")

        val running = starting()
        sessions.put(sessions.sessions.getValue(running.id).copy(state = SessionState.RUNNING))
        assertEquals(emptyList(), beat(a).executions, "Not waiting any more")

        val raced = starting()
        sessions.loseNextRaces = 1
        assertEquals(emptyList(), beat(a).executions, "Lost its race: the next heartbeat")
        assertEquals(listOf(raced.id), beat(a).executions.map { it.sessionId })
    }

    @Test
    fun aRunnerThatReportsNothingRunsNoSession() = runTest {
        val a = runner("a")
        val session = starting()
        assertEquals(MachineWork(emptyList(), emptyList()), heartbeat(a.runner.id, a.key))
        assertEquals(ExecutionStatus.PENDING, driver.executionStatus(session), "Still waiting for a runner that runs")
    }

    @Test
    fun aRunnerNeverTakesAnotherOrganizationsExecution() = runTest {
        val other = runner("x", ORG_B)
        starting()
        assertEquals(MachineWork(emptyList(), emptyList()), beat(other))
    }

    /**
     * The loss window (90 s) against a machine at the runner's longest interval (30 s, `MachineConfig`) whose heartbeat
     * times out once (10 s) and whose next one the server, under load, records 15 s late: it is never lost. Two lost
     * heartbeats in a row are a lost machine. A shorter window (60 s) would lose the first one: PERF-RUNNERS, case 3.
     */
    @Test
    fun aHeartbeatLostUnderLoadAtTheLongestIntervalNeverLosesTheMachine() = runTest {
        val a = runner("a")
        val session = starting()
        beat(a)
        beat(a, report(session, MachineExecutionState.RUNNING))
        val seen = clock.now

        // Sent at +30 s, timed out at +40 s; the next one leaves at +70 s and is recorded at +85 s.
        for (at in listOf(30, 40, 60, 70, 85)) {
            clock.now = seen + at.seconds
            assertEquals(ExecutionStatus.RUNNING, driver.executionStatus(session), "Online at +$at s")
        }
        beat(a, report(session, MachineExecutionState.RUNNING))
        val recorded = clock.now
        assertTrue(recorded - seen > 60.seconds, "A 60 s window would have lost it")

        // Then two lost in a row: the next one could come at +110 s at the earliest.
        clock.now = recorded + 91.seconds
        assertEquals(ExecutionStatus.HOST_LOST, driver.executionStatus(session))
    }

    @Test
    fun reportedStatesReadFromTheWire() {
        assertEquals(MachineExecutionState.RUNNING, MachineExecutionState.of("running"))
        assertNull(MachineExecutionState.of("exited"))
        val secret = PlannedObject("runner", buildJsonObject { put("t", "secret") })
        assertEquals("PlannedObject(name=runner)", secret.toString())
    }
}
