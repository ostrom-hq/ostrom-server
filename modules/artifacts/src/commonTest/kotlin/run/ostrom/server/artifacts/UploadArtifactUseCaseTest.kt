// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactNotFoundException
import run.ostrom.server.artifacts.domain.ArtifactNotWritableException
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.ManageArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import run.ostrom.server.artifacts.domain.UploadedFile
import run.ostrom.server.artifacts.domain.storage.ArtifactKeys
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageUnavailableException
import run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException
import run.ostrom.server.artifacts.domain.storage.BucketArtifactContents
import run.ostrom.server.artifacts.domain.storage.BucketUploadChunks
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.artifacts.domain.uploads.AnnouncedFile
import run.ostrom.server.artifacts.domain.uploads.ArtifactBlobMismatchException
import run.ostrom.server.artifacts.domain.uploads.ArtifactFileTooLargeException
import run.ostrom.server.artifacts.domain.uploads.ArtifactSecretDetectedException
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadNotFoundException
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadsBusyException
import run.ostrom.server.artifacts.domain.uploads.ChunkReceipt
import run.ostrom.server.artifacts.domain.uploads.UploadArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.uploads.UploadBlobState
import run.ostrom.server.artifacts.domain.uploads.UploadLimits
import run.ostrom.server.artifacts.domain.uploads.UploadStores
import run.ostrom.server.artifacts.fakes.InMemoryArtifacts
import run.ostrom.server.artifacts.fakes.InMemoryBucket
import run.ostrom.server.artifacts.fakes.InMemoryStorage
import run.ostrom.server.artifacts.fakes.InMemoryUploads
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.TestClock
import run.ostrom.server.artifacts.fakes.active
import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.fakes.aliceAgent
import run.ostrom.server.artifacts.fakes.bob
import run.ostrom.server.artifacts.fakes.carol
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** TC-ART-26/27: the files of a version are announced, then sent in chunks that wait in the bucket, never in PG. */
class UploadArtifactUseCaseTest {
    private val artifacts = InMemoryArtifacts()
    private val uploads = InMemoryUploads(artifacts)
    private val storage = InMemoryStorage().active(ORG_A)
    private val bucket = InMemoryBucket()
    private val contents = BucketArtifactContents(storage, bucket)
    private val digest = Sha256ContentDigest()
    private val clock = TestClock()
    private val events = mutableListOf<String>()
    private val recorder = ArtifactEvents { caller, event, _, details ->
        events += "${caller.memberId} $event ${details["reason"]}"
    }
    private val chunks = BucketUploadChunks(storage, bucket)
    private val use = UploadArtifactUseCaseImpl(
        artifacts,
        UploadStores(uploads, chunks),
        contents,
        digest,
        clock,
        recorder,
    )

    private suspend fun announced(bytes: ByteArray) = AnnouncedFile(digest.sha256(bytes), bytes.size.toLong())

    private suspend fun published(caller: ArtifactCaller, vararg files: ByteArray) =
        PublishArtifactUseCaseImpl(artifacts, contents, digest, clock, recorder).create(
            caller,
            "Report",
            "team",
            files.mapIndexed { i, bytes -> UploadedFile("f$i.html", bytes) },
        )

    // Sends a file whole, in its chunks, in the order given (in order by default).
    private suspend fun send(uploadId: Uuid, bytes: ByteArray, order: List<Int>? = null): List<ChunkReceipt> {
        val sha = digest.sha256(bytes)
        val total = UploadLimits.chunks(bytes.size.toLong())
        return (order ?: (0 until total).toList()).map { index ->
            val from = index * UploadLimits.CHUNK_BYTES
            val to = minOf(bytes.size, from + UploadLimits.CHUNK_BYTES)
            use.chunk(alice, uploadId, sha, index, bytes.copyOfRange(from, to))
        }
    }

    @Test
    fun theLimitsOfAChunkFollowTheFileSize() {
        assertEquals(1, UploadLimits.chunks(0))
        assertEquals(1, UploadLimits.chunks(UploadLimits.CHUNK_BYTES.toLong()))
        assertEquals(2, UploadLimits.chunks(UploadLimits.CHUNK_BYTES + 1L))
        assertEquals(UploadLimits.CHUNK_BYTES.toLong(), UploadLimits.chunkSize(UploadLimits.CHUNK_BYTES + 1L, 0))
        assertEquals(1L, UploadLimits.chunkSize(UploadLimits.CHUNK_BYTES + 1L, 1))
        assertEquals(UploadBlobState.WAITING, UploadBlobState.parse("unknown"))
        assertEquals(UploadBlobState.STORED, UploadBlobState.parse("stored"))
    }

