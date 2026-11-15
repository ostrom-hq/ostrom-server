// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RemoteHost
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.LAPTOP
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The reconciler on remote sessions (TeamCode's `ReconcileCloudSessionsUseCase.remote`): it never calls the driver. */
class ReconcileRemoteSessionTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val driver = FakeRuntimeDriver()
    private val clock = SettableClock()
    private val ended = mutableListOf<Pair<Uuid, List<String>>>()
    private val reconcile = ReconcileCloudSessionUseCaseImpl(
        repository,
        driver,
        FakeRunnerTokens(),
        FixedPlans(),
        RepositoryCloudUsageMeter(repository),
        clock,
        ended = listOf(SessionEndListener { org, ids -> ended += org to ids }),
        backgroundWork = { BackgroundWork.DEFAULT_MAX_MINUTES },
    )
    private val t0 = clock.now

    private fun remote(
        state: SessionState = SessionState.CREATING,
        desired: DesiredState = DesiredState.RUNNING,
        idle: Int = 60,
    ): CloudSession = session(state = state, desired = desired, limits = SessionLimits(idle, 0, false)).copy(
        kind = SessionKind.REMOTE,
        mode = SessionMode.CONVERSATION,
        host = RemoteHost(LAPTOP, "ostrom", RemoteSessionRules.ASK),
        phase = RemoteSessionRules.WAITING_HOST,
    ).also(repository::put)

    private fun hosted(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
        idle: Int = 60,
    ) =
        remote(state, desired, idle).copy(
            epoch = 1,
            executionStartedAt = t0,
            readyAt = t0,
            lastHeartbeatAt = t0,
            lastActivityAt = t0,
        ).also(repository::put)

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    @Test
    fun aSessionWaitingForItsHostIsLeftAlone() = runTest {
        val waiting = remote()
        assertNull(reconcile(waiting))
        assertEquals(waiting, stored(waiting))
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun aStartingHostIsWatchedThenGivesItsEpochBackWhenItNeverReports() = runTest {
        val starting = remote().copy(epoch = 1, executionStartedAt = t0, phase = RemoteSessionRules.STARTING_HOST)
            .also(repository::put)
        clock.now = t0 + 60.seconds
        assertEquals(clock.now + 15.seconds, reconcile(starting))

        clock.now = t0 + 121.seconds
        assertNull(reconcile(starting))
        val after = stored(starting)
        assertEquals(SessionState.CREATING, after.state)
        assertEquals(RemoteSessionRules.WAITING_HOST, after.phase)
        assertNull(after.executionStartedAt)
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun aResumingHostSilentAfterItsFirstHeartbeatGivesItsEpochBack() = runTest {
        val resuming = remote(SessionState.RESUMING).copy(epoch = 2, executionStartedAt = t0, lastHeartbeatAt = t0)
            .also(repository::put)
        clock.now = t0 + 91.seconds
        assertNull(reconcile(resuming))
        assertEquals(RemoteSessionRules.WAITING_HOST, stored(resuming).phase)
    }

    @Test
    fun aStartingSessionAskedToPauseIsPaused() = runTest {
        val starting = remote(desired = DesiredState.PAUSED)
        reconcile(starting)
        assertEquals(SessionState.PAUSED, stored(starting).state)
    }

    @Test
    fun aLostHostPausesTheSessionAsOffline() = runTest {
        val running = hosted()
        clock.now = t0 + 91.seconds

        reconcile(running)

        val after = stored(running)
        assertEquals(SessionState.PAUSED, after.state)
        assertEquals(DesiredState.PAUSED, after.desiredState)
        assertEquals(RemoteSessionRules.HOST_OFFLINE, after.failure?.code)
        assertEquals(true, after.failure?.retryable)
        assertNull(after.executionStartedAt)
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun aRunningSessionWithoutExecutionIsOffline() = runTest {
        val running = hosted().copy(executionStartedAt = null).also(repository::put)
        reconcile(running)
        assertEquals(RemoteSessionRules.HOST_OFFLINE, stored(running).failure?.code)
    }

    @Test
    fun aLiveHostIsWatchedThenPausedWhenIdle() = runTest {
        val running = hosted()
        clock.now = t0 + 30.seconds
        assertEquals(clock.now + 15.seconds, reconcile(running))

        val fresh = stored(running).copy(lastHeartbeatAt = t0 + 60.minutes).also(repository::put)
        clock.now = t0 + 60.minutes
        assertEquals(clock.now + 2.seconds, reconcile(fresh))
        val after = stored(running)
        assertEquals(SessionState.PAUSING, after.state)
        assertEquals(DesiredState.PAUSED, after.desiredState)
        assertEquals("idle_timeout", after.failure?.code)
    }

    @Test
    fun aZeroIdleTimeoutNeverPauses() = runTest {
        val running = hosted(idle = 0).copy(lastActivityAt = null, readyAt = null).also(repository::put)
        clock.now = t0 + 60.seconds
        assertEquals(clock.now + 15.seconds, reconcile(running))
        assertEquals(SessionState.RUNNING, stored(running).state)
    }

    @Test
    fun aSessionIdleSinceItWasReadyOrBusyInTheBackgroundIsJudgedSo() = runTest {
        val ready = hosted().copy(lastActivityAt = null, readyAt = t0).also(repository::put)
        clock.now = t0 + 60.minutes
        repository.put(ready.copy(lastHeartbeatAt = clock.now))
        reconcile(stored(ready))
        assertEquals(SessionState.PAUSING, stored(ready).state)

        val busy = hosted().copy(
            lastHeartbeatAt = clock.now,
            backgroundSince = clock.now - 1.minutes,
            backgroundSeenAt = clock.now,
        ).also(repository::put)
        assertEquals(clock.now + 15.seconds, reconcile(busy))
        assertEquals(SessionState.RUNNING, stored(busy).state)
    }

    @Test
    fun aSessionWithoutActivityIsIdleFromNow() = runTest {
        val running = hosted().copy(lastActivityAt = null, readyAt = null).also(repository::put)
        clock.now = t0 + 60.seconds
        assertEquals(clock.now + 15.seconds, reconcile(running))
    }

    @Test
    fun aPauseAskedForDrainsTheHostThenCompletesOnceItStops() = runTest {
        val running = hosted(desired = DesiredState.PAUSED)
        assertEquals(t0 + 2.seconds, reconcile(running))
        val pausing = stored(running)
        assertEquals(SessionState.PAUSING, pausing.state)

        clock.now = t0 + 10.seconds
        val beating = pausing.copy(lastHeartbeatAt = clock.now).also(repository::put)
        assertEquals(clock.now + 2.seconds, reconcile(beating))

        val stopped = beating.copy(runnerStoppedAt = clock.now).also(repository::put)
        assertNull(reconcile(stopped))
        assertEquals(SessionState.PAUSED, stored(running).state)
        assertEquals(DesiredState.PAUSED, stored(running).desiredState)
    }

    @Test
    fun aPauseCompletesPastItsDeadlineOrWithoutHost() = runTest {
        val pausing = hosted(SessionState.PAUSING, DesiredState.PAUSED).copy(pausingSince = t0).also(repository::put)
        clock.now = t0 + 16.minutes
        repository.put(pausing.copy(lastHeartbeatAt = clock.now))
        reconcile(stored(pausing))
        assertEquals(SessionState.PAUSED, stored(pausing).state)

        val gone = hosted(SessionState.PAUSING, DesiredState.PAUSED)
            .copy(executionStartedAt = null, pausingSince = null)
            .also(repository::put)
        reconcile(gone)
        assertEquals(SessionState.PAUSED, stored(gone).state)

        // A host gone silent while draining.
        val silent = hosted(SessionState.PAUSING).copy(pausingSince = clock.now).also(repository::put)
        reconcile(silent)
        assertEquals(SessionState.PAUSED, stored(silent).state)
    }

    @Test
    fun aResumeBeforeTheHostStoppedCancelsThePause() = runTest {
        val pausing = hosted(SessionState.PAUSING).copy(pausingSince = t0, phase = "draining").also(repository::put)
        assertEquals(t0, reconcile(pausing))
        val after = stored(pausing)
        assertEquals(SessionState.RUNNING, after.state)
        assertNull(after.phase)
        assertNull(after.pausingSince)
    }

    @Test
    fun aPauseThatCompletesWithAResumeAskedLooksAgainAtOnce() = runTest {
        val pausing = hosted(SessionState.PAUSING).copy(pausingSince = t0, runnerStoppedAt = t0).also(repository::put)
        assertEquals(t0, reconcile(pausing))
        assertEquals(SessionState.PAUSED, stored(pausing).state)
        assertEquals(DesiredState.RUNNING, stored(pausing).desiredState)
    }

    @Test
    fun aPausedSessionWokenWaitsForItsHost() = runTest {
        val paused = remote(SessionState.PAUSED).copy(
            pausedAt = t0,
            failure = SessionFailure(RemoteSessionRules.HOST_OFFLINE, "Gone.", retryable = true),
        ).also(repository::put)

        assertNull(reconcile(paused))

        val after = stored(paused)
        assertEquals(SessionState.RESUMING, after.state)
        assertEquals(RemoteSessionRules.WAITING_HOST, after.phase)
        assertNull(after.failure)
        assertNull(after.pausedAt)
    }

    @Test
    fun aPausedSessionWaitsForItsExpiryThenEnds() = runTest {
        val paused = remote(SessionState.PAUSED, DesiredState.PAUSED).copy(expiresAt = t0 + 1.minutes)
            .also(repository::put)
        assertEquals(t0 + 1.minutes, reconcile(paused))

        clock.now = t0 + 1.minutes
        assertEquals(clock.now, reconcile(paused))
        val expiring = stored(paused)
        assertEquals(DesiredState.TERMINATED, expiring.desiredState)
        assertEquals(CloudSession.TERMINATED_EXPIRED, expiring.terminatedReason)

        assertNull(reconcile(expiring))
        assertEquals(SessionState.TERMINATED, stored(paused).state)
        assertEquals(listOf(ORG_A to listOf(paused.id.toString())), ended)
        assertTrue(driver.calls.isEmpty())
        assertNull(reconcile(stored(paused)))
    }

    @Test
    fun aDeletedSessionEndsWithoutTouchingTheDevice() = runTest {
        val running = hosted(desired = DesiredState.TERMINATED)
        assertNull(reconcile(running))
        val after = stored(running)
        assertEquals(SessionState.TERMINATED, after.state)
        assertNull(after.executionStartedAt)
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun aLostRaceLooksAgainAtOnce() = runTest {
        val running = hosted(desired = DesiredState.TERMINATED)
        repository.loseNextRaces = 1
        assertEquals(clock.now, reconcile(running))
        assertTrue(ended.isEmpty())
    }

    @Test
    fun aFailedRemoteSessionIsDone() = runTest {
        assertNull(reconcile(remote(SessionState.FAILED)))
    }
}
