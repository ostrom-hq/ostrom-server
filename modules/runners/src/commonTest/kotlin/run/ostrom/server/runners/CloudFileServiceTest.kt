// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudConversationServiceImpl
import run.ostrom.server.runners.domain.CloudFile
import run.ostrom.server.runners.domain.CloudFileBegin
import run.ostrom.server.runners.domain.CloudFileRules
import run.ostrom.server.runners.domain.CloudFileServiceImpl
import run.ostrom.server.runners.domain.CloudFileStorage
import run.ostrom.server.runners.domain.CloudFileStore
import run.ostrom.server.runners.domain.CloudSessionWake
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.InputRequest
import run.ostrom.server.runners.domain.NoCloudFileStorage
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.WakeCloudSessionUseCase
import run.ostrom.server.runners.fakes.FakeArtifactStorage
import run.ostrom.server.runners.fakes.FakeBucket
import run.ostrom.server.runners.fakes.FakeConversationStore
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** The service's branches no request reaches on PostgreSQL: a store that fails, the fence, and an empty bucket. */
class CloudFileServiceTest {
    private val sessions = InMemoryCloudSessionsRepository()
    private val clock = SettableClock()
    private val wake = object : WakeCloudSessionUseCase {
        override suspend fun invoke(orgId: Uuid, sessionId: String, waitId: Uuid) = error("Not a message")

        override suspend fun forMessage(orgId: Uuid, sessionId: Uuid, inputId: Uuid) = CloudSessionWake.RESUMED
    }
    private val inputs = FakeConversationStore(sessions)
    private val conversation = CloudConversationServiceImpl(inputs, sessions, wake, OrgAuditTrail {}, clock)
    private val bucket = FakeBucket()
    private val connection = FakeArtifactStorage().let { storage ->
        object : CloudFileStorage {
            override suspend fun active(orgId: Uuid): ArtifactStorageConnection? = storage.connection(orgId)

            override suspend fun any(orgId: Uuid): ArtifactStorageConnection? = storage.connection(orgId)
        }
    }
    private val alice = Caller(ORG_A, "alice", false)
    private val principal = AuthenticatedPrincipal("alice", PrincipalKind.USER, emptySet())

    /** A store that begins every upload and fails to abandon it with [failure]. */
    private class Store(private val failure: Exception, val due: List<CloudFile> = emptyList()) : CloudFileStore {
        var abandoned = 0
        val forgotten = mutableListOf<Uuid>()

        override suspend fun begin(file: CloudFile, expectedOwnerId: String, announcedBytes: Long) =
            CloudFileBegin.Started

        override suspend fun complete(
            orgId: Uuid,
            id: Uuid,
            name: String,
            contentType: String,
            size: Long,
            sha256: String,
            requestDigest: String?,
        ): CloudFile = error("Never completed")

        override suspend fun abandon(orgId: Uuid, id: Uuid) {
            abandoned++
            throw failure
        }

        override suspend fun deliverable(orgId: Uuid, sessionId: Uuid, epoch: Long, id: Uuid): CloudFile? = null

        override suspend fun expire(limit: Int) = 0

        override suspend fun deleting(limit: Int) = due

        override suspend fun forget(orgId: Uuid, ids: List<Uuid>) {
            forgotten += ids
        }

        override suspend fun purgeUsage(day: Long) = 0
    }

    private fun service(store: CloudFileStore, storage: CloudFileStorage = connection) =
        CloudFileServiceImpl(store, sessions, conversation, storage, bucket, null, OrgAuditTrail {}, clock)

    private fun running() = session(state = SessionState.RUNNING).copy(mode = SessionMode.CONVERSATION, epoch = 2)
        .also(sessions::put)

    @Test
    fun anAbandonThatFailsLeavesTheUploadToItsExpiry() = runTest {
        val session = running()
        val store = Store(IllegalStateException("database down"))
        val refusal = assertFailsWith<IllegalStateException> {
            service(store).upload(alice, principal, session.id, "a.txt", null, null) { error("client left") }
        }
        assertEquals("client left", refusal.message)
        assertEquals(1, store.abandoned)
        // A cancellation is never swallowed, and still abandons the upload.
        val cancelled = Store(CancellationException("stopped"))
        assertFailsWith<CancellationException> {
            service(cancelled).upload(alice, principal, session.id, "a.txt", null, null) { error("client left") }
        }
        val leaving = Store(IllegalStateException())
        val left = assertFailsWith<CancellationException> {
            service(leaving).upload(alice, principal, session.id, "a.txt", null, null) {
                throw CancellationException("the call ended")
            }
        }
        assertEquals("the call ended" to 1, left.message to leaving.abandoned)
    }

    @Test
    fun withoutTheCloudModuleAMessagesFilesHaveTheDefaultBound() = runTest {
        val session = running()
        val request = InputRequest("", true, null, null, null, listOf(Uuid.random().toString()))
        conversation.send(alice, principal, session.id, request)
        assertEquals(25 * CloudFileRules.MEBIBYTE, inputs.lastNew?.fileBytes)
    }

    @Test
    fun aDownloadIsFencedToTheLiveExecution() = runTest {
        val session = running()
        val files = service(Store(IllegalStateException()))
        suspend fun fenced(execution: RunnerExecution) {
            assertFailsWith<ExecutionFencedException> { files.runnerFile(execution, Uuid.random()) }
        }
        fenced(RunnerExecution(ORG_A, Uuid.random(), 2))
        fenced(RunnerExecution(ORG_A, session.id, 1))
        sessions.put(session.copy(desiredState = DesiredState.TERMINATED))
        fenced(RunnerExecution(ORG_A, session.id, 2))
        sessions.put(session.copy(state = SessionState.PAUSED))
        fenced(RunnerExecution(ORG_A, session.id, 2))
    }

    @Test
    fun anUnfinishedUploadWithoutPartsIsForgotten() = runTest {
        val draft = CloudFile(
            Uuid.random(), ORG_A, Uuid.random(), "alice", null, "f", null, 0, null, null, null, null, clock.now, null,
            clock.now, deletingSince = clock.now,
        )
        val store = Store(IllegalStateException(), listOf(draft))
        assertEquals(1, service(store).sweep())
        assertEquals(listOf(draft.id), store.forgotten)
        assertNull(NoCloudFileStorage.active(ORG_A))
        assertNull(NoCloudFileStorage.any(ORG_A))
        val policy = service(store, NoCloudFileStorage).policy(alice)
        assertEquals("file_storage_not_configured", policy.reason)
    }
}
