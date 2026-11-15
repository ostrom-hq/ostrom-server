// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.CloudSettingsSource
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionIdempotencyConflictException
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CreateRemoteSessionUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.EndRemovedDeviceSessions
import run.ostrom.server.runners.domain.HostRemoteSessionUseCaseImpl
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.OwnerLoginPrincipalResolver
import run.ostrom.server.runners.domain.RemoteHost
import run.ostrom.server.runners.domain.RemoteSessionDraft
import run.ostrom.server.runners.domain.RemoteSessionException
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.BOB
import run.ostrom.server.runners.fakes.BOB_LAPTOP
import run.ostrom.server.runners.fakes.DESKTOP
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.LAPTOP
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.REMOTE_LOGINS
import run.ostrom.server.runners.fakes.RemoteLogins
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.TestOrgResolver
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.CloudSettingsRemoteIdleTimeout
import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.SessionRef
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RemoteSessionsTest {
    private class WarningLogger : Logger {
        val warnings = mutableListOf<Map<String, Any?>>()
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            if (level == LogLevel.WARN) warnings += fields
        }
        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private val logger = WarningLogger()
    private val repository = InMemoryCloudSessionsRepository()
    private val devices = FakeSignedInDevices()
    private val clock = SettableClock()
    private var idle = 60
    private val create = CreateRemoteSessionUseCaseImpl(repository, devices, { idle }, clock)
    private val host = HostRemoteSessionUseCaseImpl(repository, devices, clock)
    private val alice = Caller(ORG_A, ALICE.toString())
    private val laptop = REMOTE_LOGINS.getValue("alice-laptop")
    private val desktop = REMOTE_LOGINS.getValue("alice-desktop")
    private val draft = RemoteSessionDraft(name = null, agent = "claude", directoryName = "ostrom")

    private suspend fun created(principal: AuthenticatedPrincipal = laptop, from: RemoteSessionDraft = draft) =
        create(alice, principal, from).session

    private suspend fun refusal(block: suspend () -> Unit): RemoteSessionException =
        assertFailsWith<RemoteSessionException> { block() }

    @Test
    fun aSessionKindIsReadByItsWireName() {
        assertEquals(SessionKind.REMOTE, SessionKind.fromWire("remote"))
        assertEquals(SessionKind.CLOUD, SessionKind.fromWire("cloud"))
        assertNull(SessionKind.fromWire("local"))
    }

    @Test
    fun aDraftIsCheckedBeforeAnythingIsRead() {
        fun type(d: RemoteSessionDraft) = assertFailsWith<RemoteSessionException> {
            RemoteSessionRules.validateRemote(d)
        }.let { assertEquals(ErrorKind.INVALID_REQUEST, it.kind); it.type }

        assertEquals(AgentKind.CLAUDE, RemoteSessionRules.validateRemote(draft))
        assertEquals(AgentKind.CODEX, RemoteSessionRules.validateRemote(draft.copy(agent = "codex")))
        assertEquals("invalid_agent", type(draft.copy(agent = "gemini")))
        assertEquals("invalid_agent", type(draft.copy(agent = "unknown")))
        assertEquals("invalid_folder", type(draft.copy(folderId = "f")))
        assertEquals("invalid_resume", type(draft.copy(resumeSessionId = "r")))
        assertEquals("invalid_directory_name", type(draft.copy(directoryName = null)))
        assertEquals("invalid_directory_name", type(draft.copy(directoryName = "a/b")))
        assertEquals("invalid_directory_name", type(draft.copy(deviceId = "d", directoryName = "..")))
        assertEquals("invalid_permission_mode", type(draft.copy(permissionMode = "auto")))
        // A daemon's folder names the directory: none is needed then.
        assertEquals(
            AgentKind.CLAUDE,
            RemoteSessionRules.validateRemote(
                draft.copy(deviceId = "d", folderId = "f", resumeSessionId = "r", directoryName = null),
            ),
        )
        assertEquals(AgentKind.CLAUDE, RemoteSessionRules.validateRemote(draft.copy(permissionMode = "bypass")))
    }

    @Test
    fun aDirectoryNameIsALastSegmentOnly() {
        assertTrue(RemoteSessionRules.validDirectoryName("ostrom-server"))
        assertFalse(RemoteSessionRules.validDirectoryName(""))
        assertFalse(RemoteSessionRules.validDirectoryName("."))
        assertFalse(RemoteSessionRules.validDirectoryName(".."))
        assertFalse(RemoteSessionRules.validDirectoryName("a\\b"))
        assertFalse(RemoteSessionRules.validDirectoryName("a\nb"))
        assertFalse(RemoteSessionRules.validDirectoryName("x".repeat(256)))
        assertTrue(RemoteSessionRules.validDirectoryName("x".repeat(255)))
    }

    @Test
    fun aSessionWithoutHostIsHostedNowhere() {
        val refused = assertFailsWith<RemoteSessionException> { RemoteSessionRules.requireHost(LAPTOP, session()) }
        assertEquals("remote_host_removed", refused.type)
    }

    @Test
    fun aRemoteSessionStartsWaitingForItsHostOnTheCallersDevice() = runTest {
        idle = 15
        val session = created(from = draft.copy(model = " claude-opus-5-5 ", effort = "high", prompt = "Fix it"))

        assertEquals(SessionKind.REMOTE, session.kind)
        assertEquals(SessionMode.CONVERSATION, session.mode)
        assertEquals(SessionState.CREATING, session.state)
        assertEquals(RemoteSessionRules.WAITING_HOST, session.phase)
        assertEquals(RemoteHost(LAPTOP, "ostrom", "ask"), session.host)
        assertEquals("ostrom", session.name)
        assertEquals("claude-opus-5-5", session.model)
        assertEquals("high", session.effort)
        assertEquals("Fix it", session.prompt)
        assertEquals(15, session.limits.idleTimeoutMinutes)
        assertEquals(0, session.limits.maxRunMinutes)
        assertNull(session.profile)
        assertNull(session.repository)
        assertNull(session.permissionBypass)
        assertEquals(session, repository.sessions[session.id])
    }

    @Test
    fun aBypassedRemoteSessionApprovesEveryToolFromItsCreation() = runTest {
        val session = created(from = draft.copy(name = "  my session ", permissionMode = "bypass", prompt = " "))

        assertEquals("my session", session.name)
        assertEquals("bypass", session.host?.permissionMode)
        assertEquals(ALICE.toString(), session.permissionBypass?.by)
        assertEquals("credential-alice-laptop", session.permissionBypass?.credentialId)
        assertEquals(clock.now, session.permissionBypass?.at)
        assertNull(session.prompt)
        // A blank name or model is none: the directory names it.
        val unnamed = created(from = draft.copy(name = " ", model = " "))
        assertEquals("ostrom", unnamed.name)
        assertNull(unnamed.model)
        val empty = created(from = draft.copy(name = "", model = ""))
        assertEquals("ostrom" to null, empty.name to empty.model)
    }

    @Test
    fun aRemoteSessionNeedsTheDeviceOfAPersonsSignIn() = runTest {
        val service = AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("cloud:write"))
        val old = REMOTE_LOGINS.getValue("alice-old")

        for (principal in listOf(service, old)) {
            val refused = refusal { created(principal) }
            assertEquals(ErrorKind.UNPROCESSABLE, refused.kind)
            assertEquals("remote_requires_device", refused.type)
        }
        val withoutIdentity = CreateRemoteSessionUseCaseImpl(repository, null, { idle }, clock)
        assertEquals("remote_requires_device", refusal { withoutIdentity(alice, laptop, draft) }.type)
        assertTrue(repository.sessions.isEmpty())
    }

    @Test
    fun anotherDeviceIsNoTargetYet() = runTest {
        val refused = refusal { created(from = draft.copy(deviceId = DESKTOP.toString(), folderId = "f")) }
        assertEquals("device_target_unsupported", refused.type)
        assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
    }

    @Test
    fun theFieldsOfARemoteSessionAreChecked() = runTest {
        for (bad in listOf(
            draft.copy(name = "x".repeat(121)),
            draft.copy(name = "a\u0000b"),
            draft.copy(model = "not a model"),
            draft.copy(prompt = "x".repeat(20_001)),
        )) {
            assertFailsWith<InvalidCloudSessionRequestException> { created(from = bad) }
        }
        assertFailsWith<CloudConversationException> { created(from = draft.copy(effort = "a b c")) }
    }

    @Test
    fun aKeyedCreationReplaysTheSameRequestAndRefusesAnother() = runTest {
        val first = create(alice, laptop, draft, "key" to "digest")
        assertFalse(first.replayed)
        val again = create(alice, laptop, draft, "key" to "digest")
        assertTrue(again.replayed)
        assertEquals(first.session.id, again.session.id)
        assertFailsWith<CloudSessionIdempotencyConflictException> { create(alice, laptop, draft, "key" to "other") }
        // The same key and request from another device never replays the first device's session.
        assertFailsWith<CloudSessionIdempotencyConflictException> { create(alice, desktop, draft, "key" to "digest") }
        assertEquals(1, repository.sessions.size)
    }

    @Test
    fun aHostStartsANewEpochThatFencesThePreviousOne() = runTest {
        val session = created()

        val first = host(alice, laptop, session.id)
        assertEquals(ORG_A, first.orgId)
        assertEquals(1, first.epoch)
        assertEquals(SessionState.CREATING, first.session.state)
        assertEquals(RemoteSessionRules.STARTING_HOST, first.session.phase)
        assertEquals(clock.now, first.session.executionStartedAt)

        val second = host(alice, laptop, session.id)
        assertEquals(2, second.epoch)
        assertEquals(second.session, repository.sessions[session.id])
    }

    @Test
    fun aPausedOrRunningRemoteSessionResumesOnItsHost() = runTest {
        val session = created()
        for (state in listOf(SessionState.PAUSED, SessionState.RUNNING)) {
            repository.put(
                repository.sessions.getValue(session.id).copy(
                    state = state,
                    desiredState = DesiredState.PAUSED,
                    failure = SessionFailure("host_offline", "Gone.", retryable = true),
                    lastHeartbeatAt = clock.now,
                    runnerTokenHash = "hash",
                ),
            )
            val hosted = host(alice, laptop, session.id).session
            assertEquals(SessionState.RESUMING, hosted.state)
            assertEquals(DesiredState.RUNNING, hosted.desiredState)
            assertNull(hosted.failure)
            assertNull(hosted.lastHeartbeatAt)
            assertNull(hosted.runnerTokenHash)
        }
    }

    @Test
    fun onlyTheOwnersOwnDeviceHostsARemoteSession() = runTest {
        val session = created()
        val cloud = session().copy(ownerId = ALICE.toString()).also(repository::put)

        assertEquals("not_remote", refusal { host(alice, laptop, cloud.id) }.type)
        assertEquals("remote_host_mismatch", refusal { host(alice, desktop, session.id) }.type)
        val old = REMOTE_LOGINS.getValue("alice-old")
        assertEquals("remote_requires_device", refusal { host(alice, old, session.id) }.type)
        assertFailsWith<CloudSessionNotFoundException> { host(Caller(ORG_A, BOB.toString()), laptop, session.id) }
        repository.put(session.copy(host = session.host?.copy(deviceId = null)))
        val removed = refusal { host(alice, laptop, session.id) }
        assertEquals("remote_host_removed", removed.type)
        assertEquals(ErrorKind.CONFLICT, removed.kind)
    }

    @Test
    fun anEndedOrPausingRemoteSessionIsNotHosted() = runTest {
        val session = created()
        repository.put(session.copy(state = SessionState.PAUSING))
        assertEquals("session_pausing", refusal { host(alice, laptop, session.id) }.type)
        repository.put(session.copy(desiredState = DesiredState.TERMINATED))
        assertEquals("session_not_running", refusal { host(alice, laptop, session.id) }.type)
        repository.put(session.copy(state = SessionState.TERMINATED))
        assertEquals("session_not_running", refusal { host(alice, laptop, session.id) }.type)
    }

    @Test
    fun aHostRetriesARaceItLosesThenGivesUp() = runTest {
        val session = created()
        repository.loseNextRaces = 1
        assertEquals(1, host(alice, laptop, session.id).epoch)
        repository.loseNextRaces = 5
        assertEquals("session_busy", refusal { host(alice, laptop, session.id) }.type)
    }

    @Test
    fun removingADeviceEndsItsRemoteSessions() = runTest {
        val live = created()
        val ended = created().copy(state = SessionState.TERMINATED).also(repository::put)
        val elsewhere = created(desktop)

        EndRemovedDeviceSessions(repository, clock, logger).removed(ALICE, LAPTOP)

        val after = repository.sessions.getValue(live.id)
        assertEquals(DesiredState.TERMINATED, after.desiredState)
        assertEquals(RemoteSessionRules.DEVICE_REMOVED, after.terminatedReason)
        assertNull(after.host?.deviceId)
        assertNull(repository.sessions.getValue(ended.id).host?.deviceId)
        assertEquals(DESKTOP, repository.sessions.getValue(elsewhere.id).host?.deviceId)
    }

    @Test
    fun aSessionThatCannotBeEndedIsLoggedAndTheNextOneStillEnds() = runTest {
        val first = created()
        val second = created()
        val failing = object : CloudSessionsRepository by repository {
            override suspend fun endOnRemovedDevice(ref: SessionRef, deviceId: Uuid, now: Instant): Boolean {
                if (ref.id == first.id) error("database unavailable")
                return repository.endOnRemovedDevice(ref, deviceId, now)
            }
        }

        EndRemovedDeviceSessions(failing, clock, logger).removed(ALICE, LAPTOP)

        assertEquals(DesiredState.RUNNING, repository.sessions.getValue(first.id).desiredState)
        assertEquals(DesiredState.TERMINATED, repository.sessions.getValue(second.id).desiredState)
        assertEquals(listOf(first.id.toString()), logger.warnings.map { it["session_id"] })
    }

    @Test
    fun anUnreadableDeviceIsLoggedAndLeftToTheReconciler() = runTest {
        val live = created()
        val failing = object : CloudSessionsRepository by repository {
            override suspend fun hostedOn(deviceId: Uuid, live: Boolean): List<SessionRef> =
                error("database unavailable")
        }

        EndRemovedDeviceSessions(failing, clock, logger).removed(ALICE, LAPTOP)

        assertEquals(DesiredState.RUNNING, repository.sessions.getValue(live.id).desiredState)
        assertEquals(listOf<Any?>(null), logger.warnings.map { it["session_id"] })
    }

    @Test
    fun aCancelledRemovalIsNeverSwallowed() = runTest {
        created()
        val cancelled = object : CloudSessionsRepository by repository {
            override suspend fun hostedOn(deviceId: Uuid, live: Boolean): List<SessionRef> =
                throw CancellationException("stop")
        }

        assertFailsWith<CancellationException> {
            EndRemovedDeviceSessions(cancelled, clock, logger).removed(ALICE, LAPTOP)
        }
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun theIdleTimeoutIsTheOrganizationsCloudSettingOrTheDefault() = runTest {
        val settings = CloudSettingsSource { CloudSettings(remoteIdleTimeoutMinutes = 5) }
        assertEquals(5, CloudSettingsRemoteIdleTimeout(settings).minutes(ORG_A))
        assertEquals(60, CloudSettingsRemoteIdleTimeout(null).minutes(ORG_A))
    }
}

class OwnerLoginPrincipalResolverTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val devices = FakeSignedInDevices()
    private val clock = SettableClock()
    private val resolver = OwnerLoginPrincipalResolver(RemoteLogins, devices, TestOrgResolver, repository)
    private val alice = Caller(ORG_A, ALICE.toString())
    private val create = CreateRemoteSessionUseCaseImpl(repository, devices, { 60 }, clock)
    private val host = HostRemoteSessionUseCaseImpl(repository, devices, clock)
    private val laptop = REMOTE_LOGINS.getValue("alice-laptop")
    private val draft = RemoteSessionDraft(name = null, agent = "claude", directoryName = "ostrom")

    private fun resolverOf(logins: run.ostrom.server.kernel.auth.TokenAuthenticator) =
        OwnerLoginPrincipalResolver(logins, devices, TestOrgResolver, repository)

    private suspend fun hosted(): CloudSession {
        val session = create(alice, laptop, draft).session
        return host(alice, laptop, session.id).session
    }

    @Test
    fun twoLiveRemoteSessionsOnOneDeviceAreEachHostedWithTheirOwnEpoch() = runTest {
        val one = hosted()
        val two = hosted()
        val again = host(alice, laptop, two.id).session

        assertEquals(RunnerExecution(ORG_A, one.id, 1), resolver.resolve("alice-laptop", one.id, 1))
        assertEquals(RunnerExecution(ORG_A, two.id, 2), resolver.resolve("alice-laptop", two.id, again.epoch))
        // The previous execution of the second one is fenced.
        assertNull(resolver.resolve("alice-laptop", two.id, 1))
        // Without the claim, the credential is the device's first live execution.
        assertEquals(RunnerExecution(ORG_A, one.id, 1), resolver.resolve("alice-laptop"))
    }

    @Test
    fun aSignInFromAnotherDeviceOrPersonIsNoHost() = runTest {
        val session = hosted()

        assertNull(resolver.resolve("alice-desktop", session.id, 1))
        assertNull(resolver.resolve("alice-desktop"))
        assertNull(resolver.resolve("bob-laptop", session.id, 1))
        assertNull(resolver.resolve("alice-old", session.id, 1))
        assertNull(resolver.resolve("unknown", session.id, 1))
        assertNull(resolver.resolve("unknown"))
        assertNull(resolver.resolve("alice-laptop", Uuid.random(), 1))
    }

    @Test
    fun onlyTheOwnersLiveRemoteExecutionIsAccepted() = runTest {
        val session = hosted()
        val cloud = session().copy(ownerId = ALICE.toString(), executionStartedAt = clock.now, epoch = 1)
            .also(repository::put)

        assertNull(resolver.resolve("alice-laptop", cloud.id, 1))
        // Bob's session on Alice's device (it cannot happen, the device is the creator's) is not hers to host.
        repository.put(session.copy(ownerId = BOB.toString()))
        assertNull(resolver.resolve("alice-laptop", session.id, 1))
        repository.put(session.copy(orgId = ORG_B))
        assertNull(resolver.resolve("alice-laptop", session.id, 1))
        repository.put(session.copy(executionStartedAt = null))
        assertNull(resolver.resolve("alice-laptop", session.id, 1))
        assertNull(resolver.resolve("alice-laptop"), "Its device hosts no live execution")
        repository.put(session.copy(host = null))
        assertNull(resolver.resolve("alice-laptop", session.id, 1))
        repository.put(session.copy(state = SessionState.TERMINATED))
        assertNull(resolver.resolve("alice-laptop", session.id, 1))
        assertNull(resolver.resolve("alice-laptop"))
        repository.put(session)
        assertNotNull(resolver.resolve("alice-laptop", session.id, 1))
    }

    @Test
    fun aServiceKeyOrADevicelessSignInIsNoHost() = runTest {
        val service = object : run.ostrom.server.kernel.auth.TokenAuthenticator {
            override suspend fun authenticate(token: String) =
                AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("cloud:write"))
        }
        val session = hosted()
        assertNull(resolverOf(service).resolve("k", session.id, 1))
        val unsigned = object : run.ostrom.server.kernel.auth.TokenAuthenticator {
            override suspend fun authenticate(token: String) =
                REMOTE_LOGINS.getValue("alice-laptop").copy(signedIn = false)
        }
        assertNull(resolverOf(unsigned).resolve("k", session.id, 1))
        // A member of no organization.
        val nobody = object : run.ostrom.server.kernel.auth.TokenAuthenticator {
            override suspend fun authenticate(token: String) =
                REMOTE_LOGINS.getValue("alice-laptop").copy(subjectId = "nobody")
        }
        assertNull(resolverOf(nobody).resolve("k", session.id, 1))
    }
}
