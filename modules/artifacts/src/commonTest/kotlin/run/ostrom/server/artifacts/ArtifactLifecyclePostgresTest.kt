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
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.infrastructure.LifecycleQueries
import run.ostrom.server.artifacts.infrastructure.fetchInOrg
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TC-ART-29 against a real PostgreSQL: the versions kept, the contents marked, the expiry. */
class ArtifactLifecyclePostgresTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    private fun version(artifact: Artifact, number: Int, vararg shas: String) = ArtifactVersion(
        artifact.id, number, "${shas.first()}.html",
        shas.map { ArtifactFile("$it.html", it, 10, "text/html") }, t0, artifact.ownerId,
    )

    private suspend fun Driver.versions(id: Uuid) = fetchInOrg(
        ORG_A, Statement.create("SELECT number FROM artifacts_versions WHERE artifact_id = :id ORDER BY number")
            .bind("id", id),
    ).getOrThrow().map { it.get("number").asString().toInt() }

    private suspend fun Driver.marked(id: Uuid) = fetchInOrg(
        ORG_A, Statement.create("SELECT sha256 FROM artifacts_blobs WHERE artifact_id = :id AND marked_at IS NOT NULL")
            .bind("id", id),
    ).getOrThrow().map { it.get("sha256").asString() }.toSet()

    private suspend fun Driver.expiry(id: Uuid) = fetchInOrg(
        ORG_A, Statement.create("SELECT expires_at FROM artifacts_artifacts WHERE id = :id").bind("id", id),
    ).getOrThrow().single().get("expires_at").asInstantOrNull()

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
        assertEquals(listOf(2, 3), server.driver.versions(made.id))
        assertEquals(setOf("a"), server.driver.marked(made.id))
        assertEquals(expires, server.driver.expiry(made.id))
        // A later version that uses the marked content again clears its mark.
        artifacts.addVersion(ORG_A, version(made, 4, "a"), t0)
        lifecycle.retain(ORG_A, made.id, 2, null, t0)
        assertEquals(listOf(3, 4), server.driver.versions(made.id))
        assertEquals(setOf("b"), server.driver.marked(made.id))
        assertEquals(null, server.driver.expiry(made.id))
        lifecycle.expire(ORG_A, made.id, expires)
        assertEquals(expires, server.driver.expiry(made.id))
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
            val id = server.driver.fetchInOrg(ORG_A, Statement.create("SELECT id FROM artifacts_artifacts"))
                .getOrThrow().single().get("id").asUuid()
            assertEquals(HttpStatusCode.Created, publish("/api/v1/artifacts/$id/versions", "<p>two</p>").status)
            assertEquals(listOf(2), server.driver.versions(id))
            assertEquals(1, server.driver.marked(id).size)
            assertNotNull(server.driver.expiry(id))
        }

    @Test
    fun theRetentionReadsItsIndexes() = serverTest(listOf(ArtifactsModule)) { server ->
        val fixtures = server.database.superuser
        // 2 000 artifacts of 10 versions each, a content per version.
        fixtures.execute(
            "INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, created_at, " +
                "updated_at) SELECT CAST(md5('a' || i) AS uuid), CAST('$ORG_A' AS uuid), 'm', 'T', 'team', 10, " +
                "now(), now() FROM generate_series(0, 1999) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO artifacts_versions (artifact_id, org_id, number, entry, files, created_at, created_by) " +
                "SELECT CAST(md5('a' || i) AS uuid), CAST('$ORG_A' AS uuid), n, 'i.html', '[{\"path\": \"i.html\", " +
                "\"sha256\": \"' || n || '\", \"size\": 1, \"type\": \"text/html\"}]', now(), 'm' " +
                "FROM generate_series(0, 1999) AS i, generate_series(1, 10) AS n",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) SELECT CAST(md5('a' || i) AS uuid), " +
                "CAST('$ORG_A' AS uuid), '' || n, 1 FROM generate_series(0, 1999) AS i, generate_series(1, 10) AS n",
        ).getOrThrow()
        for (table in listOf("artifacts_artifacts", "artifacts_versions", "artifacts_blobs")) {
            fixtures.execute("VACUUM ANALYZE $table").getOrThrow()
        }
        val id = Uuid.random()
        for (sql in listOf(LifecycleQueries.PRUNE, LifecycleQueries.MARK)) {
            val statement = Statement.create("EXPLAIN $sql").bind("id", id).bind("keep", 5).bind("now", t0)
            val plan = server.driver.fetchInOrg(ORG_A, statement).getOrThrow()
                .joinToString("\n") { it.get("QUERY PLAN").asString() }
            for (table in listOf("artifacts_artifacts", "artifacts_versions", "artifacts_blobs")) {
                assertFalse("Seq Scan on $table" in plan, "The retention scans $table:\n$plan")
            }
        }
    }
}
