// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactMaintenanceRepository
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.asSuperuser
import run.ostrom.server.artifacts.fakes.superuserSql
import run.ostrom.server.artifacts.fakes.texts
import run.ostrom.server.artifacts.fakes.vacuumAnalyze
import run.ostrom.server.artifacts.infrastructure.ArtifactsArtifacts
import run.ostrom.server.artifacts.infrastructure.ArtifactsBlobs
import run.ostrom.server.artifacts.infrastructure.ArtifactsPublications
import run.ostrom.server.artifacts.infrastructure.ArtifactsUploadBlobs
import run.ostrom.server.artifacts.infrastructure.ArtifactsUploads
import run.ostrom.server.artifacts.infrastructure.artifactDeleted
import run.ostrom.server.artifacts.infrastructure.artifactsDue
import run.ostrom.server.artifacts.infrastructure.blobForgotten
import run.ostrom.server.artifacts.infrastructure.blobsMarked
import run.ostrom.server.artifacts.infrastructure.dueOrganizationsSql
import run.ostrom.server.artifacts.infrastructure.foldersDeleted
import run.ostrom.server.artifacts.infrastructure.publicationsForget
import run.ostrom.server.artifacts.infrastructure.uploadsExpired
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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

    private suspend fun TestServer.artifact(org: Uuid, latest: Int, age: Duration, expires: Instant? = null): Uuid {
        val id = Uuid.random()
        asSuperuser {
            ArtifactsArtifacts.insert {
                it[this.id] = id
                it[orgId] = org
                it[ownerId] = "m"
                it[title] = "T"
                it[access] = "team"
                it[latestVersion] = latest
                it[createdAt] = now - age
                it[updatedAt] = now - age
                it[expiresAt] = expires
            }
        }
        return id
    }

    private suspend fun TestServer.upload(org: Uuid, artifact: Uuid, expires: Instant, vararg shas: String): Uuid {
        val id = Uuid.random()
        asSuperuser {
            ArtifactsUploads.insert {
                it[this.id] = id
                it[orgId] = org
                it[artifactId] = artifact
                it[memberId] = "m"
                it[createdAt] = expires - 1.hours
                it[expiresAt] = expires
            }
            shas.forEachIndexed { i, sha ->
                ArtifactsUploadBlobs.insert {
                    it[uploadId] = id
                    it[orgId] = org
                    it[sha256] = sha
                    it[size] = 1
                    it[state] = "stored"
                    it[position] = i
                }
                ArtifactsBlobs.insertIgnore {
                    it[artifactId] = artifact
                    it[orgId] = org
                    it[sha256] = sha
                    it[size] = 1
                }
            }
        }
        return id
    }

    private suspend fun TestServer.ids(sql: String) = asSuperuser { texts(sql) }.toSet()

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
        server.asSuperuser {
            for (publishedAt in listOf(now - 2.hours, now - 5.minutes)) {
                ArtifactsPublications.insert {
                    it[orgId] = ORG_A
                    it[memberId] = "m"
                    it[artifactId] = retained
                    it[this.publishedAt] = publishedAt
                }
            }
        }

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
        val recent = server.asSuperuser {
            ArtifactsPublications.selectAll().where { ArtifactsPublications.publishedAt greater publications }.count()
        }
        assertEquals(1L, recent)

        assertEquals(listOf(expired), maintenance.expiredUploads(ORG_B, now, 10))
        assertEquals(emptyList(), maintenance.expiredUploads(ORG_A, now, 10))
        maintenance.endUpload(ORG_B, expired, now)
        val remaining = server.asSuperuser {
            ArtifactsUploads.selectAll().where { ArtifactsUploads.id eq expired }.count()
        }
        assertEquals(0L, remaining)
        val marked = server.asSuperuser {
            ArtifactsBlobs.select(ArtifactsBlobs.sha256)
                .where { (ArtifactsBlobs.artifactId eq pending) and ArtifactsBlobs.markedAt.isNotNull() }
                .map { it[ArtifactsBlobs.sha256] }
        }
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
        server.asSuperuser { ArtifactsBlobs.update { it[markedAt] = now - 1.minutes } }
        val marked = maintenance.markedBlobs(ORG_A, now, 10)
        // A live upload counts on `announced`: it is not collected.
        assertEquals(setOf("old", "reused"), marked.map { it.sha256 }.toSet())
        assertEquals(emptyList(), maintenance.markedBlobs(ORG_B, now, 10))
        // A version used `reused` again before the deletion was forgotten: its mark is cleared, its row stays.
        server.asSuperuser { ArtifactsBlobs.update({ ArtifactsBlobs.sha256 eq "reused" }) { it[markedAt] = null } }
        maintenance.forgetBlobs(ORG_A, marked)
        assertEquals(setOf("reused", "announced"), server.ids("SELECT sha256 FROM artifacts_blobs"))
    }

    @Test
    fun aDeletedArtifactLeavesItsFolderUntilItIsProvenEmpty() = serverTest(listOf(ArtifactsModule)) { server ->
        val maintenance = server.koin.get<ArtifactMaintenanceRepository>()
        val artifacts = server.koin.get<ArtifactsRepository>()
        val (owned, expired) = server.artifact(ORG_A, 1, 1.hours) to server.artifact(ORG_A, 1, 2.hours, now - 1.minutes)
        val elsewhere = server.artifact(ORG_B, 1, 1.hours)
        assertEquals(emptySet(), maintenance.organizations(now - 1.hours, drafts, publications, 10).toSet())
        artifacts.delete(ORG_A, owned)
        assertEquals(1, maintenance.deleteExpired(ORG_A, expired, now, drafts))
        artifacts.delete(ORG_B, elsewhere)
        assertEquals(setOf(owned, expired), maintenance.deletedFolders(ORG_A, 10).toSet())
        assertEquals(1, maintenance.deletedFolders(ORG_A, 1).size)
        assertEquals(listOf(elsewhere), maintenance.deletedFolders(ORG_B, 10))
        assertEquals(setOf(ORG_A, ORG_B), maintenance.organizations(now - 1.hours, drafts, publications, 10).toSet())
        maintenance.folderRemoved(ORG_A, owned)
        maintenance.folderRemoved(ORG_A, elsewhere)
        assertEquals(listOf(expired), maintenance.deletedFolders(ORG_A, 10))
        assertEquals(listOf(elsewhere), maintenance.deletedFolders(ORG_B, 10))
    }

    @Test
    fun theMaintenanceReadsItsIndexes() = serverTest(listOf(ArtifactsModule)) { server ->
        // 20 000 artifacts of 2 000 organizations, an upload, a content and two publications each, none of them due.
        server.superuserSql(
            "INSERT INTO artifacts_artifacts (id, org_id, owner_id, title, access, latest_version, created_at, " +
                "updated_at, expires_at) SELECT CAST(md5('a' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), " +
                "'m', 'T', 'team', i % 2, '$now', '$now', '${now + 30.hours}' FROM generate_series(0, 19999) AS i",
        )
        server.superuserSql(
            "INSERT INTO artifacts_uploads (id, org_id, artifact_id, member_id, created_at, expires_at) SELECT " +
                "CAST(md5('u' || i) AS uuid), CAST(md5('o' || (i % 2000)) AS uuid), CAST(md5('a' || i) AS uuid), " +
                "'m', '$now', '${now + 1.hours}' FROM generate_series(0, 19999) AS i",
        )
        server.superuserSql(
            "INSERT INTO artifacts_publications (org_id, member_id, artifact_id, published_at) SELECT " +
                "CAST(md5('o' || (i % 2000)) AS uuid), 'm', CAST(md5('a' || i) AS uuid), '$now' " +
                "FROM generate_series(0, 39999) AS i",
        )
        server.superuserSql(
            "INSERT INTO artifacts_blobs (artifact_id, org_id, sha256, size) SELECT CAST(md5('a' || i) AS uuid), " +
                "CAST(md5('o' || (i % 2000)) AS uuid), 'x', 1 FROM generate_series(0, 19999) AS i",
        )
        server.vacuumAnalyze(*MAINTAINED.toTypedArray())
        // As the function runs it: the pending folders are few, read whole.
        val due = dueOrganizationsSql("'$now'", "'$drafts'", "'$publications'", "100", folders = true)
        val plans = listOf(
            due to server.asSuperuser { texts("EXPLAIN $due").joinToString("\n") },
        )
        val id = Uuid.random()
        val queryPlans = server.koin.get<JdbcDatabase>().inOrg(ORG_A) {
            listOf(
                plan { uploadsExpired(ORG_A, now, 100) },
                plan { artifactsDue(ORG_A, now, drafts, 100) },
                plan { artifactDeleted(ORG_A, id, now, drafts) },
                plan { blobsMarked(ORG_A, now, 100) },
                plan { blobForgotten(ORG_A, id, "x") },
                plan { foldersDeleted(ORG_A, 100) },
                plan { publicationsForget(ORG_A, publications) },
            ).map { "the repository's statement" to it }.also { rollback() }
        }
        for ((sql, plan) in plans + queryPlans) {
            for (table in MAINTAINED) {
                assertFalse("Seq Scan on $table" in plan, "The maintenance scans $table:\n$sql\n$plan")
            }
        }
    }
}