    @Test
    fun aNewArtifactIsADraftNobodyReadsUntilItsFirstVersion() = runTest {
        val page = "<h1>Draft</h1>".encodeToByteArray()
        val upload = use.open(alice, null, listOf(announced(page), announced(page)))
        assertEquals(listOf(digest.sha256(page)), upload.missing)
        assertEquals(clock.now + UploadLimits.LIFETIME, upload.expiresAt)
        val draft = artifacts.artifacts.getValue(upload.artifactId)
        assertTrue(draft.draft)
        assertEquals(alice.memberId to ArtifactAccess.PERSONAL, draft.ownerId to draft.access)
        val read = ReadArtifactsUseCaseImpl(artifacts, contents)
        assertFailsWith<ArtifactNotFoundException> { read.get(alice, draft.id) }
        assertEquals(emptyList(), read.list(alice, null, null))
        // Its owner may still send another upload to it, or delete it; nobody else reaches it.
        use.open(alice, draft.id, listOf(announced(page)))
        assertFailsWith<ArtifactNotFoundException> { use.open(bob, draft.id, listOf(announced(page))) }
        ManageArtifactUseCaseImpl(artifacts, contents, clock, recorder).delete(alice, draft.id)
        assertNull(artifacts.artifacts[draft.id])
    }

    @Test
    fun anUploadToAnArtifactAsksOnlyForWhatItsFolderLacks() = runTest {
        val kept = "<h1>Kept</h1>".encodeToByteArray()
        val changed = "<h1>New</h1>".encodeToByteArray()
        val artifact = published(alice, kept)
        val upload = use.open(alice, artifact.id, listOf(announced(kept), announced(changed)))
        assertEquals(artifact.id, upload.artifactId)
        assertEquals(listOf(digest.sha256(changed)), upload.missing)
        // A file the folder holds is not one to send.
        assertFailsWith<ArtifactUploadNotFoundException> {
            use.chunk(alice, upload.id, digest.sha256(kept), 0, kept)
        }
        // The owner alone publishes to it, even when the team reads it.
        assertFailsWith<ArtifactNotWritableException> { use.open(bob, artifact.id, listOf(announced(changed))) }
        assertFailsWith<ArtifactNotFoundException> { use.open(alice, Uuid.random(), listOf(announced(changed))) }
    }

    @Test
    fun chunksInAnyOrderAssembleTheFileOnceInTheArtifactsFolder() = runTest {
        val file = ByteArray(2 * UploadLimits.CHUNK_BYTES + 5) { (it % 7).toByte() }
        val sha = digest.sha256(file)
        val upload = use.open(alice, null, listOf(AnnouncedFile(sha, file.size.toLong())))
        val receipts = send(upload.id, file, listOf(2, 0, 0, 1))
        val expected = listOf(
            ChunkReceipt(false, 1, 3),
            ChunkReceipt(false, 2, 3),
            ChunkReceipt(false, 2, 3),
            ChunkReceipt(true, 3, 3),
        )
        assertEquals(expected, receipts)
        val blob = ArtifactKeys.blob("", ORG_A.toString(), upload.artifactId.toString(), sha)
        assertTrue(bucket.objects.getValue(blob).contentEquals(file))
        // The chunks are gone from the bucket; the folder's file is recorded with its size.
        assertEquals(setOf(blob), bucket.objects.keys)
        assertEquals(file.size.toLong(), artifacts.blobs[upload.artifactId to sha])
        assertEquals(UploadBlobState.STORED, uploads.state(upload.id, sha))
        // A chunk sent again once the file is stored only says so.
        assertEquals(ChunkReceipt(true, 3, 3), send(upload.id, file, listOf(1)).single())
    }

    @Test
    fun anEmptyFileIsOneEmptyChunk() = runTest {
        val upload = use.open(alice, null, listOf(announced(ByteArray(0))))
        assertEquals(listOf(ChunkReceipt(true, 1, 1)), send(upload.id, ByteArray(0)))
    }

    @Test
    fun aChunkMustBeTheSizeItsIndexHolds() = runTest {
        val file = ByteArray(UploadLimits.CHUNK_BYTES + 3)
        val sha = digest.sha256(file)
        val upload = use.open(alice, null, listOf(AnnouncedFile(sha, file.size.toLong())))
        for ((index, size) in listOf(-1 to 3, 2 to 3, 0 to 3, 1 to 4)) {
            assertFailsWith<InvalidArtifactRequestException> {
                use.chunk(alice, upload.id, sha, index, ByteArray(size))
            }
        }
        assertEquals(emptyMap(), uploads.chunks)
    }

