// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactConflictException
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactNotWritableException
import run.ostrom.server.artifacts.domain.ArtifactPersonRequiredException
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException
import run.ostrom.server.artifacts.domain.storage.BucketArtifactContents
import run.ostrom.server.artifacts.domain.storage.BucketUploadChunks
import run.ostrom.server.artifacts.domain.uploads.AnnouncedFile
import run.ostrom.server.artifacts.domain.uploads.ArtifactBlobMissingException
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadNotFoundException
import run.ostrom.server.artifacts.domain.uploads.CommitArtifactUploadUseCaseImpl
import run.ostrom.server.artifacts.domain.uploads.CommittedFile
import run.ostrom.server.artifacts.domain.uploads.UploadArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.uploads.UploadCommit
import run.ostrom.server.artifacts.domain.uploads.UploadStores
import run.ostrom.server.artifacts.fakes.InMemoryArtifacts
import run.ostrom.server.artifacts.fakes.InMemoryBucket
import run.ostrom.server.artifacts.fakes.InMemoryStorage
import run.ostrom.server.artifacts.fakes.InMemoryUploads
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.TestClock
import run.ostrom.server.artifacts.fakes.active
import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.fakes.aliceAgent
import run.ostrom.server.artifacts.fakes.bob
import run.ostrom.server.artifacts.fakes.carol
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** TC-ART-28: an upload is committed as the next version, from the live one, once (a replay answers the same). */
class CommitArtifactUploadUseCaseTest {
    private val artifacts = InMemoryArtifacts()
    private val uploads = InMemoryUploads(artifacts)
    private val storage = InMemoryStorage().active(ORG_A)
    private val bucket = InMemoryBucket()
    private val contents = BucketArtifactContents(storage, bucket)
    private val digest = Sha256ContentDigest()
    private val clock = TestClock()
    private val events = mutableListOf<String>()
    private val recorder = ArtifactEvents { caller, event, _, details ->
        events += "${caller.memberId} $event ${details["version"] ?: details["reason"]}"
    }
    private val upload = UploadArtifactUseCaseImpl(
        artifacts,
        UploadStores(uploads, BucketUploadChunks(storage, bucket)),
        contents,
        digest,
        clock,
        recorder,
    )
    private val use = CommitArtifactUploadUseCaseImpl(artifacts, uploads, contents, digest, clock, recorder)
    private val page = "<h1>Report</h1>".encodeToByteArray()
    private val style = "h1 { color: red }".encodeToByteArray()

    private suspend fun file(path: String, bytes: ByteArray) =
        CommittedFile(path, digest.sha256(bytes), bytes.size.toLong())

    // Opens an upload of [files] for [caller] and sends each whole: the upload's id and its artifact's.
    private suspend fun sent(caller: ArtifactCaller, artifactId: Uuid?, vararg files: ByteArray): Pair<Uuid, Uuid> {
        val opened = upload.open(caller, artifactId, files.map { AnnouncedFile(digest.sha256(it), it.size.toLong()) })
        for (bytes in files) {
            val sha = digest.sha256(bytes)
            if (sha in opened.missing) upload.chunk(caller, opened.id, sha, 0, bytes)
        }
        return opened.id to opened.artifactId
    }

    private suspend fun firstVersion(): Pair<Uuid, UploadCommit> {
        val (uploadId, draft) = sent(alice, null, page)
        val commit = UploadCommit(uploadId, listOf(file("index.html", page)), baseVersion = 0, title = "Report")
        use.commit(alice, draft, commit.copy(access = "team"))
        return draft to commit
    }

    @Test
    fun aDraftsFirstCommitPublishesItWithItsTitleAndAccess() = runTest {
        val (uploadId, draft) = sent(alice, null, page)
        val commit = UploadCommit(uploadId, listOf(file("index.html", page)), title = " Report ", access = "team")
        val committed = use.commit(alice, draft, commit)
        assertFalse(committed.replayed)
        assertEquals(1 to 0, committed.version.number to committed.version.baseVersion)
        assertNotNull(committed.version.digest)
        val artifact = artifacts.artifacts.getValue(draft)
        assertEquals("Report" to ArtifactAccess.TEAM, artifact.title to artifact.access)
        assertEquals(1, artifact.latestVersion)
        assertNull(uploads.uploads[uploadId])
        assertEquals(listOf("${alice.memberId} created 1"), events)
        val served = ReadArtifactsUseCaseImpl(artifacts, contents).file(bob, draft, null, null)
        assertContentEquals(page, served.bytes)
    }

    @Test
    fun theSameCommitWithinTenMinutesIsAReplay() = runTest {
        val (draft, commit) = firstVersion()
        val again = use.commit(alice, draft, commit)
        assertTrue(again.replayed)
        assertEquals(1, again.version.number)
        // Another credential, another base, or past ten minutes: a new commit, whose upload is gone.
        assertFailsWith<ArtifactUploadNotFoundException> { use.commit(aliceAgent, draft, commit) }
        assertFailsWith<ArtifactUploadNotFoundException> { use.commit(alice, draft, commit.copy(baseVersion = 1)) }
        clock.now += 11.minutes
        assertFailsWith<ArtifactUploadNotFoundException> { use.commit(alice, draft, commit) }
    }

