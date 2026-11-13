// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.uploads.ArtifactUpload
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadsRepository
import run.ostrom.server.artifacts.domain.uploads.UploadBlob
import run.ostrom.server.artifacts.domain.uploads.UploadBlobState
import run.ostrom.server.artifacts.domain.uploads.UploadLimits
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.activateArtifacts
import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import run.ostrom.server.artifacts.infrastructure.UploadQueries
import run.ostrom.server.artifacts.infrastructure.fetchInOrg
import run.ostrom.server.artifacts.infrastructure.inTenant
import run.ostrom.server.artifacts.presentation.ArtifactConflictResponse
import run.ostrom.server.artifacts.presentation.ArtifactFileRefDto
import run.ostrom.server.artifacts.presentation.ArtifactUploadChunkResponse
import run.ostrom.server.artifacts.presentation.ArtifactUploadResponse
import run.ostrom.server.artifacts.presentation.ArtifactVersionResponse
import run.ostrom.server.artifacts.presentation.CreateArtifactUploadRequest
import run.ostrom.server.artifacts.presentation.FileDto
import run.ostrom.server.artifacts.presentation.PublishVersionRequest
import run.ostrom.server.kernel.http.ErrorDetail
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: the uploads' SQL, their limits under a lock, their isolation, routes and query plans. */
class ArtifactUploadsPostgresTest {
    private val t0 = Instant.parse("2026-11-13T10:00:00Z")
    private val sha = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun draft(org: Uuid = ORG_A) =
        Artifact(Uuid.random(), org, ALICE.toString(), "", ArtifactAccess.PERSONAL, 0, null, t0, t0)

    private fun upload(artifactId: Uuid, org: Uuid = ORG_A, at: Instant = t0) = ArtifactUpload(
        id = Uuid.random(),
        orgId = org,
        artifactId = artifactId,
        memberId = ALICE.toString(),
        credentialId = "alice-session",
        files = listOf(UploadBlob(sha, 3, UploadBlobState.WAITING), UploadBlob(other, 0, UploadBlobState.HELD)),
        createdAt = at,
        expiresAt = at + UploadLimits.LIFETIME,
    )

    @Test
    fun anUploadCountsItsChunksOnceAndOneRequestAssemblesIt() = serverTest(listOf(ArtifactsModule)) { server ->
        val uploads = server.koin.get<ArtifactUploadsRepository>()
        val artifacts = server.koin.get<ArtifactsRepository>()
        val draft = draft()
        val first = upload(draft.id)
        assertTrue(uploads.open(first, draft, t0))
        assertEquals(first, uploads.get(ORG_A, first.id))
        assertNull(uploads.get(ORG_B, first.id))
        assertNull(uploads.get(ORG_A, Uuid.random()))
        // The draft is an artifact nobody lists.
        assertEquals(0, artifacts.get(ORG_A, draft.id)?.latestVersion)
        assertEquals(emptyList(), artifacts.listReadable(alice, null, 10))

        assertEquals(listOf(1, 1, 2), listOf(0, 0, 1).map { uploads.receive(ORG_A, first.id, sha, it) })
        assertTrue(uploads.claim(ORG_A, first.id, sha))
        assertFalse(uploads.claim(ORG_A, first.id, sha))
        uploads.retry(ORG_A, first.id, sha)
        assertEquals(UploadBlobState.WAITING, uploads.get(ORG_A, first.id)?.files?.first()?.state)
        assertEquals(1, uploads.receive(ORG_A, first.id, sha, 1))
        assertTrue(uploads.claim(ORG_A, first.id, sha))
        uploads.stored(ORG_A, first, sha, 3)
        assertEquals(UploadBlobState.STORED, uploads.get(ORG_A, first.id)?.files?.first()?.state)
        assertEquals(setOf(sha), artifacts.blobDigests(ORG_A, draft.id))
        // A stored file is not sent again.
        uploads.retry(ORG_A, first.id, sha)
        assertEquals(UploadBlobState.STORED, uploads.get(ORG_A, first.id)?.files?.first()?.state)

        // The draft goes with the last upload that uses it, with its rows.
        val second = upload(draft.id)
        assertTrue(uploads.open(second, null, t0))
        assertFalse(uploads.end(ORG_A, first))
        assertNull(uploads.get(ORG_A, first.id))
        assertTrue(uploads.end(ORG_A, second))
        assertNull(artifacts.get(ORG_A, draft.id))
        assertEquals(emptySet(), artifacts.blobDigests(ORG_A, draft.id))
    }