    @Test
    fun onlyTheCredentialThatOpenedAnUploadSendsToItWhileItIsOpen() = runTest {
        val page = "<p>x</p>".encodeToByteArray()
        val sha = digest.sha256(page)
        val upload = use.open(alice, null, listOf(announced(page)))
        for (caller in listOf(aliceAgent, bob)) {
            assertFailsWith<ArtifactUploadNotFoundException> { use.chunk(caller, upload.id, sha, 0, page) }
        }
        assertFailsWith<ArtifactUploadNotFoundException> { use.chunk(alice, Uuid.random(), sha, 0, page) }
        assertFailsWith<ArtifactUploadNotFoundException> { use.chunk(alice, upload.id, "0".repeat(64), 0, page) }
        clock.now += UploadLimits.LIFETIME
        assertFailsWith<ArtifactUploadNotFoundException> { use.chunk(alice, upload.id, sha, 0, page) }
    }

    @Test
    fun aFileThatDoesNotMatchItsAnnouncementIsSentAgain() = runTest {
        val page = "<p>abc</p>".encodeToByteArray()
        val sha = digest.sha256(page)
        val upload = use.open(alice, null, listOf(announced(page)))
        // Another content of the same size, a chunk the bucket lost, a chunk read back longer.
        val faults = listOf<suspend () -> Unit>(
            { use.chunk(alice, upload.id, sha, 0, "<p>abd</p>".encodeToByteArray()) },
            {
                bucket.forget = true
                use.chunk(alice, upload.id, sha, 0, page)
            },
            {
                bucket.forget = false
                bucket.corrupt = true
                use.chunk(alice, upload.id, sha, 0, page)
            },
        )
        for (fault in faults) {
            assertFailsWith<ArtifactBlobMismatchException> { fault() }
            assertEquals(UploadBlobState.WAITING, uploads.state(upload.id, sha))
            assertNull(uploads.chunks[upload.id to sha])
        }
        bucket.corrupt = false
        assertEquals(ChunkReceipt(true, 1, 1), use.chunk(alice, upload.id, sha, 0, page))
    }

