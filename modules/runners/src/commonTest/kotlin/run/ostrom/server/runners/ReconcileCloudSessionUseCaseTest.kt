// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ReconcileCloudSessionUseCaseTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val driver = FakeRuntimeDriver()
    private val tokens = FakeRunnerTokens()
    private val plans = FixedPlans()
    private val clock = SettableClock()
    private val meter = RepositoryCloudUsageMeter(repository)
    private val reconcile = ReconcileCloudSessionUseCaseImpl(repository, driver, tokens, plans, meter, clock)
    private val t0 = clock.now

    private fun put(session: CloudSession) = session.also { repository.put(it) }

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    private fun runningSession(desired: DesiredState = DesiredState.RUNNING) = put(
        session(state = SessionState.RUNNING, desired = desired).copy(
            epoch = 1,
            executionStartedAt = t0,
            readyAt = t0,
            lastHeartbeatAt = t0,
            lastActivityAt = t0,
            runnerTokenHash = "hash(token-1)",
        ),
    )

    @Test
    fun aNewSessionGetsItsWorkspaceThenItsFirstExecution() = runTest {
        val created = put(session().copy(model = "claude-opus-5-5", prompt = "Fix the tests"))

        assertEquals(t0 + 2.seconds, reconcile(created))

        val after = stored(created)
        assertEquals(listOf("ensureWorkspace", "start:1"), driver.calls)
        assertEquals(1, after.epoch)
        assertEquals(t0, after.executionStartedAt)
        assertEquals("hash(token-1)", after.runnerTokenHash)
        assertEquals("starting_runner", after.phase)
        assertEquals("token-1", driver.started.single().runnerToken)
        assertEquals(created.id, driver.started.single().sessionId)
        assertEquals(ORG_A, driver.started.single().orgId)
        assertEquals("claude-opus-5-5", driver.started.single().model)
        assertEquals("Fix the tests", driver.started.single().prompt)
    }

    @Test
    fun anExecutionIsNeverStartedWhenItsRowLostARace() = runTest {
        val created = put(session())
        repository.loseNextRaces = 1

        assertEquals(t0, reconcile(created))
        assertEquals(listOf("ensureWorkspace"), driver.calls)
    }

    @Test
    fun aStartingExecutionIsWatched() = runTest {
        val starting = put(session().copy(epoch = 1, executionStartedAt = t0))
        clock.now = t0 + 10.seconds

        assertEquals(clock.now + 2.seconds, reconcile(starting))
        assertEquals(listOf("status"), driver.calls)
        // Missing just after the start: the runtime may not list it yet.
        driver.status = ExecutionStatus.MISSING
        assertEquals(clock.now + 2.seconds, reconcile(stored(starting)))
    }

    @Test
    fun aFirstStartThatFailsFailsTheSessionAndDeletesItsWorkspace() = runTest {
        val starting = put(session().copy(epoch = 1, executionStartedAt = t0, runnerTokenHash = "hash(token-1)"))
        driver.status = ExecutionStatus.FAILED

        assertNull(reconcile(starting))

        val after = stored(starting)
        assertEquals(SessionState.FAILED, after.state)
        assertEquals("start_failed", after.failure?.code)
        assertNull(after.runnerTokenHash)
        assertEquals(listOf("status", "stop", "deleteWorkspace"), driver.calls)
    }

    @Test
    fun aFailedFirstStartWaitsForItsWorkspaceDeletion() = runTest {
        val starting = put(session().copy(epoch = 1, executionStartedAt = t0))
        driver.status = ExecutionStatus.ENDED
        driver.workspaceDeleted = false

        assertEquals(t0 + 2.seconds, reconcile(starting))
        assertEquals(SessionState.CREATING, stored(starting).state)
    }

    @Test
    fun startsTimeOutAndMissingExecutionsAreLost() = runTest {
        val timedOut = put(session().copy(epoch = 1, executionStartedAt = t0))
        clock.now = t0 + 16.minutes
        reconcile(timedOut)
        assertEquals(SessionState.FAILED, stored(timedOut).state)

        val missing = put(session().copy(epoch = 1, executionStartedAt = t0))
        driver.status = ExecutionStatus.MISSING
        clock.now = t0 + 61.seconds
        reconcile(missing)
        assertEquals(SessionState.FAILED, stored(missing).state)
    }

    @Test
    fun anExecutionQueuedForAMachineNeverTimesOutAndItsStartCountsFromItsTake() = runTest {
        val queued = put(session().copy(epoch = 1, executionStartedAt = t0, phase = "starting_runner"))
        driver.status = ExecutionStatus.QUEUED

        // Waiting for a machine with room, as long as it takes: never failed for it, looked at less often.
        clock.now = t0 + 10.seconds
        assertEquals(clock.now + 15.seconds, reconcile(queued))
        assertEquals("queued", stored(queued).phase)
        clock.now = t0 + 2.hours
        assertEquals(clock.now + 15.seconds, reconcile(stored(queued)))
        assertEquals(SessionState.CREATING, stored(queued).state)
        assertEquals(listOf("status", "status"), driver.calls)

        // A machine takes it: its start is timed from now.
        driver.status = ExecutionStatus.PENDING
        assertEquals(clock.now + 2.seconds, reconcile(stored(queued)))
        val taken = stored(queued)
        assertEquals("starting_runner", taken.phase)
        assertEquals(clock.now, taken.executionStartedAt)
        val takenAt = clock.now
        clock.now = takenAt + 14.minutes
        reconcile(stored(queued))
        assertEquals(SessionState.CREATING, stored(queued).state)
        clock.now = takenAt + 16.minutes
        reconcile(stored(queued))
        assertEquals(SessionState.FAILED, stored(queued).state)
    }

    @Test
    fun aResumeThatFailsKeepsTheWorkspaceAndPauses() = runTest {
        val resuming = put(session(state = SessionState.RESUMING).copy(epoch = 2, executionStartedAt = t0))
        driver.status = ExecutionStatus.FAILED

        assertNull(reconcile(resuming))

        val after = stored(resuming)
        assertEquals(SessionState.PAUSED, after.state)
        assertEquals("start_failed", after.failure?.code)
        assertEquals(t0, after.pausedAt)
        assertEquals(listOf("status", "stop"), driver.calls)
    }

    @Test
    fun aPauseAskedDuringTheStartStopsItAtOnce() = runTest {
        val creating = put(session(desired = DesiredState.PAUSED).copy(epoch = 1, executionStartedAt = t0))

        assertNull(reconcile(creating))

        assertEquals(SessionState.PAUSED, stored(creating).state)
        assertEquals(listOf("stop"), driver.calls)
    }

    @Test
    fun noExecutionStartsOnceTheMonthsHoursAreUsedUp() = runTest {
        val created = put(session())
        repository.usage[ORG_A to "2026-10"] = 300 * 3600L

        assertNull(reconcile(created))

        val after = stored(created)
        assertEquals(SessionState.PAUSED, after.state)
        assertEquals(DesiredState.PAUSED, after.desiredState)
        assertEquals("hours_exhausted", after.failure?.code)
        assertEquals(listOf("stop"), driver.calls)
    }

    @Test
    fun anUnlimitedPlanNeverRunsOutOfHours() = runTest {
        plans.plan = CloudPlan.FREE.copy(monthlyHours = null)
        repository.usage[ORG_A to "2026-10"] = 10_000 * 3600L
        val created = put(session())
        reconcile(created)
        assertEquals(1, stored(created).epoch)
    }

    @Test
    fun aHealthyRunningSessionIsWatched() = runTest {
        val running = runningSession()
        driver.status = ExecutionStatus.RUNNING
        clock.now = t0 + 30.seconds

        assertEquals(clock.now + 15.seconds, reconcile(running))
        assertEquals(SessionState.RUNNING, stored(running).state)
    }

    @Test
    fun aSilentOrVanishedRunnerIsLostAndTheSessionPausedCrashConsistent() = runTest {
        for (status in listOf(ExecutionStatus.ENDED, ExecutionStatus.FAILED, ExecutionStatus.MISSING)) {
            val running = runningSession()
            driver.status = status
            assertNull(reconcile(running))
            assertEquals(SessionState.PAUSED, stored(running).state)
            assertEquals(DesiredState.PAUSED, stored(running).desiredState)
            assertEquals("execution_lost", stored(running).failure?.code)
        }
        val silent = runningSession()
        driver.status = ExecutionStatus.RUNNING
        clock.now = t0 + 91.seconds
        reconcile(silent)
        assertEquals("execution_lost", stored(silent).failure?.code)
    }

    @Test
    fun aRunningSessionWhoseMachineDiedStartsAgainOnAnother() = runTest {
        val running = runningSession()
        driver.status = ExecutionStatus.HOST_LOST
        clock.now = t0 + 30.seconds

        assertEquals(clock.now, reconcile(running), "Looked at again at once")
        val paused = stored(running)
        assertEquals(SessionState.PAUSED, paused.state)
        assertEquals(DesiredState.RUNNING, paused.desiredState, "Still wanted running")
        assertEquals("runner_lost", paused.failure?.code)
        assertEquals(listOf("status", "stop"), driver.calls)

        reconcile(paused)
        assertEquals(SessionState.RESUMING, stored(running).state)
        reconcile(stored(running))
        assertEquals(listOf("status", "stop", "ensureWorkspace", "start:2"), driver.calls)
    }

    @Test
    fun onARuntimeThatRelocatesASilentRunnerMovesUnlessItsExecutionEnded() = runTest {
        driver.relocates = true
        driver.status = ExecutionStatus.RUNNING
        val silent = runningSession()
        clock.now = t0 + 91.seconds
        reconcile(silent)
        assertEquals("runner_lost", stored(silent).failure?.code)
        assertEquals(DesiredState.RUNNING, stored(silent).desiredState)

        driver.status = ExecutionStatus.ENDED
        val ended = runningSession()
        reconcile(ended)
        assertEquals("execution_lost", stored(ended).failure?.code)
        assertEquals(DesiredState.PAUSED, stored(ended).desiredState)

        // Not silent: a running execution stays.
        driver.status = ExecutionStatus.RUNNING
        val healthy = put(runningSession().copy(lastHeartbeatAt = clock.now))
        assertEquals(clock.now + 15.seconds, reconcile(healthy))
    }

    @Test
    fun aStartWhoseMachineDiedStartsAgainAtOnce() = runTest {
        val starting = put(session().copy(epoch = 1, executionStartedAt = t0, phase = "starting_runner"))
        driver.status = ExecutionStatus.HOST_LOST
        clock.now = t0 + 95.seconds

        assertEquals(clock.now, reconcile(starting))
        val after = stored(starting)
        assertEquals(SessionState.CREATING, after.state)
        assertNull(after.executionStartedAt)
        assertEquals("scheduling", after.phase)
        reconcile(after)
        assertEquals(listOf("status", "stop", "ensureWorkspace", "start:2"), driver.calls)
    }

    @Test
    fun aPauseRequestStartsTheDrain() = runTest {
        val running = runningSession(desired = DesiredState.PAUSED)
        driver.status = ExecutionStatus.RUNNING

        assertEquals(t0 + 2.seconds, reconcile(running))

        val after = stored(running)
        assertEquals(SessionState.PAUSING, after.state)
        assertEquals(t0, after.pausingSince)
        assertEquals("draining", after.phase)
    }

    @Test
    fun theFreePlansGuardsPauseIdleOverlongAndOverBudgetSessions() = runTest {
        driver.status = ExecutionStatus.RUNNING
        val idle = runningSession().copy(lastHeartbeatAt = t0 + 30.minutes).also { put(it) }
        clock.now = t0 + 30.minutes
        reconcile(idle)
        assertEquals(
            SessionFailure("idle_timeout", "Paused after 30 minutes without a request to the model."),
            stored(idle).failure,
        )
        assertEquals(SessionState.PAUSING, stored(idle).state)
        assertEquals(DesiredState.PAUSED, stored(idle).desiredState)

        val busy = runningSession().copy(
            lastActivityAt = t0 + 240.minutes,
            lastHeartbeatAt = t0 + 240.minutes,
        ).also { put(it) }
        clock.now = t0 + 240.minutes
        reconcile(busy)
        assertEquals("max_duration", stored(busy).failure?.code)

        val overBudget = runningSession().copy(
            lastActivityAt = t0 + 10.minutes,
            lastHeartbeatAt = t0 + 10.minutes,
        ).also { put(it) }
        clock.now = t0 + 10.minutes
        repository.usage[ORG_A to "2026-10"] = 300 * 3600L
        reconcile(overBudget)
        assertEquals("hours_exhausted", stored(overBudget).failure?.code)

        repository.usage.clear()
        plans.plan = CloudPlan.FREE.copy(available = false)
        val closed = runningSession().copy(
            lastActivityAt = t0 + 10.minutes,
            lastHeartbeatAt = t0 + 10.minutes,
        ).also { put(it) }
        reconcile(closed)
        assertEquals("closed", stored(closed).failure?.code)
        val waiting = put(session())
        assertNull(reconcile(waiting))
        assertEquals("closed", stored(waiting).failure?.code)
        assertEquals(SessionState.PAUSED, stored(waiting).state)
    }

    @Test
    fun aRunnerWithoutHeartbeatCountsFromItsReadinessThenFromNow() = runTest {
        driver.status = ExecutionStatus.RUNNING
        val ready = runningSession().copy(lastHeartbeatAt = null).also { put(it) }
        clock.now = t0 + 91.seconds
        reconcile(ready)
        assertEquals("execution_lost", stored(ready).failure?.code)

        val fresh = runningSession().copy(lastHeartbeatAt = null, readyAt = null, lastActivityAt = null)
            .also { put(it) }
        assertEquals(clock.now + 15.seconds, reconcile(fresh))
        assertEquals(SessionState.RUNNING, stored(fresh).state)
    }

    @Test
    fun aDrainEndsWhenTheRunnerFallsSilentBeforeTheDeadline() = runTest {
        val silent = put(
            runningSession().copy(
                state = SessionState.PAUSING,
                desiredState = DesiredState.PAUSED,
                pausingSince = t0,
                lastHeartbeatAt = t0,
            ),
        )
        clock.now = t0 + 100.seconds
        assertNull(reconcile(silent))
        assertEquals(SessionState.PAUSED, stored(silent).state)
    }

    @Test
    fun aRunningSessionWithoutActivityCountsFromItsReadiness() = runTest {
        val running = runningSession().copy(
            lastActivityAt = null,
            readyAt = t0 + 5.minutes,
            lastHeartbeatAt = t0 + 34.minutes,
        ).also { put(it) }
        driver.status = ExecutionStatus.RUNNING
        clock.now = t0 + 34.minutes
        assertEquals(clock.now + 15.seconds, reconcile(running))
    }

    @Test
    fun aDrainEndsWhenTheRunnerStoppedOrAtTheDeadline() = runTest {
        val stopped = put(
            runningSession().copy(
                state = SessionState.PAUSING,
                desiredState = DesiredState.PAUSED,
                pausingSince = t0,
                runnerStoppedAt = t0 + 3.seconds,
            ),
        )
        clock.now = t0 + 4.seconds
        assertNull(reconcile(stopped))
        val after = stored(stopped)
        assertEquals(SessionState.PAUSED, after.state)
        assertNull(after.runnerTokenHash)
        assertNull(after.executionStartedAt)
        assertEquals(clock.now, after.pausedAt)

        val draining = put(
            runningSession().copy(
                state = SessionState.PAUSING,
                desiredState = DesiredState.PAUSED,
                pausingSince = t0,
                lastHeartbeatAt = t0 + 100.seconds,
            ),
        )
        clock.now = t0 + 110.seconds
        assertEquals(clock.now + 2.seconds, reconcile(draining))
        clock.now = t0 + 121.seconds
        assertNull(reconcile(draining))
        assertEquals(SessionState.PAUSED, stored(draining).state)

        val silent = put(
            runningSession().copy(
                state = SessionState.PAUSING,
                desiredState = DesiredState.PAUSED,
                pausingSince = null,
                lastHeartbeatAt = null,
            ),
        )
        assertEquals(clock.now + 2.seconds, reconcile(silent))
    }

    @Test
    fun aResumeAskedDuringTheDrainResumesRightAfter() = runTest {
        val pausing = put(
            runningSession().copy(
                state = SessionState.PAUSING,
                desiredState = DesiredState.RUNNING,
                pausingSince = t0,
                runnerStoppedAt = t0,
                prompt = "once",
            ),
        )

        assertEquals(t0, reconcile(pausing))
        val paused = stored(pausing)
        assertEquals(SessionState.PAUSED, paused.state)

        assertEquals(t0, reconcile(paused))
        val resuming = stored(pausing)
        assertEquals(SessionState.RESUMING, resuming.state)
        assertEquals("scheduling", resuming.phase)
        assertNull(resuming.failure)

        reconcile(resuming)
        assertEquals(2, stored(pausing).epoch)
        assertEquals("token-1", driver.started.single().runnerToken)
        // The first message was the first execution's: a resume never sends it again.
        assertEquals(null, driver.started.single().prompt)
    }

    @Test
    fun aPausedSessionWaitsForAResume() = runTest {
        val paused = put(session(state = SessionState.PAUSED, desired = DesiredState.PAUSED))
        assertNull(reconcile(paused))
        assertEquals(SessionState.PAUSED, stored(paused).state)
    }

    @Test
    fun deletionStopsTheExecutionAndProvesTheWorkspaceGone() = runTest {
        val running = runningSession(desired = DesiredState.TERMINATED)
        driver.workspaceDeleted = false

        assertEquals(t0 + 2.seconds, reconcile(running))
        assertEquals(SessionState.RUNNING, stored(running).state)

        driver.workspaceDeleted = true
        assertNull(reconcile(running))
        val after = stored(running)
        assertEquals(SessionState.TERMINATED, after.state)
        assertEquals(t0, after.terminatedAt)
        assertNull(after.runnerTokenHash)
        assertEquals(listOf("stop", "deleteWorkspace", "stop", "deleteWorkspace"), driver.calls)
    }

    // The demo stack's journey (step D3, run 41471499629): the runner's last heartbeat, sent while its container
    // stopped, won the row; the step dropped its lost write and the session stayed `running` for ever.
    @Test
    fun aDeletionThatLostItsWriteIsLookedAtAgain() = runTest {
        val running = runningSession(desired = DesiredState.TERMINATED)
        repository.loseNextRaces = 1
        clock.now = t0 + 12.seconds

        assertEquals(clock.now, reconcile(running))
        assertEquals(SessionState.RUNNING, stored(running).state)

        assertNull(reconcile(stored(running)))
        assertEquals(SessionState.TERMINATED, stored(running).state)
    }

    @Test
    fun finishedSessionsNeedNothing() = runTest {
        assertNull(reconcile(put(session(state = SessionState.TERMINATED, desired = DesiredState.TERMINATED))))
        assertNull(reconcile(put(session(state = SessionState.FAILED))))
        val failedThenDeleted = put(session(state = SessionState.FAILED, desired = DesiredState.TERMINATED))
        assertNull(reconcile(failedThenDeleted))
        assertEquals(SessionState.TERMINATED, stored(failedThenDeleted).state)
    }

    @Test
    fun aLostRaceAsksForAnImmediateNewLook() = runTest {
        val paused = put(session(state = SessionState.PAUSED, desired = DesiredState.RUNNING))
        repository.loseNextRaces = 1
        clock.now = t0 + 1.seconds

        assertEquals(clock.now, reconcile(paused))
    }
}
