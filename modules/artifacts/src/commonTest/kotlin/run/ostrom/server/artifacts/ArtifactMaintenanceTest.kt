// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactBlob
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactMaintenance
import run.ostrom.server.artifacts.domain.ArtifactMaintenanceRepository
import run.ostrom.server.artifacts.domain.storage.ArtifactKeys
import run.ostrom.server.artifacts.domain.storage.BucketArtifactContents
import run.ostrom.server.artifacts.domain.storage.BucketUploadChunks
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.artifacts.fakes.InMemoryBucket
import run.ostrom.server.artifacts.fakes.InMemoryStorage
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.TestClock
import run.ostrom.server.artifacts.fakes.active
import run.ostrom.server.artifacts.infrastructure.ArtifactMaintenanceSweeper
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TC-ART-34: the maintenance of artifacts, step by step, one organization's failure apart from the others. */
class ArtifactMaintenanceTest {
    private val storage = InMemoryStorage().active(ORG_A)
    private val bucket = InMemoryBucket()
    private val clock = TestClock()
    private val rows = Rows()
    private val events = mutableListOf<Triple<String, String, Uuid?>>()
    private val maintenance = ArtifactMaintenance(
        rows, BucketArtifactContents(storage, bucket), BucketUploadChunks(storage, bucket),
        ArtifactEvents { caller, event, id, _ ->
            assertEquals(PrincipalKind.SERVICE, caller.principal?.kind)
            events += Triple(caller.memberId, event, id)
        },
        clock,
    )

    /** The rows of each organization; [failing] fails its first step. */
    private class Rows : ArtifactMaintenanceRepository {
        val asked = mutableListOf<List<Instant>>()
        var failing: Throwable? = null
        val forgotten = mutableListOf<Pair<Uuid, Instant>>()
        val uploads = mutableMapOf<Uuid, List<Uuid>>()
        val ended = mutableListOf<Uuid>()
        val expired = mutableMapOf<Uuid, List<Uuid>>()
        val latest = mutableMapOf<Uuid, Int>()

        override suspend fun organizations(now: Instant, drafts: Instant, publications: Instant, limit: Int) =
            listOf(ORG_B, ORG_A).also { asked += listOf(now, drafts, publications) }

        override suspend fun forgetPublications(orgId: Uuid, before: Instant) {
            if (orgId == ORG_B) failing?.let { throw it }
            forgotten += orgId to before
        }

        override suspend fun expiredUploads(orgId: Uuid, now: Instant, limit: Int) = uploads[orgId].orEmpty()

        override suspend fun endUpload(orgId: Uuid, uploadId: Uuid, now: Instant) {
            ended += uploadId
        }

        override suspend fun expired(orgId: Uuid, now: Instant, drafts: Instant, limit: Int) = expired[orgId].orEmpty()

        override suspend fun deleteExpired(orgId: Uuid, artifactId: Uuid, now: Instant, drafts: Instant) =
            latest.remove(artifactId)

        val marked = mutableMapOf<Uuid, List<ArtifactBlob>>()
        val collected = mutableListOf<ArtifactBlob>()

        override suspend fun markedBlobs(orgId: Uuid, now: Instant, limit: Int) = marked[orgId].orEmpty()

        override suspend fun forgetBlobs(orgId: Uuid, blobs: List<ArtifactBlob>) {
            collected += blobs
        }
    }

    private fun chunk(upload: Uuid) = ArtifactKeys.chunk("", "$ORG_A", "$upload", "sha", 0)

    private fun blob(artifact: Uuid) = ArtifactKeys.blob("", "$ORG_A", "$artifact", "sha")

    @Test
    fun aPassEndsTheExpiredUploadsAndDeletesTheDueArtifactsWithTheirFolders() = runTest {
        val upload = Uuid.random()
        val (retained, draft, raced) = Triple(Uuid.random(), Uuid.random(), Uuid.random())
        rows.uploads[ORG_A] = listOf(upload)
        rows.expired[ORG_A] = listOf(retained, draft, raced)
        // `raced` was published again since it was read: it is no longer due.
        rows.latest += mapOf(retained to 3, draft to 0)
        val keys = listOf(chunk(upload), blob(retained), blob(draft), blob(raced))
        bucket.objects += keys.associateWith { byteArrayOf(1) }

        assertEquals(2, maintenance.run())

        val now = clock.now
        assertEquals(listOf(listOf(now, now - 24.hours, now - 1.hours)), rows.asked)
        assertEquals(listOf(ORG_B to now - 1.hours, ORG_A to now - 1.hours), rows.forgotten)
        assertEquals(listOf(upload), rows.ended)
        assertEquals(setOf(blob(raced)), bucket.objects.keys)
        // A draft goes silently; an artifact past its retention is audited, by the system.
        assertEquals(listOf<Triple<String, String, Uuid?>>(Triple("system", "retention_expired", retained)), events)
    }

