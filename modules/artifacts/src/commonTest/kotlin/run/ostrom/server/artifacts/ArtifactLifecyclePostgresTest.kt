// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactLifecycleRepository
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.activateArtifacts
import run.ostrom.server.artifacts.fakes.asSuperuser
import run.ostrom.server.artifacts.fakes.superuserSql
import run.ostrom.server.artifacts.fakes.vacuumAnalyze
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.infrastructure.ArtifactsArtifacts
import run.ostrom.server.artifacts.infrastructure.ArtifactsBlobs
import run.ostrom.server.artifacts.infrastructure.ArtifactsUploadBlobs
import run.ostrom.server.artifacts.infrastructure.ArtifactsUploads
import run.ostrom.server.artifacts.infrastructure.ArtifactsVersions
import run.ostrom.server.artifacts.infrastructure.blobsMark
import run.ostrom.server.artifacts.infrastructure.markedBlob
import run.ostrom.server.artifacts.infrastructure.versionsPrune
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val UPLOADS = "/api/v1/artifacts/uploads"

/** TC-ART-29 against a real PostgreSQL: the versions kept, the contents marked, the expiry. */
class ArtifactLifecyclePostgresTest {
    private val upload = Uuid.random()
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    private fun version(artifact: Artifact, number: Int, vararg shas: String) = ArtifactVersion(
        artifact.id, number, "${shas.first()}.html",
        shas.map { ArtifactFile("$it.html", it, 10, "text/html") }, t0, artifact.ownerId,
    )

    private suspend fun TestServer.versions(id: Uuid) = koin.get<JdbcDatabase>().inOrg(ORG_A) {
        ArtifactsVersions.select(ArtifactsVersions.number).where { ArtifactsVersions.artifactId eq id }
            .orderBy(ArtifactsVersions.number).map { it[ArtifactsVersions.number] }
    }

    private suspend fun TestServer.marked(id: Uuid) = koin.get<JdbcDatabase>().inOrg(ORG_A) {
        ArtifactsBlobs.select(ArtifactsBlobs.sha256)
            .where { (ArtifactsBlobs.artifactId eq id) and ArtifactsBlobs.markedAt.isNotNull() }
            .map { it[ArtifactsBlobs.sha256] }.toSet()
    }

    private suspend fun TestServer.expiry(id: Uuid) = koin.get<JdbcDatabase>().inOrg(ORG_A) {
        ArtifactsArtifacts.select(ArtifactsArtifacts.expiresAt).where { ArtifactsArtifacts.id eq id }
            .single()[ArtifactsArtifacts.expiresAt]
    }

    private suspend fun TestServer.onlyArtifactId() = koin.get<JdbcDatabase>().inOrg(ORG_A) {
        ArtifactsArtifacts.select(ArtifactsArtifacts.id).single()[ArtifactsArtifacts.id]
    }

    @Test
    fun theOldVersionsArePrunedAndTheContentsNoneKeepsAreMarked() = serverTest(listOf(ArtifactsModule)) { server ->
        val artifacts = server.koin.get<ArtifactsRepository>()
        val lifecycle = server.koin.get<ArtifactLifecycleRepository>()
        val made = Artifact(Uuid.random(), ORG_A, ALICE.toString(), "T", ArtifactAccess.TEAM, 1, null, t0, t0)
        artifacts.create(made, version(made, 1, "a", "shared"))
        artifacts.addVersion(ORG_A, version(made, 2, "b", "shared"), t0)
        artifacts.addVersion(ORG_A, version(made, 3, "c", "shared"), t0)
        val expires = t0 + 30.days
        lifecycle.retain(ORG_A, made.id, 2, expires, t0)
        assertEquals(listOf(2, 3), server.versions(made.id))
        assertEquals(setOf("a"), server.marked(made.id))
        assertEquals(expires, server.expiry(made.id))
        // A later version that uses the marked content again clears its mark.
        artifacts.addVersion(ORG_A, version(made, 4, "a"), t0)
        lifecycle.retain(ORG_A, made.id, 2, null, t0)
        assertEquals(listOf(3, 4), server.versions(made.id))
        assertEquals(setOf("b"), server.marked(made.id))
        assertEquals(null, server.expiry(made.id))
        lifecycle.expire(ORG_A, made.id, expires)
        assertEquals(expires, server.expiry(made.id))
        // A content a live upload announced is left for its commit; an expired upload holds nothing.
        artifacts.addVersion(ORG_A, version(made, 5, "d"), t0)
        server.asSuperuser {
            ArtifactsUploads.insert {
                it[id] = upload
                it[orgId] = ORG_A
                it[artifactId] = made.id
                it[memberId] = "m"
                it[createdAt] = t0
                it[expiresAt] = t0 + 1.hours
            }
            ArtifactsUploadBlobs.insert {
                it[uploadId] = upload
                it[orgId] = ORG_A
                it[sha256] = "c"
                it[size] = 10
                it[state] = "held"
                it[position] = 0
            }
        }
        lifecycle.retain(ORG_A, made.id, 2, null, t0)
        assertEquals(setOf("b", "shared"), server.marked(made.id))
        lifecycle.retain(ORG_A, made.id, 1, null, t0 + 2.hours)
        assertEquals(setOf("a", "b", "c", "shared"), server.marked(made.id))
    }

