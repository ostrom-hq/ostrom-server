// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactOrigin
import run.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.UploadedFile
import run.ostrom.server.artifacts.domain.storage.BucketArtifactContents
import run.ostrom.server.artifacts.domain.storage.BucketUploadChunks
import run.ostrom.server.artifacts.domain.uploads.AnnouncedFile
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
import run.ostrom.server.artifacts.fakes.gateOf
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** TC-ART-39: where an agent's publication came from, read from its credential: the artifact's and each version's. */
class ArtifactOriginTest {
    private val artifacts = InMemoryArtifacts()
    private val uploads = InMemoryUploads(artifacts)
    private val storage = InMemoryStorage().active(ORG_A)
    private val bucket = InMemoryBucket()
    private val contents = BucketArtifactContents(storage, bucket)
    private val digest = Sha256ContentDigest()
    private val clock = TestClock()
    private val events = ArtifactEvents { _, _, _, _ -> }
    private val publish = PublishArtifactUseCaseImpl(artifacts, contents, digest, clock, events, gateOf(storage, clock))
    private val upload = UploadArtifactUseCaseImpl(
        artifacts,
        UploadStores(uploads, BucketUploadChunks(storage, bucket), gateOf(storage, clock)),
        contents,
        digest,
        clock,
        events,
    )
    private val commit =
        CommitArtifactUploadUseCaseImpl(artifacts, uploads, gateOf(storage, clock), digest, clock, events)

    private val cloud = ArtifactOrigin("launch-1", "session-1")
    private val machine = ArtifactOrigin("launch-2")
    private val cloudAgent = aliceAgent.copy(origin = cloud, credentialId = "cloud-token")
    private val machineAgent = aliceAgent.copy(origin = machine, credentialId = "machine-token")

    private fun page(text: String = "<h1>Report</h1>") = listOf(UploadedFile("index.html", text.encodeToByteArray()))

    @Test
    fun anAgentPublicationRecordsItsLaunchAndSession() = runTest {
        val created = publish.create(cloudAgent, "Report", "personal", page())
        assertEquals(cloud, created.origin)
        assertEquals(cloud, artifacts.artifacts.getValue(created.id).origin)
        assertEquals(cloud, artifacts.version(ORG_A, created.id, 1)?.origin)
    }

    @Test
    fun aMachineLaunchRecordsTheLaunchOnly() = runTest {
        val created = publish.create(machineAgent, "Report", "personal", page())
        assertEquals(ArtifactOrigin("launch-2", null), created.origin)
        assertNull(created.origin?.cloudSessionId)
        assertEquals(machine, artifacts.version(ORG_A, created.id, 1)?.origin)
    }

    @Test
    fun aPersonsPublicationHasNoOrigin() = runTest {
        val created = publish.create(alice, "Report", "personal", page())
        assertNull(created.origin)
        assertNull(artifacts.artifacts.getValue(created.id).origin)
        assertNull(artifacts.version(ORG_A, created.id, 1)?.origin)
    }

    @Test
    fun aLaterVersionKeepsTheArtifactsCreationOriginAndRecordsItsOwn() = runTest {
        val created = publish.create(cloudAgent, "Report", "personal", page())
        val byPerson = publish.addVersion(alice, created.id, page("<h1>Two</h1>"))
        assertNull(byPerson.origin)
        val byMachine = publish.addVersion(machineAgent, created.id, page("<h1>Three</h1>"))
        assertEquals(machine, byMachine.origin)
        assertEquals(cloud, artifacts.artifacts.getValue(created.id).origin, "The artifact's origin is set once")
        assertEquals(listOf(machine, null, cloud), artifacts.versions(ORG_A, created.id).map { it.origin })
    }

    @Test
    fun aPersonsArtifactStaysWithoutOriginWhenAnAgentAddsAVersion() = runTest {
        val created = publish.create(alice, "Report", "personal", page())
        val version = publish.addVersion(cloudAgent, created.id, page("<h1>Two</h1>"))
        assertEquals(cloud, version.origin)
        assertNull(artifacts.artifacts.getValue(created.id).origin)
    }

    // Uploads a page for [caller] and commits it as the next version of [artifactId] (a new draft for null).
    private suspend fun committed(caller: ArtifactCaller, artifactId: Uuid?, text: String, base: Int): Uuid {
        val bytes = text.encodeToByteArray()
        val sha = digest.sha256(bytes)
        val opened = upload.open(caller, artifactId, listOf(AnnouncedFile(sha, bytes.size.toLong())))
        upload.chunk(caller, opened.id, sha, 0, bytes)
        val files = listOf(CommittedFile("index.html", sha, bytes.size.toLong()))
        commit.commit(caller, opened.artifactId, UploadCommit(opened.id, files, baseVersion = base, title = "Report"))
        return opened.artifactId
    }

    @Test
    fun anUploadCommittedByAnAgentRecordsTheOriginOnTheDraftAndTheVersion() = runTest {
        val id = committed(cloudAgent, null, "<h1>One</h1>", base = 0)
        assertEquals(cloud, artifacts.artifacts.getValue(id).origin)
        assertEquals(cloud, artifacts.version(ORG_A, id, 1)?.origin)
    }

    @Test
    fun aPersonsLaterCommitKeepsTheArtifactsOriginAndHasNoVersionOrigin() = runTest {
        val id = committed(cloudAgent, null, "<h1>One</h1>", base = 0)
        committed(alice, id, "<h1>Two</h1>", base = 1)
        assertEquals(cloud, artifacts.artifacts.getValue(id).origin)
        assertNull(artifacts.version(ORG_A, id, 2)?.origin)
        committed(machineAgent, id, "<h1>Three</h1>", base = 2)
        assertEquals(cloud, artifacts.artifacts.getValue(id).origin)
        assertEquals(machine, artifacts.version(ORG_A, id, 3)?.origin)
    }

    @Test
    fun aPersonsUploadHasNoOrigin() = runTest {
        val id = committed(alice, null, "<h1>One</h1>", base = 0)
        assertNull(artifacts.artifacts.getValue(id).origin)
        assertNull(artifacts.version(ORG_A, id, 1)?.origin)
    }
}