    @Test
    fun aBucketThatFailsOrACancellationLeavesTheFileToSendAgain() = runTest {
        val page = "<p>abc</p>".encodeToByteArray()
        val sha = digest.sha256(page)
        val upload = use.open(alice, null, listOf(announced(page)))
        bucket.beforeEach = { if (it == "get") throw ObjectStoreException(503, "SlowDown") }
        assertFailsWith<ArtifactStorageUnavailableException> { use.chunk(alice, upload.id, sha, 0, page) }
        assertEquals(UploadBlobState.WAITING, uploads.state(upload.id, sha))
        bucket.beforeEach = { if (it == "get") throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { use.chunk(alice, upload.id, sha, 0, page) }
        assertEquals(UploadBlobState.WAITING, uploads.state(upload.id, sha))
        // Another request assembles it: this one only counts its chunk.
        bucket.beforeEach = {}
        uploads.move(upload.id, sha, UploadBlobState.WAITING, UploadBlobState.ASSEMBLING)
        assertEquals(ChunkReceipt(false, 1, 1), use.chunk(alice, upload.id, sha, 0, page))
    }

    @Test
    fun aSecretEndsTheUploadAndTheDraftNoOtherUploadUses() = runTest {
        val secret = "<p>AKIAABCDEFGHIJKLMNOP</p>".encodeToByteArray()
        val sha = digest.sha256(secret)
        val upload = use.open(alice, null, listOf(announced(secret)))
        assertFailsWith<ArtifactSecretDetectedException> { use.chunk(alice, upload.id, sha, 0, secret) }
        assertNull(uploads.uploads[upload.id])
        assertNull(artifacts.artifacts[upload.artifactId])
        assertEquals(listOf("${alice.memberId} publish_refused secret"), events)
        assertTrue(bucket.objects.isEmpty())
        // An artifact with versions stays, and so does a draft another upload still uses.
        val artifact = published(alice, "<p>ok</p>".encodeToByteArray())
        val toArtifact = use.open(alice, artifact.id, listOf(announced(secret)))
        assertFailsWith<ArtifactSecretDetectedException> { use.chunk(alice, toArtifact.id, sha, 0, secret) }
        val first = use.open(alice, null, listOf(announced(secret)))
        use.open(alice, first.artifactId, listOf(announced(secret)))
        assertFailsWith<ArtifactSecretDetectedException> { use.chunk(alice, first.id, sha, 0, secret) }
        assertTrue(artifacts.artifacts.keys.containsAll(listOf(artifact.id, first.artifactId)))
    }

    @Test
    fun aBinaryFileOverItsBoundEndsTheUploadButTextAndSmallBinariesPass() = runTest {
        val binary = ByteArray(UploadLimits.MAX_BINARY_BYTES.toInt() + 1)
        val notUtf8 = byteArrayOf(-1, -2, 65)
        val text = ByteArray(UploadLimits.MAX_BINARY_BYTES.toInt() + 1) { 'a'.code.toByte() }
        val upload = use.open(alice, null, listOf(announced(notUtf8), announced(text), announced(binary)))
        assertEquals(ChunkReceipt(true, 1, 1), send(upload.id, notUtf8).single())
        assertTrue(send(upload.id, text).last().complete)
        assertFailsWith<ArtifactFileTooLargeException> { send(upload.id, binary) }
        assertEquals(listOf("${alice.memberId} publish_refused file_too_large"), events)
        assertNull(uploads.uploads[upload.id])
    }

    @Test
    fun anAnnouncementIsCheckedBeforeAnythingIsRecorded() = runTest {
        val ok = AnnouncedFile("a".repeat(64), 1)
        val refused = listOf(
            emptyList(),
            List(UploadLimits.MAX_FILES + 1) { AnnouncedFile(it.toString().padStart(64, '0'), 1) },
            listOf(ok.copy(sha256 = "A".repeat(64))),
            listOf(ok.copy(size = -1)),
            listOf(ok, ok.copy(size = 2)),
        )
        for (files in refused) {
            assertFailsWith<InvalidArtifactRequestException> { use.open(alice, null, files) }
        }
        assertFailsWith<ArtifactFileTooLargeException> {
            use.open(alice, null, listOf(ok.copy(size = UploadLimits.MAX_TEXT_BYTES + 1)))
        }
        assertTrue(uploads.uploads.isEmpty())
        assertTrue(artifacts.artifacts.isEmpty())
    }

    @Test
    fun aMemberHoldsBoundedUploadsAndDrafts() = runTest {
        val file = AnnouncedFile("b".repeat(64), 1)
        repeat(UploadLimits.OPEN_UPLOADS) { use.open(alice, null, listOf(file)) }
        assertFailsWith<ArtifactUploadsBusyException> { use.open(alice, null, listOf(file)) }
        // Bob's are his own.
        use.open(bob, null, listOf(file))
        // The uploads close after an hour; their drafts stay, and count.
        clock.now += UploadLimits.LIFETIME + 1.minutes
        repeat(UploadLimits.DRAFTS - UploadLimits.OPEN_UPLOADS) { use.open(alice, null, listOf(file)) }
        assertFailsWith<ArtifactUploadsBusyException> { use.open(alice, null, listOf(file)) }
        val draft = artifacts.artifacts.values.first { it.ownerId == alice.memberId }
        assertEquals(draft.id, use.open(alice, draft.id, listOf(file)).artifactId)
    }

    @Test
    fun nothingIsUploadedWhileArtifactsAreInactive() = runTest {
        val file = AnnouncedFile("c".repeat(64), 1)
        assertFailsWith<ArtifactsInactiveException> { use.open(carol, null, listOf(file)) }
        assertFailsWith<ArtifactsInactiveException> {
            use.chunk(carol, Uuid.random(), file.sha256, 0, byteArrayOf(1))
        }
        assertFalse(ORG_B in storage.storage)
    }

    @Test
    fun droppingChunksIsBestEffortButNeverSwallowsACancellation() = runTest {
        val upload = Uuid.random()
        chunks.write(ORG_A, upload, "d", 0, byteArrayOf(1))
        // No bucket: nothing to drop.
        BucketUploadChunks(InMemoryStorage(), bucket).drop(ORG_A, upload, "d", 1)
        bucket.beforeEach = { throw ObjectStoreException(500, "InternalError") }
        chunks.drop(ORG_A, upload, "d", 1)
        bucket.beforeEach = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { chunks.drop(ORG_A, upload, "d", 1) }
        bucket.beforeEach = {}
        assertEquals(1, bucket.objects.size)
        chunks.drop(ORG_A, upload, "d", 1)
        assertTrue(bucket.objects.isEmpty())
    }
}
