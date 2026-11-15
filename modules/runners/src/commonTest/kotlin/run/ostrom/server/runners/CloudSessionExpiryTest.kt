// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.KeepPausedSessions
import run.ostrom.server.runners.domain.PausedRetention
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeRuntimeDriver
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.infrastructure.CloudRuntimePausedRetention
import run.ostrom.server.runners.presentation.toResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Paused sessions expire as TeamCode's (`runners.cloud.retention.v1`, TC-CLR-6): `expiresAt` from the retention in
 * force when a session pauses, cleared by a resume; past it, the session is terminated with its workspace, `expired`.
 */
class CloudSessionExpiryTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val driver = FakeRuntimeDriver()
    private val clock = SettableClock()
    private val t0 = clock.now
    private var days: Int? = 30
    private val reconcile = reconciler { days }

    private fun reconciler(retention: PausedRetention) = ReconcileCloudSessionUseCaseImpl(
        repository,
        driver,
        FakeRunnerTokens(),
        FixedPlans(),
        RepositoryCloudUsageMeter(repository),
        clock,
        retention = retention,
        backgroundWork = { BackgroundWork.DEFAULT_MAX_MINUTES },
    )

    private fun put(session: CloudSession) = session.also { repository.put(it) }

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    private fun paused(expiresAt: Instant?, desired: DesiredState = DesiredState.PAUSED) =
        put(session(state = SessionState.PAUSED, desired = desired).copy(pausedAt = t0, expiresAt = expiresAt))

    /** A session paused before the upgrade, as the migration marks it. */
    private fun pausedBeforeTheUpgrade() = put(paused(null).copy(expiryBackfill = true))

    @Test
    fun aPauseExpiresAfterTheRetentionInForceAndIsLookedAtAgainThen() = runTest {
        val starting = put(session(state = SessionState.RESUMING, desired = DesiredState.PAUSED))

        assertEquals(t0 + 30.days, reconcile(starting))
        assertEquals(SessionState.PAUSED, stored(starting).state)
        assertEquals(t0 + 30.days, stored(starting).expiresAt)
    }

    @Test
    fun withoutRetentionAPausedSessionIsKept() = runTest {
        days = null
        val starting = put(session(state = SessionState.RESUMING, desired = DesiredState.PAUSED))

        assertNull(reconcile(starting))
        assertNull(stored(starting).expiresAt)
        assertNull(reconcile(stored(starting)), "Nothing to do until someone resumes it")
    }

    @Test
    fun aLaterChangeOfTheRetentionLeavesAPausedSessionsExpiry() = runTest {
        val session = paused(t0 + 30.days)
        days = 1

        assertEquals(t0 + 30.days, reconcile(session))
        assertEquals(t0 + 30.days, stored(session).expiresAt)
    }

    @Test
    fun anExpiredSessionIsTerminatedWithItsWorkspace() = runTest {
        val session = paused(t0 + 30.days)
        clock.now = t0 + 30.days

        assertEquals(clock.now, reconcile(session))
        val asked = stored(session)
        assertEquals(DesiredState.TERMINATED, asked.desiredState)
        assertEquals(CloudSession.TERMINATED_EXPIRED, asked.terminatedReason)

        assertNull(reconcile(asked))
        val ended = stored(session)
        assertEquals(SessionState.TERMINATED, ended.state)
        assertEquals(CloudSession.TERMINATED_EXPIRED, ended.terminatedReason)
        assertNull(ended.expiresAt)
        assertEquals(listOf("stop", "deleteWorkspace"), driver.calls)
        assertEquals("expired", ended.toResponse().terminatedReason)
        assertNull(ended.toResponse().expiresAt)
    }

    @Test
    fun anExpiryWinsOverAResumeAskedTooLate() = runTest {
        val session = paused(t0 + 1.days, desired = DesiredState.RUNNING)
        clock.now = t0 + 1.days + 1.seconds

        reconcile(session)
        assertEquals(DesiredState.TERMINATED, stored(session).desiredState)
    }

    @Test
    fun aResumeClearsTheExpiry() = runTest {
        val session = paused(t0 + 30.days, desired = DesiredState.RUNNING)

        assertEquals(t0, reconcile(session))
        assertEquals(SessionState.RESUMING, stored(session).state)
        assertNull(stored(session).expiresAt)
    }

    @Test
    fun aDeletedSessionSaysSo() = runTest {
        val session = paused(t0 + 30.days, desired = DesiredState.TERMINATED)

        assertNull(reconcile(session))
        assertEquals(CloudSession.TERMINATED_DELETED, stored(session).terminatedReason)
        assertNull(stored(session).expiresAt)
        assertEquals("deleted", stored(session).toResponse().terminatedReason)
    }

    @Test
    fun aSessionPausedBeforeTheUpgradeExpiresFromItsPauseNeverAtOnce() = runTest {
        val recent = pausedBeforeTheUpgrade()
        clock.now = t0 + 2.days

        assertEquals(t0 + 30.days, reconcile(recent))
        assertEquals(t0 + 30.days, stored(recent).expiresAt)
        assertFalse(stored(recent).expiryBackfill, "Given once")

        val old = pausedBeforeTheUpgrade()
        clock.now = t0 + 45.days
        assertEquals(clock.now + 1.days, reconcile(old), "Already past it: one more day")
        assertEquals(clock.now + 1.days, stored(old).expiresAt)
        assertEquals((clock.now + 1.days).toString(), stored(old).toResponse().expiresAt)

        val unknown = put(
            session(state = SessionState.PAUSED, desired = DesiredState.PAUSED).copy(expiryBackfill = true),
        )
        assertEquals(clock.now + 30.days, reconcile(unknown), "Without its pause: from now")

        days = null
        val kept = pausedBeforeTheUpgrade()
        assertNull(reconcile(kept))
        assertNull(stored(kept).expiresAt)
        assertFalse(stored(kept).expiryBackfill)
        days = 1
        assertNull(reconcile(stored(kept)), "Kept under no retention at the upgrade: a later setting changes nothing")
        assertNull(stored(kept).expiresAt)
    }

    @Test
    fun aSessionPausedUnderNoRetentionIsKeptWhenARetentionIsSetLater() = runTest {
        days = null
        val starting = put(session(state = SessionState.RESUMING, desired = DesiredState.PAUSED))
        assertNull(reconcile(starting))
        days = 7

        clock.now = t0 + 30.days
        assertNull(reconcile(stored(starting)))
        assertNull(stored(starting).expiresAt)
        assertEquals(SessionState.PAUSED, stored(starting).state)
    }

    @Test
    fun aPauseOrAResumeEndsTheUpgradesBackfillAndAnUnreadableRetentionIsThirtyDays() = runTest {
        val resumed = put(pausedBeforeTheUpgrade().copy(desiredState = DesiredState.RUNNING))
        reconcile(resumed)
        assertFalse(stored(resumed).expiryBackfill)

        val failing = reconciler { throw IllegalStateException("database down") }
        val starting = put(
            session(state = SessionState.RESUMING, desired = DesiredState.PAUSED).copy(expiryBackfill = true),
        )
        assertEquals(t0 + 30.days, failing(starting), "A pause never fails on its retention")
        assertFalse(stored(starting).expiryBackfill)

        val cancelled = reconciler { throw CancellationException("stopped") }
        val other = put(session(state = SessionState.RESUMING, desired = DesiredState.PAUSED))
        assertFailsWith<CancellationException> { cancelled(other) }
    }

    @Test
    fun anExecutionLostPausesTheSessionUntilItExpires() = runTest {
        val running = put(
            session(state = SessionState.RUNNING)
                .copy(epoch = 1, readyAt = t0, lastHeartbeatAt = t0, lastActivityAt = t0),
        )
        clock.now = t0 + 2.minutes

        assertEquals(clock.now + 30.days, reconcile(running))
        assertEquals(clock.now + 30.days, stored(running).expiresAt)
    }

    @Test
    fun theRetentionIsTheConsolesAndAnUnreadableOneIsThirtyDaysLoggedByItsClassOnly() = runTest {
        val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
                assertEquals(LogLevel.WARN, level)
                assertNull(error, "Never the error itself, which may carry a body")
                logged += message to fields
            }

            override fun with(fields: Map<String, Any?>): Logger = this
        }
        var read: () -> CloudRuntime = { CloudRuntime(pausedRetentionDays = null) }
        val runtime = object : CloudRuntimeSource {
            override suspend fun current() = read()
        }
        val retention = CloudRuntimePausedRetention(runtime, logger)
        assertNull(retention.days())
        read = { CloudRuntime(pausedRetentionDays = 7) }
        assertEquals(7, retention.days())
        assertTrue(logged.isEmpty())

        read = { throw IllegalStateException("secret body") }
        assertEquals(30, retention.days())
        assertEquals("IllegalStateException", logged.single().second["error_class"])
        read = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { retention.days() }
        assertNull(KeepPausedSessions.days(), "Without the cloud module, paused sessions are kept")
    }
}
