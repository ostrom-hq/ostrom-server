// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactBlob
import run.ostrom.server.artifacts.domain.ArtifactMaintenanceRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.write
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactMaintenanceRepository]: the organizations found by `artifacts_due_organizations` ([ArtifactsMigrations]
 * version 12), then each one's rows in its own transactions under row-level security.
 *
 * @param database The serving role's pool.
 */
internal class ArtifactMaintenanceRepositoryImpl(private val database: JdbcDatabase) :
    ArtifactMaintenanceRepository {
    override suspend fun organizations(now: Instant, drafts: Instant, publications: Instant, limit: Int): List<Uuid> =
        database.query {
            val due = ArtifactsDueOrganizations
            due.of(now, drafts, publications, limit).select(due.orgId).map { it[due.orgId] }
        }

    override suspend fun forgetPublications(orgId: Uuid, before: Instant) {
        database.inOrg(orgId) { write { publicationsForget(orgId, before) } }
    }

    override suspend fun expiredUploads(orgId: Uuid, now: Instant, limit: Int): List<Uuid> =
        database.inOrg(orgId) { uploadsExpired(orgId, now, limit).map { it[ArtifactsUploads.id] } }

    override suspend fun endUpload(orgId: Uuid, uploadId: Uuid, now: Instant) {
        database.inOrg(orgId) {
            val ended = buildStatement { uploadEnded(orgId, uploadId, now) }.rows()
            ended.forEach { markUnused(orgId, it[ArtifactsUploads.artifactId], now) }
        }
    }

    override suspend fun expired(orgId: Uuid, now: Instant, drafts: Instant, limit: Int): List<Uuid> =
        database.inOrg(orgId) { artifactsDue(orgId, now, drafts, limit).map { it[ArtifactsArtifacts.id] } }

    override suspend fun deleteExpired(orgId: Uuid, artifactId: Uuid, now: Instant, drafts: Instant): Int? =
        database.inOrg(orgId) {
            val deleted = buildStatement { artifactDeleted(orgId, artifactId, now, drafts) }.rows()
            deleted.firstOrNull()?.get(ArtifactsArtifacts.latestVersion)
        }

    override suspend fun markedBlobs(orgId: Uuid, now: Instant, limit: Int): List<ArtifactBlob> =
        database.inOrg(orgId) {
            val b = ArtifactsBlobs
            blobsMarked(orgId, now, limit).map { ArtifactBlob(it[b.artifactId], it[b.sha256]) }
        }

    override suspend fun forgetBlobs(orgId: Uuid, blobs: List<ArtifactBlob>) {
        database.inOrg(orgId) {
            blobs.forEach { blob -> write { blobForgotten(orgId, blob.artifactId, blob.sha256) } }
        }
    }

    override suspend fun deletedFolders(orgId: Uuid, limit: Int): List<Uuid> = database.inOrg(orgId) {
        foldersDeleted(orgId, limit).map { it[ArtifactsFolderDeletions.artifactId] }
    }

    override suspend fun folderRemoved(orgId: Uuid, artifactId: Uuid) {
        database.inOrg(orgId) { write { folderForgotten(orgId, artifactId) } }
    }
}
