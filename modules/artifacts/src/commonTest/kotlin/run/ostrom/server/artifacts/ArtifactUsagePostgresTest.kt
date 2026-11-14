// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactUsage
import run.ostrom.server.artifacts.domain.ArtifactUsageRepository
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.BOB
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.activateArtifacts
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.infrastructure.UsageQueries
import run.ostrom.server.artifacts.infrastructure.fetchInOrg
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: what owners and organizations hold, the quotas' refusal, the plans. */
class ArtifactUsagePostgresTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    private fun artifact(owner: Uuid, org: Uuid = ORG_A, latest: Int = 1) =
        Artifact(Uuid.random(), org, owner.toString(), "T", ArtifactAccess.TEAM, latest, null, t0, t0)

    private fun version(artifact: Artifact, vararg sizes: Long) = ArtifactVersion(
        artifact.id, 1, "f0.html",
        sizes.mapIndexed { i, size -> ArtifactFile("f$i.html", "sha-$i", size, "text/html") }, t0, artifact.ownerId,
    )

    @Test
    fun theUsageSumsTheFoldersOfTheOwnerAndOfTheOrganization() = serverTest(listOf(ArtifactsModule)) { server ->
        val artifacts = server.koin.get<ArtifactsRepository>()
        val usage = server.koin.get<ArtifactUsageRepository>()
        for ((owner, sizes) in listOf(ALICE to longArrayOf(10, 20), ALICE to longArrayOf(5), BOB to longArrayOf(100))) {
            val made = artifact(owner)
            artifacts.create(made, version(made, *sizes))
        }
        val elsewhere = artifact(ALICE, ORG_B)
        artifacts.create(elsewhere, version(elsewhere, 1000))
        assertEquals(ArtifactUsage(ownerBytes = 35, orgBytes = 135, ownerArtifacts = 2), usage.usage(ORG_A, "$ALICE"))
        assertEquals(ArtifactUsage(0, 135, 0), usage.usage(ORG_A, "nobody"))
    }

    @Test
    fun aPublicationOverTheOwnersBytesIsRefused() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        server.koin.activateArtifacts()
        val storage = server.koin.get<ArtifactStorageRepository>()
        val settings = storage.overview(ORG_A).settings
        storage.saveSettings(ORG_A, settings.copy(quotas = settings.quotas.copy(memberBytes = 12)), Clock.System.now())
        suspend fun publish(content: String) = server.client.post("/api/v1/artifacts") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"title": "Report", "files": [{"path": "index.html", "content": "$content"}]}""")
        }
        assertEquals(HttpStatusCode.Created, publish("<p>hi</p>").status)
        val refused = publish("<p>again</p>")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("artifact_quota_exceeded" in refused.bodyAsText())
    }

    @Test
    fun theUsageReadsItsIndexes() = serverTest(listOf(ArtifactsModule)) { server ->
        val fixtures = server.database.superuser
        // 20 000 artifacts of 2 000 organizations, each with a content.
        fixtures.execute(
            "INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, created_at, " +
                "updated_at) SELECT CAST(md5('a' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), 'm' || " +
                "(i % 7), 'T', 'team', 1, now(), now() FROM generate_series(0, 19999) AS i",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) SELECT CAST(md5('a' || i) AS uuid), " +
                "CAST(md5('o' || (i % 2000)) AS uuid), 'x', 10 FROM generate_series(0, 19999) AS i",
        ).getOrThrow()
        for (table in listOf("artifacts_artifacts", "artifacts_blobs")) {
            fixtures.execute("VACUUM ANALYZE $table").getOrThrow()
        }
        for (sql in listOf(UsageQueries.BYTES, UsageQueries.ARTIFACTS)) {
            val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql")
                .bind("org_id", ORG_A).bind("owner", ALICE.toString())
            val plan = server.driver.fetchInOrg(ORG_A, statement).getOrThrow()
                .joinToString("\n") { it.get("QUERY PLAN").asString() }
            for (table in listOf("artifacts_artifacts", "artifacts_blobs")) {
                assertFalse("Seq Scan on $table" in plan, "The usage scans $table:\n$plan")
            }
        }
    }
}