    @Test
    fun theMarkedContentsAreDeletedFromTheBucketThenForgotten() = runTest {
        val artifact = Uuid.random()
        val blobs = listOf("one", "two").map { ArtifactBlob(artifact, it) }
        val keys = blobs.map { ArtifactKeys.blob("", "$ORG_A", "$artifact", it.sha256) }
        bucket.objects += keys.associateWith { byteArrayOf(1) }
        rows.marked[ORG_A] = blobs
        // Org B has no bucket: its marked contents wait for one.
        rows.marked[ORG_B] = listOf(ArtifactBlob(artifact, "three"))
        maintenance.run()
        assertEquals(blobs, rows.collected)
        assertEquals(emptySet(), bucket.objects.keys)
        // Refused or failed: nothing is forgotten, the next pass tries again.
        rows.collected.clear()
        bucket.objects += keys.associateWith { byteArrayOf(1) }
        bucket.refuseDelete = true
        maintenance.run()
        bucket.refuseDelete = false
        bucket.beforeEach = { call -> if (call == "delete") throw ObjectStoreException(500, "InternalError") }
        maintenance.run()
        assertEquals(emptyList(), rows.collected)
        bucket.beforeEach = { call -> if (call == "delete") throw CancellationException("stopping") }
        assertFailsWith<CancellationException> { maintenance.run() }
    }

    @Test
    fun anUploadTheBucketKeepsIsEndedLater() = runTest {
        val upload = Uuid.random()
        rows.uploads[ORG_A] = listOf(upload)
        bucket.objects[chunk(upload)] = byteArrayOf(1)
        bucket.refuseDelete = true
        maintenance.run()
        assertEquals(emptyList(), rows.ended)
        // Without a bucket there is nothing left to delete.
        rows.uploads[ORG_B] = listOf(upload)
        rows.uploads.remove(ORG_A)
        maintenance.run()
        assertEquals(listOf(upload), rows.ended)
    }

    @Test
    fun aFailingOrganizationLeavesTheOthersAndACancellationStopsThePass() = runTest {
        rows.failing = IllegalStateException("database")
        maintenance.run()
        assertEquals(listOf(ORG_A), rows.forgotten.map { it.first })
        rows.failing = CancellationException("stopping")
        assertFailsWith<CancellationException> { maintenance.run() }
    }

    private class RecordingLogger : Logger {
        val lines = mutableListOf<Pair<LogLevel, String>>()

        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += level to message
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    @Test
    fun theSweeperRunsItsPassesLogsAFailureAndStopsWhenCancelled() = runTest {
        val logger = RecordingLogger()
        val failures = mutableListOf<Exception>(IllegalStateException("database"))
        var passes = 0
        ArtifactMaintenanceSweeper({
            passes++
            failures.removeFirstOrNull()?.let { throw it }
        }, logger, 10.milliseconds, passes = 2).run()
        assertEquals(2, passes)
        assertEquals(listOf(LogLevel.ERROR to "artifacts maintenance failed"), logger.lines)
        val cancelled = ArtifactMaintenanceSweeper({ throw CancellationException("stopping") }, logger)
        assertFailsWith<CancellationException> { cancelled.run() }
        assertEquals(1, logger.lines.size)
    }

    @Test
    fun theModuleRunsTheMaintenanceInTheServersScope() = runTest {
        val swept = CompletableDeferred<Unit>()
        val due = object : ArtifactMaintenanceRepository by rows {
            override suspend fun organizations(now: Instant, drafts: Instant, publications: Instant, limit: Int) =
                emptyList<Uuid>().also { swept.complete(Unit) }
        }
        val contents = BucketArtifactContents(storage, bucket)
        val pass = ArtifactMaintenance(due, contents, BucketUploadChunks(storage, bucket), { _, _, _, _ -> })
        val koin = koinApplication {
            modules(
                module {
                    single { pass }
                    single<Logger> { RecordingLogger() }
                },
            )
        }.koin
        val job = Job()
        ArtifactsModule.start(CoroutineScope(coroutineContext + job), koin)
        swept.await()
        job.cancel()
    }
}
