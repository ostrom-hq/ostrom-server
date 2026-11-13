// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactLocations
import run.ostrom.server.artifacts.domain.ManageArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import run.ostrom.server.artifacts.domain.UploadedFile
import run.ostrom.server.artifacts.domain.ViewArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactKeys
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageUnavailableException
import run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException
import run.ostrom.server.artifacts.domain.storage.BucketArtifactContents
import run.ostrom.server.artifacts.domain.storage.InactiveReason
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.artifacts.fakes.CONTENT_ORIGIN
import run.ostrom.server.artifacts.fakes.InMemoryArtifacts
import run.ostrom.server.artifacts.fakes.InMemoryBucket
import run.ostrom.server.artifacts.fakes.InMemoryStorage
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.TestClock
import run.ostrom.server.artifacts.fakes.TestViewers
import run.ostrom.server.artifacts.fakes.VIEWER
import run.ostrom.server.artifacts.fakes.active
import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.fakes.bob
import run.ostrom.server.artifacts.infrastructure.SealedViewTickets
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** TC-ART-3/4: the files live in the organization's bucket, never in PostgreSQL, and only while artifacts are on. */
class ArtifactContentsTest {
    private val storage = InMemoryStorage().active(ORG_A)
    private val bucket = InMemoryBucket()
    private val contents = BucketArtifactContents(storage, bucket)
    private val artifact = Uuid.random()
    private val digest = Sha256ContentDigest()

    private fun file(bytes: ByteArray, sha: String = "sha") =
        ArtifactFile("a.txt", sha, bytes.size.toLong(), "text/plain")

    private fun key(sha: String, org: Uuid = ORG_A) =
        ArtifactKeys.blob("", org.toString(), artifact.toString(), sha)

    @Test
    fun eachInactiveReasonIsTheRefusal() = runTest {
        contents.requireActive(ORG_A)
        assertTrue(contents.active(ORG_A))
        assertEquals(
            InactiveReason.STORAGE_MISSING,
            assertFailsWith<ArtifactsInactiveException> { contents.requireActive(ORG_B) }.reason,
        )
        assertFalse(contents.active(ORG_B))
        storage.storage[ORG_A] = storage.storage.getValue(ORG_A).copy(verifiedAt = null)
        assertEquals(
            InactiveReason.STORAGE_UNVERIFIED,
            assertFailsWith<ArtifactsInactiveException> { contents.requireActive(ORG_A) }.reason,
        )
    }

    @Test
    fun contentsAreWrittenUnderTheirDigestAndReadBackWhole() = runTest {
        contents.write(ORG_A, artifact, mapOf("sha" to byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), bucket.objects[key("sha")])
        assertContentEquals(byteArrayOf(1, 2, 3), contents.read(ORG_A, artifact, file(byteArrayOf(1, 2, 3))))
        // Another size than the row's, a missing object or a bucket that does not answer: 503, to retry.
        assertFailsWith<ArtifactStorageUnavailableException> { contents.read(ORG_A, artifact, file(byteArrayOf(1))) }
        assertFailsWith<ArtifactStorageUnavailableException> {
            contents.read(ORG_A, artifact, file(byteArrayOf(1), "other"))
        }
        bucket.beforeEach = { throw ObjectStoreException(500, "InternalError") }
        assertFailsWith<ArtifactStorageUnavailableException> { contents.read(ORG_A, artifact, file(byteArrayOf(1))) }
        assertFailsWith<ArtifactStorageUnavailableException> {
            contents.write(ORG_A, artifact, mapOf("sha" to byteArrayOf(1)))
        }
        bucket.beforeEach = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { contents.write(ORG_A, artifact, mapOf("sha" to byteArrayOf(1))) }
        // Without a bucket that opens, nothing is written.
        assertFailsWith<ArtifactsInactiveException> { contents.write(ORG_B, artifact, mapOf("sha" to byteArrayOf(1))) }
        assertFailsWith<ArtifactsInactiveException> { contents.read(ORG_B, artifact, file(byteArrayOf(1))) }
    }

    @Test
    fun anArtifactsFolderIsEmptiedPageByPage() = runTest {
        contents.write(ORG_A, artifact, (1..5).associate { "sha-$it" to byteArrayOf(it.toByte()) })
        bucket.objects["other"] = byteArrayOf(9)
        bucket.pageSize = 2
        assertTrue(contents.remove(ORG_A, artifact))
        assertEquals(setOf("other"), bucket.objects.keys)
        assertTrue(contents.remove(ORG_A, artifact))
        assertFalse(contents.remove(ORG_B, artifact))
        contents.write(ORG_A, artifact, (1..3).associate { "sha-$it" to byteArrayOf(it.toByte()) })
        bucket.refuseDelete = true
        assertFalse(contents.remove(ORG_A, artifact))
        bucket.beforeEach = { throw ObjectStoreException(null, "timeout") }
        assertFalse(contents.remove(ORG_A, artifact))
        bucket.beforeEach = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { contents.remove(ORG_A, artifact) }
    }


    @Test
    fun whileInactiveEveryActionButDeletionIsRefusedAndPagesAreNotFound() = runTest {
        val repository = InMemoryArtifacts()
        val clock = TestClock()
        val publish = PublishArtifactUseCaseImpl(repository, contents, digest, clock) { _, _, _, _ -> }
        val read = ReadArtifactsUseCaseImpl(repository, contents)
        val manage = ManageArtifactUseCaseImpl(repository, contents, clock) { _, _, _, _ -> }
        val view = ViewArtifactUseCaseImpl(
            repository, contents, SealedViewTickets(Ed25519InstanceSigner.generate()), TestViewers(),
            ArtifactLocations(VIEWER, CONTENT_ORIGIN), clock,
        )
        val page = listOf(UploadedFile("index.html", "<p>x</p>".encodeToByteArray()))
        val linked = publish.create(alice, "Public", "link", page)
        val ticket = view.ticket(alice, linked.id, null)
        val sealed = ticket.contentUrl.removePrefix("$CONTENT_ORIGIN/a/${linked.id}/").substringBefore('/')
        val slug = checkNotNull(linked.linkSlug)
        assertEquals("<p>x</p>", view.byLink(slug, "")?.bytes?.decodeToString())

        storage.settings[ORG_A] = storage.settings.getValue(ORG_A).copy(enabled = false)
        val refusals = listOf<suspend () -> Unit>(
            { publish.create(alice, "Again", "team", page) },
            { publish.addVersion(alice, linked.id, page) },
            { read.list(alice, null, null) },
            { read.get(alice, linked.id) },
            { read.versions(alice, linked.id) },
            { read.file(alice, linked.id, null, null) },
            { manage.update(alice, linked.id, "Renamed", null) },
            { manage.members(alice, linked.id) },
            { manage.share(alice, linked.id, bob.memberId, share = true) },
            { view.ticket(alice, linked.id, null) },
        )
        for (refused in refusals) {
            assertEquals(InactiveReason.DISABLED, assertFailsWith<ArtifactsInactiveException> { refused() }.reason)
        }
        assertNull(view.byLink(slug, ""))
        assertNull(view.byTicket(linked.id, sealed, ""))
        manage.delete(alice, linked.id)
        assertNull(repository.artifacts[linked.id])
        assertTrue(bucket.objects.isEmpty())
    }
}