    @Test
    fun aCommitStartsFromTheLiveVersionAndUsesWhatTheFolderHolds() = runTest {
        val (draft, _) = firstVersion()
        // The page is in the folder already: only the style is sent.
        val (uploadId, _) = sent(alice, draft, page, style)
        val files = listOf(file("index.html", page), file("style.css", style))
        assertFailsWith<ArtifactConflictException> {
            use.commit(alice, draft, UploadCommit(uploadId, files, baseVersion = 0))
        }
        artifacts.loseNextRace = true
        assertFailsWith<ArtifactConflictException> { use.commit(alice, draft, UploadCommit(uploadId, files)) }
        val second = use.commit(alice, draft, UploadCommit(uploadId, files, entry = "index.html", title = "Renamed"))
        assertEquals(listOf("index.html", "style.css"), second.version.files.map { it.path })
        assertEquals("Renamed" to 2, artifacts.artifacts.getValue(draft).let { it.title to it.latestVersion })
        assertEquals("${alice.memberId} version_published 2", events.last())
        // The same access is accepted; changing it is a person's, through the settings.
        val (third, _) = sent(alice, draft, page)
        val same = UploadCommit(third, listOf(file("index.html", page)), access = "team")
        assertFailsWith<InvalidArtifactRequestException> { use.commit(alice, draft, same.copy(access = "personal")) }
        assertEquals(3, use.commit(alice, draft, same).version.number)
    }

    @Test
    fun anArtifactPublishedInlineTakesAnUploadAsItsNextVersion() = runTest {
        val id = Uuid.random()
        val at = clock.now
        val files = listOf(ArtifactFile("index.html", "a".repeat(64), 1, "text/html"))
        val inline = ArtifactVersion(id, 1, "index.html", files, at, alice.memberId)
        artifacts.create(Artifact(id, ORG_A, alice.memberId, "Inline", ArtifactAccess.TEAM, 1, null, at, at), inline)
        val (uploadId, _) = sent(alice, id, page)
        val next = use.commit(alice, id, UploadCommit(uploadId, listOf(file("index.html", page))))
        assertEquals(2 to 1, next.version.number to next.version.baseVersion)
    }

    @Test
    fun everyFileMustBeInTheFolderAtItsSize() = runTest {
        val (uploadId, draft) = sent(alice, null, page)
        for (missing in listOf(file("a.html", style), file("index.html", page).copy(size = 1))) {
            assertFailsWith<ArtifactBlobMissingException> {
                use.commit(alice, draft, UploadCommit(uploadId, listOf(missing), title = "T"))
            }
        }
    }

    @Test
    fun theUploadMustBeTheCallersForThisArtifactAndOpen() = runTest {
        val (uploadId, draft) = sent(alice, null, page)
        val (_, other) = sent(alice, null, style)
        val commit = UploadCommit(uploadId, listOf(file("index.html", page)), title = "T")
        assertFailsWith<ArtifactUploadNotFoundException> { use.commit(alice, other, commit) }
        assertFailsWith<ArtifactUploadNotFoundException> {
            use.commit(alice, draft, commit.copy(uploadId = Uuid.random()))
        }
        assertFailsWith<ArtifactUploadNotFoundException> { use.commit(aliceAgent, draft, commit) }
        clock.now += 120.minutes
        assertFailsWith<ArtifactUploadNotFoundException> { use.commit(alice, draft, commit) }
    }

    @Test
    fun theFilesTheEntryTheTitleAndTheAccessAreChecked() = runTest {
        val (uploadId, draft) = sent(alice, null, page)
        val ok = UploadCommit(uploadId, listOf(file("index.html", page)), title = "T")
        val refused = listOf(
            ok.copy(files = emptyList()),
            ok.copy(files = List(201) { CommittedFile("f$it.html", "a".repeat(64), 1) }),
            ok.copy(files = listOf(file("../x.html", page))),
            ok.copy(files = listOf(file("index.html", page), file("index.html", style))),
            ok.copy(entry = "other.html"),
            ok.copy(files = listOf(file("index.html", page), file("data.json", style)), entry = "data.json"),
            ok.copy(title = null),
            ok.copy(access = "public"),
        )
        for (commit in refused) {
            assertFailsWith<InvalidArtifactRequestException> { use.commit(alice, draft, commit) }
        }
        // Her agent opens and commits its own upload, never as `link`.
        val (agentUpload, agentDraft) = sent(aliceAgent, null, page)
        assertFailsWith<ArtifactPersonRequiredException> {
            use.commit(aliceAgent, agentDraft, ok.copy(uploadId = agentUpload, access = "link"))
        }
        assertEquals(listOf("${alice.memberId} publish_refused person_required"), events)
        assertFailsWith<ArtifactNotWritableException> { firstVersion().let { use.commit(bob, it.first, it.second) } }
        val linked = use.commit(alice, draft, ok.copy(access = "link"))
        assertNotNull(artifacts.artifacts.getValue(draft).linkSlug)
        assertEquals("index.html", linked.version.entry)
    }

    @Test
    fun nothingIsCommittedWhileArtifactsAreInactive() = runTest {
        assertFailsWith<ArtifactsInactiveException> {
            use.commit(carol, Uuid.random(), UploadCommit(Uuid.random(), listOf(CommittedFile("a.html", "a", 1))))
        }
    }
}
