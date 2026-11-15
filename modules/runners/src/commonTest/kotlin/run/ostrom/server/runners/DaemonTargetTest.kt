// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CreateRemoteSessionUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.RefuseRemoteHostUseCaseImpl
import run.ostrom.server.runners.domain.RemoteSessionDraft
import run.ostrom.server.runners.domain.RemoteSessionException
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE_USER
import run.ostrom.server.runners.fakes.BOB_LAPTOP
import run.ostrom.server.runners.fakes.DESKTOP
import run.ostrom.server.runners.fakes.FakeDeviceDaemons
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.LAPTOP
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.aliceLogin
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * A remote session started on another device through its daemon (TeamCode's `targetFolder` and `refuseHost`): only on
 * the owner's own online device, in a folder it published; its daemon refuses it while it waits.
 */
class DaemonTargetTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val daemons = FakeDeviceDaemons()
    private val clock = SettableClock()
    private val create = CreateRemoteSessionUseCaseImpl(repository, FakeSignedInDevices(), { 60 }, clock, daemons)
    private val refuse = RefuseRemoteHostUseCaseImpl(repository, clock)
    private val alice = Caller(ORG_A, "alice")
    private val browser = AuthenticatedPrincipal(ALICE_USER.toString(), PrincipalKind.USER, setOf("user:*"))
    private val folder = DaemonFolder("f1", "ostrom", "~/git/ostrom")
    private val draft = RemoteSessionDraft(name = null, agent = "claude", deviceId = LAPTOP.toString(), folderId = "f1")

    private suspend fun online() {
        daemons.publish(LAPTOP, listOf(folder))
        daemons.touch(LAPTOP, "1.0.0", emptyList(), clock.now())
    }

    private suspend fun refusal(type: String, block: suspend () -> Unit): RemoteSessionException {
        val refused = assertFailsWith<RemoteSessionException> { block() }
        assertEquals(type, refused.type)
        return refused
    }

    @Test
    fun theOwnerStartsASessionOnTheirOnlineDeviceInAPublishedFolderFromTheConsole() = runTest {
        online()

        val created = create(alice, browser, draft.copy(permissionMode = "bypass")).session

        assertEquals(LAPTOP, created.host?.deviceId)
        assertEquals("ostrom", created.host?.directoryName)
        assertEquals("f1", created.host?.folderId)
        assertEquals("ostrom", created.name)
        assertEquals(RemoteSessionRules.WAITING_HOST, created.phase)
        assertEquals(RemoteSessionRules.BYPASS, created.host?.permissionMode)
    }

    @Test
    fun theFoldersNameIsTheSessionsDirectory() = runTest {
        online()
        assertEquals("f1", create(alice, browser, draft.copy(directoryName = "ostrom")).session.host?.folderId)
        refusal("invalid_directory_name") { create(alice, browser, draft.copy(directoryName = "other")) }
    }

    @Test
    fun aKeyedStartIsBoundToTheTargetDevice() = runTest {
        online()
        val first = create(alice, browser, draft, "key" to "digest")
        val again = create(alice, browser, draft, "key" to "digest")
        assertEquals(first.session.id, again.session.id)
        assertEquals(true, again.replayed)
    }

    @Test
    fun aStartOnAnotherDeviceNeedsAPersonsSignInAFolderAndNoConversation() = runTest {
        online()
        val service = browser.copy(kind = PrincipalKind.SERVICE)
        assertEquals(ErrorKind.UNPROCESSABLE, refusal("remote_requires_login") { create(alice, service, draft) }.kind)
        refusal("remote_requires_login") { create(alice, browser.copy(subjectId = "not-a-uuid"), draft) }
        refusal("invalid_folder") { create(alice, browser, draft.copy(folderId = null)) }
        refusal("invalid_folder") { create(alice, browser, draft.copy(folderId = "a/b")) }
        refusal("resume_unsupported") {
            create(alice, browser, draft.copy(resumeSessionId = "0f3c2b1a-9d8e-4c7b-a6f5-e4d3c2b1a090"))
        }
    }

    @Test
    fun anotherPersonsOrAnUnknownDeviceIsNotFound() = runTest {
        refusal("device_not_found") { create(alice, browser, draft.copy(deviceId = BOB_LAPTOP.toString())) }
        refusal("device_not_found") { create(alice, browser, draft.copy(deviceId = "laptop")) }
        refusal("device_not_found") { create(alice, browser, draft.copy(deviceId = Uuid.random().toString())) }
    }

    @Test
    fun nothingIsQueuedForAnOfflineDeviceOrAFolderItDoesNotOffer() = runTest {
        refusal("device_offline") { create(alice, browser, draft) }
        online()
        clock.now += DaemonPresenceTtl
        refusal("device_offline") { create(alice, browser, draft) }
        daemons.touch(LAPTOP, null, emptyList(), clock.now())
        refusal("folder_not_published") { create(alice, browser, draft.copy(folderId = "f2")) }
    }

    @Test
    fun withoutTheDaemonsAServerRefusesAStartOnAnotherDevice() = runTest {
        val plain = CreateRemoteSessionUseCaseImpl(repository, FakeSignedInDevices(), { 60 }, clock)
        refusal("device_target_unsupported") { plain(alice, browser, draft) }
    }

    @Test
    fun theDaemonRefusesAnOrderAndTheSessionEndsWithItsReason() = runTest {
        online()
        val created = create(alice, browser, draft).session

        val refused = refuse(alice, LAPTOP, created.id, "folder_missing")

        assertEquals(DesiredState.TERMINATED, refused.desiredState)
        assertEquals(RemoteSessionRules.HOST_REFUSED, refused.terminatedReason)
        assertEquals(RemoteSessionRules.HOST_REFUSED, refused.failure?.code)
        assertEquals(true, "folder_missing" in refused.failure?.message.orEmpty())
        assertEquals(clock.now(), repository.next[created.id])
        refusal("not_waiting_host") { refuse(alice, LAPTOP, created.id, "folder_missing") }
    }

    @Test
    fun onlyTheSessionsDeviceRefusesASessionStartedThroughItsDaemon() = runTest {
        online()
        val created = create(alice, browser, draft).session
        refusal("invalid_reason") { refuse(alice, LAPTOP, created.id, "Folder missing") }
        refusal("remote_host_mismatch") { refuse(alice, DESKTOP, created.id, "folder_missing") }
        assertFailsWith<CloudSessionNotFoundException> {
            refuse(Caller(ORG_A, "bob"), LAPTOP, created.id, "folder_missing")
        }
        val here = RemoteSessionDraft(null, "claude", directoryName = "ostrom")
        val own = create(alice, aliceLogin("alice-laptop"), here).session
        assertFailsWith<CloudSessionNotFoundException> { refuse(alice, LAPTOP, own.id, "folder_missing") }
        val cloud = session(SessionState.CREATING, orgId = ORG_A).also { repository.sessions[it.id] = it }
        assertFailsWith<CloudSessionNotFoundException> { refuse(alice, LAPTOP, cloud.id, "folder_missing") }
    }

    @Test
    fun aSessionNoLongerWaitingForItsHostIsNotRefused() = runTest {
        online()
        val created = create(alice, browser, draft).session
        repository.sessions[created.id] = created.copy(phase = RemoteSessionRules.STARTING_HOST)
        refusal("not_waiting_host") { refuse(alice, LAPTOP, created.id, "folder_missing") }
        repository.sessions[created.id] = created.copy(state = SessionState.TERMINATED)
        refusal("not_waiting_host") { refuse(alice, LAPTOP, created.id, "folder_missing") }
    }

    @Test
    fun aRefusalRacingAnotherWriteGivesUpAfterFiveTries() = runTest {
        online()
        val created = create(alice, browser, draft).session
        val busy = object : run.ostrom.server.runners.domain.CloudSessionsRepository by repository {
            override suspend fun compareAndSet(
                session: run.ostrom.server.runners.domain.CloudSession,
                nextReconcileAt: kotlin.time.Instant?,
            ) = null
        }
        val racing = RefuseRemoteHostUseCaseImpl(busy, clock)
        refusal("session_busy") { racing(alice, LAPTOP, created.id, "folder_missing") }
    }

    private companion object {
        val DaemonPresenceTtl = run.ostrom.server.kernel.auth.DaemonPresence.ONLINE_TTL
    }
}