    @Test
    fun aPublicationKeepsTheOrganizationsVersionsAndSetsTheExpiry() =
        serverTest(listOf(ArtifactsModule), testIdentities) { server ->
            server.koin.activateArtifacts()
            val storage = server.koin.get<ArtifactStorageRepository>()
            val settings = storage.overview(ORG_A).settings
            val retention = settings.retention.copy(personalDays = 7, maxVersionsPerArtifact = 1)
            storage.saveSettings(ORG_A, settings.copy(retention = retention), Clock.System.now())
            suspend fun publish(path: String, content: String) = server.client.post(path) {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody("""{"title": "Notes", "files": [{"path": "index.html", "content": "$content"}]}""")
            }
            assertEquals(HttpStatusCode.Created, publish("/api/v1/artifacts", "<p>one</p>").status)
            val id = server.onlyArtifactId()
            assertEquals(HttpStatusCode.Created, publish("/api/v1/artifacts/$id/versions", "<p>two</p>").status)
            assertEquals(listOf(2), server.versions(id))
            assertEquals(1, server.marked(id).size)
            assertNotNull(server.expiry(id))
        }

    @Test
    fun anArtifactWhoseContentsAreCollectedTakesNoUploadNorVersion() =
        serverTest(listOf(ArtifactsModule), testIdentities) { server ->
            server.koin.activateArtifacts()
            suspend fun post(path: String, body: String) = server.client.post(path) {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val page = """"files": [{"path": "index.html", "content": "<p>one</p>"}]"""
            assertEquals(HttpStatusCode.Created, post("/api/v1/artifacts", """{"title": "Notes", $page}""").status)
            val id = server.onlyArtifactId()
            server.superuserSql("UPDATE artifacts_blobs SET marked_at = now()")
            val announced = """{"artifactId": "$id", "files": [{"sha256": "${"a".repeat(64)}", "size": 1}]}"""
            for (refused in listOf(post("/api/v1/artifacts/$id/versions", "{$page}"), post(UPLOADS, announced))) {
                assertEquals(HttpStatusCode.Conflict, refused.status)
                assertEquals("60", refused.headers[HttpHeaders.RetryAfter])
                assertTrue("artifact_busy" in refused.bodyAsText())
            }
            server.superuserSql("UPDATE artifacts_blobs SET marked_at = NULL")
            assertEquals(HttpStatusCode.Created, post(UPLOADS, announced).status)
        }

    @Test
    fun theRetentionReadsItsIndexes() = serverTest(listOf(ArtifactsModule)) { server ->
        // 2 000 artifacts of 10 versions each, a content per version.
        server.superuserSql(
            "INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, created_at, " +
                "updated_at) SELECT CAST(md5('a' || i) AS uuid), CAST('$ORG_A' AS uuid), 'm', 'T', 'team', 10, " +
                "now(), now() FROM generate_series(0, 1999) AS i",
        )
        server.superuserSql(
            "INSERT INTO artifacts_versions (artifact_id, org_id, number, entry, files, created_at, created_by) " +
                "SELECT CAST(md5('a' || i) AS uuid), CAST('$ORG_A' AS uuid), n, 'i.html', '[{\"path\": \"i.html\", " +
                "\"sha256\": \"' || n || '\", \"size\": 1, \"type\": \"text/html\"}]', now(), 'm' " +
                "FROM generate_series(0, 1999) AS i, generate_series(1, 10) AS n",
        )
        server.superuserSql(
            "INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) SELECT CAST(md5('a' || i) AS uuid), " +
                "CAST('$ORG_A' AS uuid), '' || n, 1 FROM generate_series(0, 1999) AS i, generate_series(1, 10) AS n",
        )
        server.vacuumAnalyze("artifacts_artifacts", "artifacts_versions", "artifacts_blobs", "artifacts_uploads")
        val id = Uuid.random()
        val database = server.koin.get<JdbcDatabase>()
        val plans = database.inOrg(ORG_A) {
            listOf(
                plan { versionsPrune(ORG_A, id, 5) },
                plan { blobsMark(ORG_A, id, t0) },
                plan { markedBlob(ORG_A, id) },
            ).also { rollback() }
        }
        for (plan in plans) {
            for (table in listOf("artifacts_artifacts", "artifacts_versions", "artifacts_blobs")) {
                assertFalse("Seq Scan on $table" in plan, "The retention scans $table:\n$plan")
            }
        }
    }
}
