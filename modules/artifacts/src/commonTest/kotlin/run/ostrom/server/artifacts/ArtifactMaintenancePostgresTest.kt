// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactMaintenanceRepository
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.infrastructure.MaintenanceQueries
import run.ostrom.server.artifacts.infrastructure.dueOrganizationsSql
import run.ostrom.server.artifacts.infrastructure.fetchInOrg
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val MAINTAINED = listOf("artifacts_artifacts", "artifacts_uploads", "artifacts_publications", "artifacts_blobs")

/** TC-ART-34 against a real PostgreSQL: what the maintenance finds across organizations and ends in each. */
class ArtifactMaintenancePostgresTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val drafts = now - 24.hours
    private val publications = now - 1.hours

    private suspend fun TestServer.sql(sql: String) = database.superuser.execute(sql).getOrThrow()

    private suspend fun TestServer.artifact(org: Uuid, latest: Int, age: Duration, expires: Instant? = null): Uuid {
        val id = Uuid.random()
        val expiry = expires?.let { "'$it'" } ?: "NULL"
        sql(
            "INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, created_at, " +
                "updated_at, expires_at) VALUES ('$id', '$org', 'm', 'T', 'team', $latest, '${now - age}', " +
                "'${now - age}', $expiry)",
        )
        return id
    }

    private suspend fun TestServer.upload(org: Uuid, artifact: Uuid, expires: Instant, vararg shas: String): Uuid {
        val id = Uuid.random()
        sql(
            "INSERT INTO artifacts_uploads (id, org_id, artifact_id, member_id, created_at, expires_at) VALUES " +
                "('$id', '$org', '$artifact', 'm', '${expires - 1.hours}', '$expires')",
        )
        shas.forEachIndexed { i, sha ->
            sql(
                "INSERT INTO artifacts_upload_blobs (upload_id, org_id, sha256, size, state, position) VALUES " +
                    "('$id', '$org', '$sha', 1, 'stored', $i)",
            )
            sql(
                "INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) VALUES " +
                    "('$artifact', '$org', '$sha', 1) ON CONFLICT DO NOTHING",
            )
        }
        return id
    }

    private suspend fun TestServer.ids(sql: String) =
        database.superuser.fetchAll(sql).getOrThrow().map { it.get("id").asString() }.toSet()

    @Test
    fun theDueOrganizationsAndTheirExpiredRows() = serverTest(listOf(ArtifactsModule)) { server ->
        val maintenance = server.koin.get<ArtifactMaintenanceRepository>()
        val quiet = Uuid.random()
        server.artifact(quiet, 1, 2.hours, expires = now + 1.hours)
        server.artifact(quiet, 0, 1.hours)
        val retained = server.artifact(ORG_A, 2, 48.hours, expires = now - 1.minutes)
        val draft = server.artifact(ORG_A, 0, 25.hours)
        val uploading = server.artifact(ORG_A, 0, 30.hours)
        server.upload(ORG_A, uploading, now + 10.minutes)
        val pending = server.artifact(ORG_B, 1, 1.hours)
        val expired = server.upload(ORG_B, pending, now - 1.minutes, "orphan")
        server.sql(
            "INSERT INTO artifacts_publications (org_id, member_id, artifact_id, published_at) VALUES " +
                "('$ORG_A', 'm', '$retained', '${now - 2.hours}'), ('$ORG_A', 'm', '$retained', '${now - 5.minutes}')",
        )

        assertEquals(setOf(ORG_A, ORG_B), maintenance.organizations(now, drafts, publications, 10).toSet())
        assertEquals(1, maintenance.organizations(now, drafts, publications, 1).size)

        assertEquals(listOf(retained, draft), maintenance.expired(ORG_A, now, drafts, 10).sortedBy { it != retained })
        assertEquals(2, maintenance.deleteExpired(ORG_A, retained, now, drafts))
        assertEquals(0, maintenance.deleteExpired(ORG_A, draft, now, drafts))
        // Gone already, or not due: nothing is deleted.
        assertNull(maintenance.deleteExpired(ORG_A, draft, now, drafts))
        assertNull(maintenance.deleteExpired(ORG_A, uploading, now, drafts))

        maintenance.forgetPublications(ORG_A, publications)
        val count = "SELECT CAST(count(*) AS text) AS id FROM artifacts_publications"
        assertEquals(setOf("1"), server.ids(count))
        assertEquals(setOf("1"), server.ids("$count WHERE published_at > '$publications'"))

        assertEquals(listOf(expired), maintenance.expiredUploads(ORG_B, now, 10))
        assertEquals(emptyList(), maintenance.expiredUploads(ORG_A, now, 10))
        maintenance.endUpload(ORG_B, expired, now)
        assertEquals(emptySet(), server.ids("SELECT id FROM artifacts_uploads WHERE id = '$expired'"))
        val marked = server.database.superuser.fetchAll(
            "SELECT sha256 FROM artifacts_blobs WHERE artifact_id = '$pending' AND marked_at IS NOT NULL",
        ).getOrThrow().map { it.get("sha256").asString() }
        assertEquals(listOf("orphan"), marked)
        // The marked content keeps org B due, for its collection.
        assertEquals(setOf(ORG_A, ORG_B), maintenance.organizations(now, drafts, publications, 10).toSet())
    }

    @Test
    fun theMarkedContentsAreFoundThenForgottenUnlessUsedAgain() = serverTest(listOf(ArtifactsModule)) { server ->
        val maintenance = server.koin.get<ArtifactMaintenanceRepository>()
        val artifact = server.artifact(ORG_A, 1, 1.hours)
        server.upload(ORG_A, artifact, now - 1.minutes, "old", "reused", "announced")
        server.upload(ORG_A, artifact, now + 10.minutes, "announced")
        server.sql("UPDATE artifacts_blobs SET marked_at = '${now - 1.minutes}'")
        val marked = maintenance.markedBlobs(ORG_A, now, 10)
        // A live upload counts on `announced`: it is not collected.
        assertEquals(setOf("old", "reused"), marked.map { it.sha256 }.toSet())
        assertEquals(emptyList(), maintenance.markedBlobs(ORG_B, now, 10))
        // A version used `reused` again before the deletion was forgotten: its mark is cleared, its row stays.
        server.sql("UPDATE artifacts_blobs SET marked_at = NULL WHERE sha256 = 'reused'")
        maintenance.forgetBlobs(ORG_A, marked)
        assertEquals(setOf("reused", "announced"), server.ids("SELECT sha256 AS id FROM artifacts_blobs"))
    }

    @Test
    fun theMaintenanceReadsItsIndexes() = serverTest(listOf(ArtifactsModule)) { server ->
        // 20 000 artifacts of 2 000 organizations, an upload, a content and two publications each, none of them due.
        server.sql(
            "INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, created_at, " +
                "updated_at, expires_at) SELECT CAST(md5('a' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), " +
                "'m', 'T', 'team', i % 2, '$now', '$now', '${now + 30.hours}' FROM generate_series(0, 19999) AS i",
        )
        server.sql(
            "INSERT INTO artifacts_uploads (id, org_id, artifact_id, member_id, created_at, expires_at) SELECT " +
                "CAST(md5('u' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), CAST(md5('a' || i) AS uuid), " +
                "'m', '$now', '${now + 1.hours}' FROM generate_series(0, 19999) AS i",
        )
        server.sql(
            "INSERT INTO artifacts_publications (org_id, member_id, artifact_id, published_at) SELECT " +
                "CAST(md5('o' || (i % 2000)) AS uuid), 'm', CAST(md5('a' || i) AS uuid), '$now' " +
                "FROM generate_series(0, 39999) AS i",
        )
        server.sql(
            "INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) SELECT CAST(md5('a' || i) AS uuid), " +
                "CAST(md5('o' || (i % 2000)) AS uuid), 'x', 1 FROM generate_series(0, 19999) AS i",
        )
        for (table in MAINTAINED) {
            server.sql("VACUUM ANALYZE $table")
        }
        val due = dueOrganizationsSql("'$now'", "'$drafts'", "'$publications'", "100")
        val plans = mutableListOf(
            due to server.database.superuser.fetchAll("EXPLAIN $due").getOrThrow()
                .joinToString("\n") { it.get("QUERY PLAN").asString() },
        )
        val binds = mapOf("now" to now, "drafts" to drafts, "publications" to publications, "limit" to 100)
        val statements = listOf(
            MaintenanceQueries.EXPIRED_UPLOADS,
            MaintenanceQueries.EXPIRED,
            MaintenanceQueries.DELETE_EXPIRED,
            MaintenanceQueries.MARKED,
            MaintenanceQueries.FORGET,
            "DELETE FROM artifacts_publications WHERE published_at < :publications",
        )
        for (sql in statements) {
            var statement = Statement.create("EXPLAIN $sql")
            binds.filterKeys { ":$it" in sql }.forEach { (name, value) -> statement = statement.bind(name, value) }
            if (":id" in sql) statement = statement.bind("id", Uuid.random())
            if (":sha" in sql) statement = statement.bind("sha", "x")
            plans += sql to server.driver.fetchInOrg(ORG_A, statement).getOrThrow()
                .joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
        for ((sql, plan) in plans) {
            for (table in MAINTAINED) {
                assertFalse("Seq Scan on $table" in plan, "The maintenance scans $table:\n$sql\n$plan")
            }
        }
    }
}
