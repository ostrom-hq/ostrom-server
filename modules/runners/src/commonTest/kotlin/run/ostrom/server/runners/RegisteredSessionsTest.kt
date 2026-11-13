// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AssignedExecution
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ExecutionPlan
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.MachineExecutionState
import run.ostrom.server.runners.domain.MachineReport
import run.ostrom.server.runners.domain.MachineWork
import run.ostrom.server.runners.domain.NewRunner
import run.ostrom.server.runners.domain.PlannedObject
import run.ostrom.server.runners.domain.RegisterRunnerUseCaseImpl
import run.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.RunnerRegistration
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.executionSpec
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRunnerWorkSignals
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.InMemoryRunnerAssignments
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.RegisteredRuntimeDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Cloud sessions on registered runners: the driver, and the heartbeat that hands executions over, on fakes. */
class RegisteredSessionsTest {
    private val clock = SettableClock()
    private val runners = InMemoryRegisteredRunners()
    private val tokens = FakeRunnerTokens()
    private val sessions = InMemoryCloudSessionsRepository()
    private val assignments = InMemoryRunnerAssignments(
        { id -> runners.runners.firstOrNull { it.id == id }?.lastSeenAt },
        { id -> runners.runners.firstOrNull { it.id == id }?.capacity },
    )
    private val signals = FakeRunnerWorkSignals()
    private val driver = RegisteredRuntimeDriver(assignments, clock, signals)
    private val register = RegisterRunnerUseCaseImpl(runners, tokens, clock)
    private val heartbeat = RegisteredRunnerHeartbeatUseCaseImpl(
        runners,
        tokens,
        clock,
        assignments,
        sessions,
        signals,
    ) { spec ->
        val runner = buildJsonObject {
            put("token", spec.runnerToken)
            put("prompt", spec.prompt)
        }
        ExecutionPlan(listOf(PlannedObject("ws", buildJsonObject {})), listOf(PlannedObject("runner", runner)))
    }

    private suspend fun runner(name: String, org: Uuid = ORG_A) =
        register(Caller(org, "dana", admin = true), RunnerRegistration(name, emptyList(), null))

    private suspend fun beat(runner: NewRunner, report: MachineReport = MachineReport(), wait: Duration = ZERO) =
        heartbeat(runner.runner.id, runner.key, report, wait)

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
        assertEquals(ExecutionStatus.QUEUED, driver.executionStatus(session), "No machine took it yet")

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
    fun aWaitingHeartbeatIsHandedTheExecutionQueuedWhileItWaits() = runTest {
        val a = runner("a")
        val waiting = async { beat(a, wait = 20.seconds) }
        runCurrent()
        assertEquals(1, signals.waits, "Nothing to hand over: it waits")

        val session = starting()
        assertEquals(listOf(ORG_A), signals.arrivals, "Queuing wakes the organization's heartbeats")
        signals.wake()

        val work = waiting.await()
        assertEquals(listOf(session.id), work.executions.filter { it.plan != null }.map { it.sessionId })
        assertEquals(1, signals.waits, "Answered as soon as it was handed over")
    }

    @Test
    fun aWaitingHeartbeatWokenForNothingWaitsAgainThenAnswersAtTheEndOfTheWait() = runTest {
        val a = runner("a")
        val started = testScheduler.currentTime
        val waiting = async { beat(a, wait = 20.seconds) }
        runCurrent()
        signals.wake()
        runCurrent()
        assertEquals(2, signals.waits, "Another runner took it, or nothing came: it waits again")

        assertEquals(MachineWork(emptyList(), emptyList()), waiting.await())
        assertEquals(20_000, testScheduler.currentTime - started, "At the end of the wait")
    }

