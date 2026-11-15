// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CloudUsageMeter
import run.ostrom.server.runners.domain.CreateLocalSessionUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.HostRemoteSessionUseCaseImpl
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.LocalLaunches
import run.ostrom.server.runners.domain.LocalSessionDraft
import run.ostrom.server.runners.domain.LocalSessionRules
import run.ostrom.server.runners.domain.OnBehalfForbiddenException
import run.ostrom.server.runners.domain.OwnerLoginPrincipalResolver
import run.ostrom.server.runners.domain.RemoteSessionException
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.RunnerCredentialsImpl
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.RunnerHeartbeat
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.BOB
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.REMOTE_LOGINS
import run.ostrom.server.runners.fakes.RemoteLogins
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.TestOrgResolver
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The local session of an `ostrom <agent>` launch (TeamCode's `createLocal` and the local branch of `host`): opened
 * once per launch, only for the caller's own running launch, and hosted by the launch from any of its owner's devices.
 */
class LocalSessionsTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val devices = FakeSignedInDevices()
    private val clock = SettableClock()
    private val running = mutableSetOf(LAUNCH)
    private val asked = mutableListOf<Triple<Uuid, String, String>>()
    private val launches = LocalLaunches { orgId, userId, launchId ->
        asked += Triple(orgId, userId, launchId)
        orgId == ORG_A && userId == "alice" && launchId in running
    }
    private val create = CreateLocalSessionUseCaseImpl(repository, launches, clock)
    private val host = HostRemoteSessionUseCaseImpl(repository, devices, clock)
    private val alice = Caller(ORG_A, ALICE.toString(), credentialId = "credential-alice-laptop")
    private val laptop = REMOTE_LOGINS.getValue("alice-laptop")
    private val desktop = REMOTE_LOGINS.getValue("alice-desktop")
    private val draft = LocalSessionDraft(LAUNCH, name = null, agent = "claude")

    private suspend fun opened(from: LocalSessionDraft = draft) = create(alice, laptop, from).session

    private suspend fun refusal(block: suspend () -> Unit): String =
        assertFailsWith<RemoteSessionException> { block() }.type

    @Test
    fun aLaunchOpensOneSessionWithoutPodProfileOrCap() = runTest {
        val first = create(alice, laptop, draft)
        assertFalse(first.replayed)
        val session = first.session
        assertEquals(SessionKind.LOCAL to LAUNCH, session.kind to session.launchId)
        assertEquals(SessionState.CREATING to DesiredState.RUNNING, session.state to session.desiredState)
        assertEquals(RemoteSessionRules.WAITING_HOST, session.phase)
        assertEquals(SessionMode.CONVERSATION, session.mode)
        assertEquals(SessionLimits(0, 0, false), session.limits, "No idle pause, no run bound")
        assertEquals("claude", session.name, "The agent's name when none is given")
        assertNull(session.host, "No device is bound")
        assertNull(session.repository)
        assertNull(session.profile)
        assertEquals(ALICE.toString(), session.createdBy?.effectiveMemberId)
        assertEquals(listOf(Triple(ORG_A, "alice", LAUNCH)), asked)

        // A second call for the same launch returns the first session, whatever its state.
        repository.put(repository.sessions.getValue(session.id).copy(state = SessionState.RUNNING))
        val again = create(alice, laptop, draft.copy(name = "another name", agent = "codex"))
        assertTrue(again.replayed)
        assertEquals(session.id, again.session.id)
        assertEquals(1, repository.sessions.size)

        val named = opened(LocalSessionDraft(SECOND_LAUNCH.also(running::add), "  my run  ", "codex"))
        assertEquals("my run" to run.ostrom.server.runners.domain.AgentKind.CODEX, named.name to named.agent)
        val blank = opened(LocalSessionDraft(THIRD_LAUNCH.also(running::add), " ", "claude"))
        assertEquals("claude", blank.name)
    }

    @Test
    fun onlyTheCallersOwnRunningLaunchOpensOne() = runTest {
        assertFailsWith<OnBehalfForbiddenException> {
            create(alice.copy(memberId = BOB.toString(), initiatorId = alice.memberId), laptop, draft)
        }
        assertEquals("invalid_launch", refusal { opened(draft.copy(launchId = "run-1")) })
        assertEquals("invalid_launch", refusal { opened(draft.copy(launchId = "lch_" + "A".repeat(32))) })
        assertFailsWith<InvalidCloudSessionRequestException> { opened(draft.copy(name = "a\u0000b")) }
        assertFailsWith<InvalidCloudSessionRequestException> { opened(draft.copy(name = "n".repeat(121))) }
        assertEquals("invalid_agent", refusal { opened(draft.copy(agent = "gemini")) })
        assertEquals("invalid_agent", refusal { opened(draft.copy(agent = "unknown")) })
        assertTrue(asked.isEmpty(), "A malformed request reads no launch")

        val notFound = assertFailsWith<RemoteSessionException> { opened(draft.copy(launchId = SECOND_LAUNCH)) }
        assertEquals(ErrorKind.NOT_FOUND to "launch_not_found", notFound.kind to notFound.type)
        val bob = Caller(ORG_A, BOB.toString())
        assertEquals("launch_not_found", refusal { create(bob, REMOTE_LOGINS.getValue("bob-laptop"), draft) })
        val key = AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("cloud:write"))
        assertEquals("launch_not_found", refusal { create(alice, key, draft) })
        running.remove(LAUNCH)
        assertEquals("launch_not_found", refusal { opened() }, "An ended launch")
        running.add(LAUNCH)
        val noLaunches = CreateLocalSessionUseCaseImpl(repository, null, clock)
        assertEquals("launch_not_found", refusal { noLaunches(alice, laptop, draft) })
        assertTrue(repository.sessions.isEmpty())
    }

    @Test
    fun twoCallsForOneLaunchAtOnceMakeOneSession() = runTest {
        val first = Uuid.random()
        // The other call stores its session between this one's look and its insert: the unique index refuses it.
        val racing = object : CloudSessionsRepository by repository {
            override suspend fun createWithinCap(
                session: CloudSession,
                maxRunning: Int,
                journal: run.ostrom.server.kernel.audit.OrgAuditRecord?,
            ): Boolean {
                if (first !in repository.sessions) repository.createWithinCap(session.copy(id = first), maxRunning)
                return repository.createWithinCap(session, maxRunning, journal)
            }
        }
        val outcome = CreateLocalSessionUseCaseImpl(racing, launches, clock)(alice, laptop, draft)
        assertTrue(outcome.replayed)
        assertEquals(first, outcome.session.id)
        assertEquals(1, repository.sessions.size)
    }

    @Test
    fun aFailedInsertWithoutASessionOfTheLaunchIsReported() = runTest {
        val broken = object : CloudSessionsRepository by repository {
            override suspend fun createWithinCap(
                session: CloudSession,
                maxRunning: Int,
                journal: run.ostrom.server.kernel.audit.OrgAuditRecord?,
            ): Boolean = error("database down")
        }
        val error = assertFailsWith<IllegalStateException> {
            CreateLocalSessionUseCaseImpl(broken, launches, clock)(alice, laptop, draft)
        }
        assertEquals("database down", error.message)
        // Another member's session of the launch is never handed over (TeamCode's owner check).
        val bobs = opened().copy(id = Uuid.random(), ownerId = BOB.toString())
        repository.sessions.clear()
        repository.put(bobs)
        assertFailsWith<IllegalStateException> { opened() }
        val cancelled = object : CloudSessionsRepository by repository {
            override suspend fun createWithinCap(
                session: CloudSession,
                maxRunning: Int,
                journal: run.ostrom.server.kernel.audit.OrgAuditRecord?,
            ): Boolean = throw CancellationException("stopped")
        }
        repository.sessions.clear()
        assertFailsWith<CancellationException> {
            CreateLocalSessionUseCaseImpl(cancelled, launches, clock)(alice, laptop, draft)
        }
    }

    @Test
    fun theLaunchHostsItFromAnyOfItsOwnersDevices() = runTest {
        val session = opened()
        val first = host(alice, desktop, session.id)
        assertEquals(1L to SessionState.CREATING, first.epoch to first.session.state)
        assertEquals(RemoteSessionRules.STARTING_HOST, first.session.phase)
        val second = host(alice, laptop, session.id)
        assertEquals(2L, second.epoch, "Another device of the owner's takes the next epoch")
        // A service key or a sign-in of no device hosts nothing; another member finds nothing.
        val key = AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("cloud:write"))
        assertEquals("remote_requires_device", refusal { host(alice, key, session.id) })
        assertFailsWith<CloudSessionNotFoundException> {
            host(Caller(ORG_A, BOB.toString()), REMOTE_LOGINS.getValue("bob-laptop"), session.id)
        }
        // Back after a host_offline pause: the launch resumes it itself.
        repository.put(
            repository.sessions.getValue(session.id).copy(
                state = SessionState.PAUSED,
                desiredState = DesiredState.PAUSED,
                executionStartedAt = null,
            ),
        )
        val resumed = host(alice, laptop, session.id)
        assertEquals(SessionState.RESUMING, resumed.session.state)
        assertEquals(DesiredState.RUNNING, resumed.session.desiredState)
        // Deleted when the agent exits: it cannot be hosted again.
        repository.put(repository.sessions.getValue(session.id).copy(desiredState = DesiredState.TERMINATED))
        assertEquals("session_not_running", refusal { host(alice, laptop, session.id) })
    }

    @Test
    fun itsOwnersSignInFromAnyDeviceBeatsForItsCurrentEpochOnly() = runTest {
        val session = opened()
        host(alice, laptop, session.id)
        val owners = OwnerLoginPrincipalResolver(RemoteLogins, devices, TestOrgResolver, repository)
        assertEquals(RunnerExecution(ORG_A, session.id, 1), owners.resolve("alice-desktop", session.id, 1))
        assertEquals(RunnerExecution(ORG_A, session.id, 1), owners.resolve("alice-laptop", session.id, 1))
        assertNull(owners.resolve("bob-laptop", session.id, 1), "Another member's sign-in")
        assertNull(owners.resolve("alice-desktop", session.id, 2), "Another epoch")
        assertNull(owners.resolve("alice-old", session.id, 1), "A sign-in of no device")

        val records = mutableListOf<Long>()
        val meter = object : CloudUsageMeter {
            override suspend fun usedSeconds(orgId: Uuid, now: Instant): Long = 0

            override suspend fun record(orgId: Uuid, seconds: Long, newExecution: Boolean, now: Instant) {
                records += seconds
            }
        }
        val credentials = RunnerCredentialsImpl(repository, FakeRunnerTokens())
        val heartbeat = RunnerHeartbeatUseCaseImpl(repository, credentials, meter, clock, owners)
        val beat = RunnerHeartbeat(session.id, 1, true, false, null as Instant?, false)
        assertEquals(SessionState.RUNNING, heartbeat("alice-desktop", beat).state)
        assertTrue(records.isEmpty(), "No hour of the plan is counted")
        assertFailsWith<ExecutionFencedException> { heartbeat("alice-laptop", beat.copy(epoch = 2)) }
    }

    @Test
    fun theRefusalsOfALocalSessionAreItsOwn() {
        assertTrue(LocalSessionRules.validLaunchId(LAUNCH))
        assertFalse(LocalSessionRules.validLaunchId("lch_123"))
        val local: OstromException = LocalSessionRules.localSession()
        assertEquals(ErrorKind.CONFLICT to "local_session", local.kind to local.type)
        assertSame(ErrorKind.NOT_FOUND, LocalSessionRules.launchNotFound().kind)
    }

    private companion object {
        const val LAUNCH = "lch_0123456789abcdef0123456789abcdef"
        const val SECOND_LAUNCH = "lch_1123456789abcdef0123456789abcdef"
        const val THIRD_LAUNCH = "lch_2123456789abcdef0123456789abcdef"
    }
}