    @Test
    fun aMemberHoldsBoundedUploadsAndDraftsInTheirOrganizationOnly() = serverTest(listOf(ArtifactsModule)) { server ->
        val uploads = server.koin.get<ArtifactUploadsRepository>()
        val draft = draft()
        assertTrue(uploads.open(upload(draft.id), draft, t0))
        repeat(UploadLimits.OPEN_UPLOADS - 1) { assertTrue(uploads.open(upload(draft.id), null, t0)) }
        assertFalse(uploads.open(upload(draft.id), null, t0))
        // The same member id in another organization counts apart, and its rows stay hidden from A.
        val elsewhere = draft(ORG_B)
        assertTrue(uploads.open(upload(elsewhere.id, ORG_B), elsewhere, t0))
        // Once the uploads closed, the drafts stay and count.
        repeat(UploadLimits.DRAFTS - 1) {
            val at = t0 + (2 * it + 2).hours
            val next = draft().copy(createdAt = at)
            assertTrue(uploads.open(upload(next.id, at = at), next, at))
        }
        val later = t0 + (2 * UploadLimits.DRAFTS + 2).hours
        val refused = draft()
        assertFalse(uploads.open(upload(refused.id, at = later), refused, later))
        suspend fun count(org: Uuid, table: String) = server.driver.inTenant(org) {
            fetchAll(Statement.create("SELECT COUNT(*) AS n FROM $table")).getOrThrow().first().get("n").asLong()
        }
        assertEquals(1L, count(ORG_B, "artifacts_uploads"))
        assertEquals(2L, count(ORG_B, "artifacts_upload_blobs"))
        assertEquals(UploadLimits.DRAFTS.toLong(), count(ORG_A, "artifacts_artifacts"))
    }