    @Test
    fun aHeartbeatAnswersAtOnceWhenItIsHandedWorkHasNoRoomOrMayNotWait() = runTest {
        val a = runner("a")
        val session = starting()
        assertTrue(beat(a, wait = 20.seconds).executions.single().plan != null, "Handed over: at once")
        val full = report(session, MachineExecutionState.RUNNING).copy(capacity = 1)
        assertEquals(1, beat(a, full, 20.seconds).executions.size, "No room: at once")
        signals.room = false
        assertEquals(1, beat(a, full.copy(capacity = 2), 20.seconds).executions.size, "Too many wait: at once")
        assertTrue(heartbeat(a.runner.id, a.key, null, 20.seconds).executions.isEmpty(), "No report: no work")
        assertEquals(0, signals.waits)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun aRunnerWithRoomLeftWaits() = runTest {
        val a = runner("a")
        val session = starting()
        beat(a)
        val roomy = report(session, MachineExecutionState.RUNNING).copy(capacity = 2)
        assertEquals(1, beat(a, roomy, 5.seconds).executions.size)
        assertEquals(1, signals.waits)
        assertEquals(5_000, testScheduler.currentTime)
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
    fun aSessionNamesTheRunnerThatTookItsExecution() = runTest {
        val a = runner("a")
        assertNull(driver.holder(session()), "Never started: no execution, no runner")
        val session = starting()
        assertNull(driver.holder(session), "Waiting for a machine: none")

        beat(a)

        assertEquals(a.runner.id, driver.holder(session))
        driver.stopExecutions(session)
        assertNull(driver.holder(session), "Stopped: no longer the runner's")
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
        assertEquals(ExecutionStatus.QUEUED, driver.executionStatus(session), "Still waiting for a runner that runs")
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
    fun aMachineNeverTakesMoreSessionsThanItsCapacityAndAStoppedOneFreesItsPlace() = runTest {
        val a = runner("a")
        val b = runner("b")
        val c = runner("c")
        val waiting = List(4) { starting() }

        repeat(3) { beat(a, MachineReport(capacity = 2)) }
        assertEquals(2, beat(a, MachineReport(capacity = 2)).executions.size, "Full at 2")
        assertEquals(emptyList(), beat(c, MachineReport(capacity = 0)).executions, "A machine at 0 takes nothing")
        assertEquals(1, beat(b, MachineReport(capacity = 1)).executions.size)
        assertEquals(1, beat(b, MachineReport(capacity = 1)).executions.size, "Full at 1: the fourth waits")
        val held = beat(a, MachineReport(capacity = 2)).executions.map { it.sessionId }
        val onB = beat(b, MachineReport(capacity = 1)).executions.map { it.sessionId }
        val fourth = (waiting.map { it.id } - held.toSet() - onB.toSet()).single()

        // One of a's sessions stops: a takes the one that waited.
        driver.stopExecutions(waiting.first { it.id == held.first() })
        val after = beat(a, MachineReport(capacity = 2)).executions.map { it.sessionId }.toSet()
        assertEquals(setOf(held.last(), fourth), after)
    }

    @Test
    fun aResumeGoesBackToItsMachineOnlyWhileItHasRoom() = runTest {
        val a = runner("a")
        val b = runner("b")
        val paused = starting()
        beat(a, MachineReport(capacity = 1))
        driver.stopExecutions(paused)
        val other = starting()
        assertEquals(listOf(other.id), beat(a, MachineReport(capacity = 1)).executions.map { it.sessionId })

        // a is online but full: the resume waits for any machine, and b takes it.
        val resumed = sessions.sessions.getValue(paused.id).copy(epoch = 2, state = SessionState.RESUMING)
        sessions.put(resumed)
        driver.startExecution(resumed.executionSpec("reconciler"))
        assertEquals(listOf(other.id), beat(a, MachineReport(capacity = 1)).executions.map { it.sessionId })
        assertEquals(listOf(paused.id to 2L), beat(b).executions.map { it.sessionId to it.epoch })
    }

    @Test
    fun reportedStatesReadFromTheWire() {
        assertEquals(MachineExecutionState.RUNNING, MachineExecutionState.of("running"))
        assertNull(MachineExecutionState.of("exited"))
        val secret = PlannedObject("runner", buildJsonObject { put("t", "secret") })
        assertEquals("PlannedObject(name=runner)", secret.toString())
    }
}
