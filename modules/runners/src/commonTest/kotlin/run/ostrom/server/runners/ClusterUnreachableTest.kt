// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.NoSessionProfiles
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl.Companion.CLEANUP_BLOCKED
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionProfiles
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A session whose cluster stops opening is retried for a bounded time, then stops without it (TeamCode's lost
 * execution and blocked deletion); a session of the former variables is given their cluster's profile first.
 */
class ClusterUnreachableTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val driver = FakeRuntimeDriver()
    private val clock = SettableClock()
    private var legacy: SessionProfile? = null
    private val profiles = object : SessionProfiles {
        override suspend fun choose(caller: Caller, profileId: Uuid?, image: String?) = null

        override suspend fun startFailure(session: CloudSession): SessionFailure? = null

        override suspend fun legacy(): SessionProfile? = legacy
    }
    private val told = mutableListOf<List<String>>()
    private val reconcile = reconciler(profiles)
    private val profile = SessionProfile(clusterId = "c", namespace = "ostrom", image = "r:1", workspaceGibibytes = 10)

    private fun reconciler(profiles: SessionProfiles) = ReconcileCloudSessionUseCaseImpl(
        repository, driver, FakeRunnerTokens(), FixedPlans(), RepositoryCloudUsageMeter(repository), clock,
        listOf(SessionEndListener { _, ids -> told += ids }),
        profiles,
    ) { BackgroundWork.DEFAULT_MAX_MINUTES }

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    /** Steps the session until [UNREACHABLE] has passed, each retry 30 seconds apart; returns the last answer. */
    private suspend fun exhaust(session: CloudSession): kotlin.time.Instant? {
        val start = clock.now
        assertEquals(start + RETRY, reconcile(stored(session)), "Marked, retried in 30 s")
        assertEquals(start, stored(session).clusterUnreachableSince)
        clock.now = start + 9.minutes
        assertEquals(clock.now + RETRY, reconcile(stored(session)), "Still within the bound")
        assertEquals(session.state, stored(session).state, "Nothing ends within the bound")
        clock.now = start + UNREACHABLE
        return reconcile(stored(session))
    }

    @Test
    fun aRunningSessionOnAClusterThatStopsOpeningPausesAndFreesItsPlace() = runTest {
        driver.unreachable = true
        val running = session(state = SessionState.RUNNING).copy(profile = profile, readyAt = clock.now)
            .also { repository.put(it) }
        assertNull(exhaust(running))
        val paused = stored(running)
        assertEquals(SessionState.PAUSED to DesiredState.PAUSED, paused.state to paused.desiredState)
        assertEquals("cluster_unavailable", paused.failure?.code)
        assertNull(paused.clusterUnreachableSince)
        assertNull(paused.runnerTokenHash)
        assertFalse(paused.state.holdsExecution, "No longer under the running cap")
    }

    @Test
    fun aFirstStartFailsAndAResumeOrAPauseEndsPaused() = runTest {
        driver.unreachable = true
        val first = session().copy(profile = profile, executionStartedAt = clock.now).also { repository.put(it) }
        assertNull(exhaust(first))
        assertEquals(SessionState.FAILED, stored(first).state)
        assertEquals("cluster_unavailable", stored(first).failure?.code)
        for (state in listOf(SessionState.RESUMING, SessionState.PAUSING)) {
            // A pause past its deadline stops its execution.
            val other = session(state = state)
                .copy(profile = profile, executionStartedAt = clock.now, epoch = 2)
                .copy(pausingSince = clock.now - 5.minutes)
                .also { repository.put(it) }
            assertNull(exhaust(other))
            assertEquals(SessionState.PAUSED to DesiredState.PAUSED, stored(other).state to stored(other).desiredState)
        }
    }

    @Test
    fun aDeletedSessionEndsWithItsCleanupBlockedThenCleanedOnceTheClusterOpens() = runTest {
        driver.unreachable = true
        val deleted = session(state = SessionState.RUNNING, desired = DesiredState.TERMINATED).copy(profile = profile)
            .also { repository.put(it) }
        assertEquals(clock.now + UNREACHABLE + RECHECK, exhaust(deleted))
        val ended = stored(deleted)
        assertEquals(SessionState.TERMINATED to CLEANUP_BLOCKED, ended.state to ended.phase)
        assertEquals("cluster_unavailable", ended.failure?.code)
        assertEquals(clock.now, ended.terminatedAt)
        assertEquals(listOf(listOf(deleted.id.toString())), told, "What its agent asked ends with it")

        clock.now += RECHECK
        assertEquals(clock.now + RECHECK, reconcile(stored(deleted)), "Rechecked every 10 minutes")
        driver.probe = true
        assertEquals(clock.now + RECHECK, reconcile(stored(deleted)), "Opens, then fails again: rechecked later")
        driver.unreachable = false
        driver.probe = null
        driver.workspaceDeleted = false
        assertEquals(clock.now + 2.seconds, reconcile(stored(deleted)), "Its workspace is still being deleted")
        driver.workspaceDeleted = true
        assertNull(reconcile(stored(deleted)))
        assertEquals("cleaned" to null, stored(deleted).phase to stored(deleted).failure)
        assertNull(reconcile(stored(deleted)), "Nothing left to do")
    }

    @Test
    fun aClusterThatOpensAgainStopsTheCount() = runTest {
        val since = clock.now
        val running = session(state = SessionState.RUNNING).copy(
            profile = profile,
            readyAt = clock.now,
            lastHeartbeatAt = clock.now,
            clusterUnreachableSince = since,
        ).also { repository.put(it) }
        clock.now += 1.minutes
        driver.unreachable = true
        assertEquals(clock.now + RETRY, reconcile(stored(running)), "Still unreachable: the count goes on")
        assertEquals(since, stored(running).clusterUnreachableSince)
        driver.unreachable = false
        assertEquals(clock.now, reconcile(stored(running)), "Looked at again at once")
        assertNull(stored(running).clusterUnreachableSince)
        assertEquals(SessionState.RUNNING, stored(running).state)
    }

    @Test
    fun aSessionOfTheFormerVariablesIsGivenTheirClustersProfileFirst() = runTest {
        val former = session(state = SessionState.PAUSED).also { repository.put(it) }
        legacy = profile
        assertEquals(clock.now, reconcile(former))
        assertEquals(profile, stored(former).profile)
        assertEquals(listOf<String>(), driver.calls, "Nothing asked of the cluster yet")

        // Docker and registered runners have none: the step goes on.
        val docker = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED).also { repository.put(it) }
        assertNull(reconciler(NoSessionProfiles)(docker))
        assertNull(stored(docker).profile)
        legacy = null
        assertNull(reconcile(docker), "No cluster of the former variables")
        val terminated = session(state = SessionState.TERMINATED).also { repository.put(it) }
        legacy = profile
        assertNull(reconcile(terminated), "A terminated session is never given one")
        assertNull(stored(terminated).profile)
    }

    private companion object {
        val RETRY = 30.seconds
        val UNREACHABLE = 10.minutes
        val RECHECK = 10.minutes
    }
}
