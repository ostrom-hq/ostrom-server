// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactBlob
import run.ostrom.server.artifacts.domain.ArtifactMaintenanceRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactMaintenanceRepository]: the organizations found by `artifacts_due_organizations` ([ArtifactsMigrations]
 * version 12), then each one's rows in its own transactions under row-level security.
 *
 * @param driver The database.
 */
internal class ArtifactMaintenanceRepositoryImpl(private val driver: Driver) : ArtifactMaintenanceRepository {
    override suspend fun organizations(now: Instant, drafts: Instant, publications: Instant, limit: Int): List<Uuid> =
        driver.fetchAll(
            Statement.create(
                "SELECT org_id FROM artifacts_due_organizations(:now, :drafts, :publications, :limit) AS due (org_id)",
            ).bind("now", now).bind("drafts", drafts).bind("publications", publications).bind("limit", limit),
        ).getOrThrow().map { it.get("org_id").asUuid() }

    override suspend fun forgetPublications(orgId: Uuid, before: Instant) {
        driver.inTenant(orgId) {
            val forget = Statement.create("DELETE FROM artifacts_publications WHERE published_at < :before")
            execute(forget.bind("before", before)).getOrThrow()
        }
    }

    override suspend fun expiredUploads(orgId: Uuid, now: Instant, limit: Int): List<Uuid> = driver.fetchInOrg(
        orgId,
        Statement.create(MaintenanceQueries.EXPIRED_UPLOADS).bind("now", now).bind("limit", limit),
    ).getOrThrow().map { it.get("id").asUuid() }

    override suspend fun endUpload(orgId: Uuid, uploadId: Uuid, now: Instant) {
        driver.inTenant(orgId) {
            val ended = fetchAll(
                Statement.create(
                    "DELETE FROM artifacts_uploads WHERE id = :id AND expires_at <= :now RETURNING artifact_id",
                ).bind("id", uploadId).bind("now", now),
            ).getOrThrow()
            ended.forEach { row ->
                val mark = Statement.create(LifecycleQueries.MARK).bind("id", row.get("artifact_id").asUuid())
                execute(mark.bind("now", now)).getOrThrow()
            }
        }
    }

    override suspend fun expired(orgId: Uuid, now: Instant, drafts: Instant, limit: Int): List<Uuid> =
        driver.fetchInOrg(
            orgId,
            Statement.create(MaintenanceQueries.EXPIRED).bind("now", now).bind("drafts", drafts).bind("limit", limit),
        ).getOrThrow().map { it.get("id").asUuid() }

    override suspend fun deleteExpired(orgId: Uuid, artifactId: Uuid, now: Instant, drafts: Instant): Int? =
        driver.inTenant(orgId) {
            fetchAll(
                Statement.create(MaintenanceQueries.DELETE_EXPIRED).bind("id", artifactId).bind("now", now)
                    .bind("drafts", drafts),
            ).getOrThrow().firstOrNull()?.let { it.get("latest_version").asInt() }
        }

    override suspend fun markedBlobs(orgId: Uuid, now: Instant, limit: Int): List<ArtifactBlob> = driver.fetchInOrg(
        orgId,
        Statement.create(MaintenanceQueries.MARKED).bind("now", now).bind("limit", limit),
    ).getOrThrow().map { ArtifactBlob(it.get("artifact_id").asUuid(), it.get("sha256").asString()) }

    override suspend fun forgetBlobs(orgId: Uuid, blobs: List<ArtifactBlob>) {
        driver.inTenant(orgId) {
            blobs.forEach { blob ->
                val forget = Statement.create(MaintenanceQueries.FORGET).bind("id", blob.artifactId)
                execute(forget.bind("sha", blob.sha256)).getOrThrow()
            }
        }
    }
}

/** The statements of the maintenance, each run in one organization. */
internal object MaintenanceQueries {
    /** The uploads expired by `:now`: `artifacts_uploads_expiry`. */
    const val EXPIRED_UPLOADS =
        "SELECT id FROM artifacts_uploads WHERE expires_at <= :now ORDER BY expires_at LIMIT :limit"

    /** Past their retention, or a draft created before `:drafts`; never while an upload to it is live. */
    private const val DUE = "(a.expires_at <= :now OR (a.latest_version = 0 AND a.created_at < :drafts)) AND " +
        "NOT EXISTS (SELECT 1 FROM artifacts_uploads u WHERE u.artifact_id = a.id AND u.expires_at > :now)"

    /** The artifacts due: `artifacts_artifacts_expiry` and `artifacts_artifacts_draft_age`. */
    const val EXPIRED = "SELECT a.id FROM artifacts_artifacts a WHERE $DUE LIMIT :limit"

    /** The contents marked, unless a live upload announced one: `artifacts_blobs_marked`. */
    const val MARKED = "SELECT b.artifact_id, b.sha256 FROM artifacts_blobs b WHERE b.marked_at IS NOT NULL AND " +
        "NOT EXISTS (SELECT 1 FROM artifacts_uploads u JOIN artifacts_upload_blobs ub ON ub.upload_id = u.id " +
        "WHERE u.artifact_id = b.artifact_id AND u.expires_at > :now AND ub.sha256 = b.sha256) " +
        "ORDER BY b.marked_at LIMIT :limit"

    /** A content deleted from the bucket, still marked. */
    const val FORGET = "DELETE FROM artifacts_blobs WHERE artifact_id = :id AND sha256 = :sha AND marked_at IS NOT NULL"

    /** One of them, if it is still due. */
    const val DELETE_EXPIRED = "DELETE FROM artifacts_artifacts a WHERE a.id = :id AND $DUE RETURNING a.latest_version"
}
