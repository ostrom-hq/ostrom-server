// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.LocalSessionRules
import run.ostrom.server.runners.domain.PausedRetention
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionMode
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The reconciler on local sessions (TeamCode's `ReconcileCloudSessionsUseCase.local`): the launch is the host, the
 * session pauses as host offline after 90 s of silence, and ends when the launch never took its execution in 120 s.
 */
class ReconcileLocalSessionTest {
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
        retention = PausedRetention { 7 },
        backgroundWork = { BackgroundWork.DEFAULT_MAX_MINUTES },
    )
    private val t0 = clock.now

    private fun local(state: SessionState = SessionState.CREATING, desired: DesiredState = DesiredState.RUNNING) =
        session(state = state, desired = desired, createdAt = t0, limits = SessionLimits(0, 0, false)).copy(
            kind = SessionKind.LOCAL,
            mode = SessionMode.CONVERSATION,
            phase = RemoteSessionRules.WAITING_HOST,
            launchId = "lch_0123456789abcdef0123456789abcdef",
        ).also(repository::put)

    private fun hosted(state: SessionState = SessionState.RUNNING) = local(state).copy(
        epoch = 1,
        executionStartedAt = t0,
        readyAt = t0,
        lastHeartbeatAt = t0,
        lastActivityAt = t0,
    ).also(repository::put)

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    @Test
    fun aSessionWaitingForItsLaunchIsWatchedThenEndsAsRunLost() = runTest {
        val waiting = local()
        clock.now = t0 + 60.seconds
        assertEquals(clock.now + 15.seconds, reconcile(waiting))
        assertEquals(waiting, stored(waiting))

        clock.now = t0 + 121.seconds
        assertEquals(clock.now, reconcile(waiting))
        val lost = stored(waiting)
        assertEquals(DesiredState.TERMINATED to LocalSessionRules.RUN_LOST, lost.desiredState to lost.terminatedReason)
        assertNull(reconcile(lost))
        val after = stored(waiting)
        assertEquals(SessionState.TERMINATED to LocalSessionRules.RUN_LOST, after.state to after.terminatedReason)
        assertEquals(listOf(ORG_A to listOf(waiting.id.toString())), ended)
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun aBeatingLaunchKeepsItsSessionWhateverItsIdleness() = runTest {
        val running = hosted()
        clock.now = t0 + 60.minutes
        repository.put(running.copy(lastHeartbeatAt = clock.now))
        assertEquals(clock.now + 15.seconds, reconcile(stored(running)))
        assertEquals(SessionState.RUNNING, stored(running).state, "A local session never pauses for idleness")
    }

    @Test
    fun aSilentLaunchPausesItsSessionAsOfflineUntilItsRetentionEnds() = runTest {
        val running = hosted()
        clock.now = t0 + 91.seconds
        assertEquals(t0 + 91.seconds + 7.days, reconcile(running))
        val offline = stored(running)
        assertEquals(SessionState.PAUSED to DesiredState.PAUSED, offline.state to offline.desiredState)
        assertEquals(RemoteSessionRules.HOST_OFFLINE, offline.failure?.code)
        assertNull(offline.executionStartedAt, "Its execution ended")
        assertNull(offline.terminatedReason, "It is not ended")

        // Paused, it waits for its launch: only the retention ends it.
        assertEquals(offline.expiresAt, reconcile(offline))
        clock.now = offline.expiresAt!!
        assertEquals(clock.now, reconcile(offline))
        val expiring = stored(running)
        assertEquals(CloudSession.TERMINATED_EXPIRED, expiring.terminatedReason)
        assertNull(reconcile(expiring))
        assertEquals(SessionState.TERMINATED, stored(running).state)
        assertNull(reconcile(stored(running)))
    }

    @Test
    fun aPausedSessionKeptForeverWaitsForItsLaunch() = runTest {
        val kept = local(SessionState.PAUSED, DesiredState.PAUSED)
        assertNull(reconcile(kept))
        assertEquals(SessionState.PAUSED, stored(kept).state)
    }

    @Test
    fun anExecutionThatNeverReportsWithinTheStartTimeIsOffline() = runTest {
        val starting = local().copy(epoch = 1, executionStartedAt = t0 + 10.seconds).also(repository::put)
        clock.now = t0 + 100.seconds
        assertEquals(clock.now + 15.seconds, reconcile(starting), "A host may take 120 s to report")
        clock.now = t0 + 131.seconds
        reconcile(starting)
        val offline = stored(starting)
        assertEquals(SessionState.PAUSED to RemoteSessionRules.HOST_OFFLINE, offline.state to offline.failure?.code)
    }

    @Test
    fun aDeletedOrFinishedSessionEnds() = runTest {
        val deleted = hosted().copy(desiredState = DesiredState.TERMINATED).also(repository::put)
        assertNull(reconcile(deleted))
        val after = stored(deleted)
        assertEquals(SessionState.TERMINATED to CloudSession.TERMINATED_DELETED, after.state to after.terminatedReason)
        assertNull(reconcile(after))
        assertNull(reconcile(local(SessionState.FAILED, DesiredState.RUNNING)))
    }
}
