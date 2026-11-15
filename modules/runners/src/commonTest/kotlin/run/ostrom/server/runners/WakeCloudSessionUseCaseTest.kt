// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudSessionWake
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.WakeCloudSessionUseCase
import run.ostrom.server.runners.domain.WakeCloudSessionUseCaseImpl
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.RunnersCloudSessionWakes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * An answer to a wait of a paused cloud session resumes it, as the system on its owner's behalf, with the rechecks of
 * an owner's resume (TeamCode's `CloudConversationService.wake`); anything else leaves the session as it is.
 */
class WakeCloudSessionUseCaseTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val plans = FixedPlans()
    private val clock = SettableClock()
    private val active = mutableSetOf("alice")
    private val members = object : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
            orgId == ORG_A && memberId in active
    }
    private val audit = mutableListOf<OrgAuditRecord>()
    private val waitId = Uuid.random()
    private val wake = WakeCloudSessionUseCaseImpl(
        repository,
        plans,
        RepositoryCloudUsageMeter(repository),
        members,
        OrgAuditTrail { audit += it },
        clock,
    )

    private fun paused(state: SessionState = SessionState.PAUSED) =
        session(state = state, desired = DesiredState.PAUSED).also(repository::put)

    @Test
    fun aPausedSessionResumesOnItsOwnersBehalfAndTheAuditLogSaysWhy() = runTest {
        val paused = paused()

        assertEquals(CloudSessionWake.RESUMED, wake(ORG_A, paused.id.toString(), waitId))
        assertEquals(DesiredState.RUNNING, repository.sessions.getValue(paused.id).desiredState)
        assertEquals(clock.now, repository.next[paused.id], "The reconciler looks at it now")
        val line = audit.single()
        assertEquals(ORG_A, line.orgId)
        assertEquals("cloud.session.woken", line.event)
        assertEquals("cloud_session", line.subjectType)
        assertEquals(paused.id.toString(), line.subjectId)
        assertEquals(PrincipalKind.SERVICE, line.actor.kind, "The server, not a person")
        assertEquals(mapOf("owner" to "alice", "waitId" to waitId.toString()), line.details)

        assertEquals(CloudSessionWake.NOT_PAUSED, wake(ORG_A, paused.id.toString(), waitId), "Idempotent")
        assertEquals(1, audit.size)
    }

    @Test
    fun aMessageWakesItsSessionAndTheAuditLogNamesIt() = runTest {
        val paused = paused()
        val inputId = Uuid.random()

        assertEquals(CloudSessionWake.RESUMED, wake.forMessage(ORG_A, paused.id, inputId))
        assertEquals(mapOf("owner" to "alice", "inputId" to inputId.toString()), audit.single().details)
        assertEquals(CloudSessionWake.NO_SESSION, wake.forMessage(ORG_B, paused.id, inputId))
    }

    @Test
    fun aPausingSessionResumesOncePaused() = runTest {
        val pausing = paused(SessionState.PAUSING)
        assertEquals(CloudSessionWake.RESUMED, wake(ORG_A, pausing.id.toString(), waitId))
        assertEquals(DesiredState.RUNNING, repository.sessions.getValue(pausing.id).desiredState)
    }

    @Test
    fun aSessionThatIsNoPausedCloudSessionIsLeftAsItIs() = runTest {
        val running = session(state = SessionState.RUNNING).also(repository::put)
        val deleting = session(state = SessionState.PAUSED, desired = DesiredState.TERMINATED).also(repository::put)
        val failed = session(state = SessionState.FAILED, desired = DesiredState.PAUSED).also(repository::put)

        assertEquals(CloudSessionWake.NO_SESSION, wake(ORG_A, "launch-segment-1", waitId), "A local launch's")
        assertEquals(CloudSessionWake.NO_SESSION, wake(ORG_A, Uuid.random().toString(), waitId))
        assertEquals(CloudSessionWake.NO_SESSION, wake(ORG_B, paused().id.toString(), waitId), "Another org's")
        assertEquals(CloudSessionWake.NOT_PAUSED, wake(ORG_A, running.id.toString(), waitId))
        assertEquals(CloudSessionWake.ENDED, wake(ORG_A, deleting.id.toString(), waitId))
        assertEquals(CloudSessionWake.ENDED, wake(ORG_A, failed.id.toString(), waitId))
        assertTrue(audit.isEmpty())
    }

    @Test
    fun aSessionPausedForATransferOnlyTheTransferResumes() = runTest {
        val moving = paused().copy(transferId = Uuid.random()).also(repository::put)
        assertEquals(CloudSessionWake.NOT_PAUSED, wake(ORG_A, moving.id.toString(), waitId))
        assertTrue(audit.isEmpty())
    }

    @Test
    fun aPausedSessionThatMayNotResumeStaysPaused() = runTest {
        val gone = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED, ownerId = "bob")
        repository.put(gone)
        assertEquals(CloudSessionWake.OWNER_GONE, wake(ORG_A, gone.id.toString(), waitId))
        val unbound = WakeCloudSessionUseCaseImpl(
            repository, plans, RepositoryCloudUsageMeter(repository), null, OrgAuditTrail { audit += it }, clock,
        )
        val paused = paused()
        assertEquals(CloudSessionWake.OWNER_GONE, unbound(ORG_A, paused.id.toString(), waitId), "No member known")

        plans.plan = CloudPlan.FREE.copy(maxRunningSessions = 1)
        repository.put(session(state = SessionState.RUNNING))
        assertEquals(CloudSessionWake.AT_CAP, wake(ORG_A, paused.id.toString(), waitId))
        repository.usage[ORG_A to "2026-10"] = 300 * 3600L
        assertEquals(CloudSessionWake.NO_HOURS, wake(ORG_A, paused.id.toString(), waitId))
        plans.plan = plans.plan.copy(available = false)
        assertEquals(CloudSessionWake.CLOSED, wake(ORG_A, paused.id.toString(), waitId))

        assertEquals(DesiredState.PAUSED, repository.sessions.getValue(paused.id).desiredState)
        assertTrue(audit.isEmpty())
    }

    @Test
    fun aSessionDeletedBetweenTheReadAndTheResumeIsEnded() = runTest {
        val paused = paused()
        val deleting = object : run.ostrom.server.runners.domain.CloudSessionsRepository by repository {
            override suspend fun resumeWithinCap(orgId: Uuid, id: Uuid, maxRunning: Int, now: kotlin.time.Instant) =
                run.ostrom.server.runners.domain.ResumeOutcome.NotFound
        }
        val racing = WakeCloudSessionUseCaseImpl(
            deleting, plans, RepositoryCloudUsageMeter(deleting), members, OrgAuditTrail { audit += it }, clock,
        )
        assertEquals(CloudSessionWake.ENDED, racing(ORG_A, paused.id.toString(), waitId))
        assertTrue(audit.isEmpty())
    }

    @Test
    fun theKernelsWakeLogsASessionThatStaysPausedNeverOneWithNothingToWake() = runTest {
        val logged = mutableListOf<Pair<String, Map<String, Any?>>>()
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
                logged += message to fields
            }

            override fun with(fields: Map<String, Any?>): Logger = this
        }
        var outcome = CloudSessionWake.RESUMED
        val fixed = object : WakeCloudSessionUseCase {
            override suspend fun invoke(orgId: Uuid, sessionId: String, waitId: Uuid) = outcome
            override suspend fun forMessage(orgId: Uuid, sessionId: Uuid, inputId: Uuid) = outcome
        }
        val wakes = RunnersCloudSessionWakes(fixed, logger)

        assertTrue(wakes.wake(ORG_A, "s1", waitId))
        for (quiet in listOf(CloudSessionWake.NOT_PAUSED, CloudSessionWake.NO_SESSION, CloudSessionWake.ENDED)) {
            outcome = quiet
            assertFalse(wakes.wake(ORG_A, "s1", waitId))
        }
        assertTrue(logged.isEmpty())
        outcome = CloudSessionWake.AT_CAP
        assertFalse(wakes.wake(ORG_A, "s1", waitId))
        val fields =
            mapOf<String, Any?>("orgId" to ORG_A, "sessionId" to "s1", "waitId" to waitId, "reason" to "AT_CAP")
        assertEquals(listOf("cloud session not woken by its wait" to fields), logged)
    }
}