    @Test
    fun theRoutesOpenAnUploadAndTakeItsChunks() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        server.koin.activateArtifacts()
        val client = server.client
        val page = "<p>hi</p>".encodeToByteArray()
        val hash = Sha256ContentDigest().sha256(page)
        val opened = client.post("/api/v1/artifacts/uploads") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateArtifactUploadRequest(files = listOf(ArtifactFileRefDto(hash, page.size.toLong()))))
        }
        assertEquals(HttpStatusCode.Created, opened.status)
        val upload = opened.body<ArtifactUploadResponse>()
        assertEquals(listOf(hash) to UploadLimits.CHUNK_BYTES, upload.missing to upload.chunkBytes)

        suspend fun chunk(token: String?, id: String, n: String) =
            client.put("/api/v1/artifacts/uploads/$id/blobs/$hash/$n") {
                token?.let { bearerAuth(it) }
                contentType(ContentType.Application.OctetStream)
                setBody(page)
            }
        assertEquals(HttpStatusCode.Unauthorized, chunk(null, upload.uploadId, "0").status)
        assertEquals(HttpStatusCode.Forbidden, chunk("reader", upload.uploadId, "0").status)
        assertEquals(HttpStatusCode.BadRequest, chunk("alice", upload.uploadId, "x").status)
        assertEquals(HttpStatusCode.NotFound, chunk("alice", "nope", "0").status)
        val sent = chunk("alice", upload.uploadId, "0")
        assertEquals(HttpStatusCode.OK, sent.status)
        assertEquals(ArtifactUploadChunkResponse(true, 1, 1), sent.body<ArtifactUploadChunkResponse>())

        val toUnknown = client.post("/api/v1/artifacts/uploads") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateArtifactUploadRequest("not-an-id", listOf(ArtifactFileRefDto(hash, 1))))
        }
        assertEquals(HttpStatusCode.NotFound, toUnknown.status)
        val tooLarge = client.post("/api/v1/artifacts/uploads") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            val huge = ArtifactFileRefDto(hash, UploadLimits.MAX_TEXT_BYTES + 1)
            setBody(CreateArtifactUploadRequest(files = listOf(huge)))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
        assertTrue("artifact_file_too_large" in tooLarge.body<String>())
    }

    @Test
    fun anUploadIsCommittedOnceThenReplayed() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        server.koin.activateArtifacts()
        val client = server.client
        val page = "<p>v1</p>".encodeToByteArray()
        val hash = Sha256ContentDigest().sha256(page)
        val upload = client.post("/api/v1/artifacts/uploads") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateArtifactUploadRequest(files = listOf(ArtifactFileRefDto(hash, page.size.toLong()))))
        }.body<ArtifactUploadResponse>()
        client.put("/api/v1/artifacts/uploads/${upload.uploadId}/blobs/$hash/0") {
            bearerAuth("alice")
            contentType(ContentType.Application.OctetStream)
            setBody(page)
        }
        val versions = "/api/v1/artifacts/${upload.artifactId}/versions"
        suspend fun commit(request: PublishVersionRequest) = client.post(versions) {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val files = listOf(FileDto("index.html", sha256 = hash, size = page.size.toLong()))
        val request = PublishVersionRequest(files, upload.uploadId, baseVersion = 0, title = "Uploaded")
        assertEquals(HttpStatusCode.BadRequest, commit(request.copy(files = listOf(FileDto("index.html")))).status)
        assertEquals(HttpStatusCode.BadRequest, commit(request.copy(files = listOf(files[0].copy(size = null)))).status)
        assertEquals(HttpStatusCode.NotFound, commit(request.copy(uploadId = "nope")).status)
        // Inline, a file needs its content.
        assertEquals(HttpStatusCode.BadRequest, commit(PublishVersionRequest(listOf(FileDto("index.html")))).status)
        // A draft has no live version to give with its conflict.
        val draftConflict = commit(request.copy(baseVersion = 1))
        assertEquals(HttpStatusCode.Conflict, draftConflict.status)
        assertEquals(
            ArtifactConflictResponse(ErrorDetail("artifact_conflict", ARTIFACT_CONFLICT_MESSAGE)),
            draftConflict.body<ArtifactConflictResponse>(),
        )
        val first = commit(request)
        assertEquals(HttpStatusCode.Created, first.status)
        val made = first.body<ArtifactVersionResponse>()
        assertEquals(Triple(1, 0, false), Triple(made.number, made.baseVersion, made.replayed))
        val again = commit(request)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(made.copy(replayed = true), again.body<ArtifactVersionResponse>())
        // A commit from a stale base gets the live version back, to merge (TeamCode's `{error, current}`).
        val next = client.post("/api/v1/artifacts/uploads") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            val held = listOf(ArtifactFileRefDto(hash, page.size.toLong()))
            setBody(CreateArtifactUploadRequest(upload.artifactId, held))
        }.body<ArtifactUploadResponse>()
        val moved = listOf(files[0].copy(path = "other.html"))
        val stale = commit(PublishVersionRequest(moved, next.uploadId, baseVersion = 0))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(made.copy(replayed = null), stale.body<ArtifactConflictResponse>().current)
    }

    @Test
    fun theUploadsRequestPathReadsUseTheirIndexes() = serverTest(listOf(ArtifactsModule)) { server ->
        val fixtures = server.database.superuser
        // 20 000 artifacts and uploads of 2 000 other organizations, a tenth of them drafts.
        fixtures.execute(
            "INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, created_at, " +
                "updated_at) SELECT CAST(md5('a' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), 'm' || " +
                "(i % 7), 'T', 'team', CASE WHEN i % 10 = 0 THEN 0 ELSE 1 END, now(), now() " +
                "FROM generate_series(0, 19999) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO artifacts_uploads (id, org_id, artifact_id, member_id, created_at, expires_at) " +
                "SELECT CAST(md5('u' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), CAST(md5('a' || i) " +
                "AS uuid), 'm' || (i % 7), now(), now() + interval '1 hour' FROM generate_series(0, 19999) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO artifacts_upload_blobs (upload_id, org_id, sha256, size, state, position) " +
                "SELECT CAST(md5('u' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), 'x', 1, 'waiting', 0 " +
                "FROM generate_series(0, 19999) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO artifacts_upload_chunks (upload_id, org_id, sha256, chunk_index) " +
                "SELECT CAST(md5('u' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), 'x', 0 " +
                "FROM generate_series(0, 19999) AS i",
        ).getOrThrow()
        val uploads = server.koin.get<ArtifactUploadsRepository>()
        val draft = draft()
        val mine = upload(draft.id)
        uploads.open(mine, draft, t0)
        uploads.receive(ORG_A, mine.id, sha, 0)
        for (table in listOf("artifacts_artifacts", "artifacts_uploads", "artifacts_upload_chunks")) {
            fixtures.execute("VACUUM ANALYZE $table").getOrThrow()
        }
        val driver = server.driver
        val plans = mapOf(
            "artifacts_uploads" to driver.explain(UploadQueries.OPEN_UPLOADS) {
                bind("member", ALICE.toString()).bind("now", t0)
            },
            "artifacts_artifacts" to driver.explain(UploadQueries.DRAFTS) { bind("member", ALICE.toString()) },
            "artifacts_upload_chunks" to driver.explain(UploadQueries.RECEIVED) {
                bind("id", mine.id).bind("sha", sha)
            },
        )
        for ((table, plan) in plans) {
            assertFalse("Seq Scan on $table" in plan, "The read scans $table:\n$plan")
        }
        assertNotNull(uploads.get(ORG_A, mine.id))
    }

    /** The plan of [sql] in org A, as the tenant role reads it; `:org_id` is bound when it names it. */
    private suspend fun Driver.explain(sql: String, bind: Statement.() -> Statement): String {
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind()
        if (":org_id" in sql) statement.bind("org_id", ORG_A)
        return fetchInOrg(ORG_A, statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
    }

}

private const val ARTIFACT_CONFLICT_MESSAGE =
    "Another version of this artifact was published at the same time: publish again."
