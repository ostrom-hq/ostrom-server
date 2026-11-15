// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // One function per statement of the maintenance, so each has its plan.

package run.ostrom.server.artifacts.infrastructure

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.statements.ReturningBlockingExecutable
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The statements of the maintenance (ArtifactMaintenanceRepositoryImpl), one function each so that the plan test
// explains exactly what the repository sends. Every one filters on the organization.

/** The publications made before [before], forgotten: `artifacts_publications_age`. */
internal fun StatementBuilder.publicationsForget(orgId: Uuid, before: Instant): DeleteStatement {
    val p = ArtifactsPublications
    return p.deleteWhere { (p.orgId eq orgId) and (p.publishedAt less before) }
}

/** The uploads expired by [now]: `artifacts_uploads_expiry`. */
internal fun uploadsExpired(orgId: Uuid, now: Instant, limit: Int): Query {
    val u = ArtifactsUploads
    return u.select(u.id).where { (u.orgId eq orgId) and (u.expiresAt lessEq now) }.orderBy(u.expiresAt).limit(limit)
}

/** An expired upload ended, returning its artifact. */
internal fun StatementBuilder.uploadEnded(orgId: Uuid, uploadId: Uuid, now: Instant): ReturningStatement {
    val u = ArtifactsUploads
    return u.deleteReturning(listOf(u.artifactId)) {
        (u.id eq uploadId) and (u.orgId eq orgId) and (u.expiresAt lessEq now)
    }
}

/** Past their retention, or a draft created before [drafts]; never while an upload to it is live. */
private fun due(a: ArtifactsArtifacts, now: Instant, drafts: Instant): Op<Boolean> {
    val u = ArtifactsUploads
    val live = u.select(intLiteral(1)).where { (u.artifactId eq a.id) and (u.expiresAt greater now) }
    return ((a.expiresAt lessEq now) or ((a.latestVersion eq 0) and (a.createdAt less drafts))) and notExists(live)
}

/** The artifacts due: `artifacts_artifacts_expiry` and `artifacts_artifacts_draft_age`. */
internal fun artifactsDue(orgId: Uuid, now: Instant, drafts: Instant, limit: Int): Query {
    val a = ArtifactsArtifacts
    return a.select(a.id).where { (a.orgId eq orgId) and due(a, now, drafts) }.limit(limit)
}

/** One of them, deleted if it is still due. */
internal fun StatementBuilder.artifactDeleted(
    orgId: Uuid,
    artifactId: Uuid,
    now: Instant,
    drafts: Instant,
): ReturningStatement {
    val a = ArtifactsArtifacts
    return a.deleteReturning(listOf(a.latestVersion)) {
        (a.id eq artifactId) and (a.orgId eq orgId) and due(a, now, drafts)
    }
}

/** The contents marked, unless a live upload announced one: `artifacts_blobs_marked`. */
internal fun blobsMarked(orgId: Uuid, now: Instant, limit: Int): Query {
    val b = ArtifactsBlobs
    return b.select(b.artifactId, b.sha256)
        .where { (b.orgId eq orgId) and b.markedAt.isNotNull() and notExists(announced(b, now)) }
        .orderBy(b.markedAt)
        .limit(limit)
}

/** The folders to delete: `artifacts_folder_deletions_org`. */
internal fun foldersDeleted(orgId: Uuid, limit: Int): Query {
    val f = ArtifactsFolderDeletions
    return f.select(f.artifactId).where { f.orgId eq orgId }.orderBy(f.deletedAt).limit(limit)
}

/** A content deleted from the bucket, still marked. */
internal fun StatementBuilder.blobForgotten(orgId: Uuid, artifactId: Uuid, sha256: String): DeleteStatement {
    val b = ArtifactsBlobs
    return b.deleteWhere {
        (b.artifactId eq artifactId) and (b.orgId eq orgId) and (b.sha256 eq sha256) and b.markedAt.isNotNull()
    }
}

/** A folder emptied from the bucket, forgotten. */
internal fun StatementBuilder.folderForgotten(orgId: Uuid, artifactId: Uuid): DeleteStatement {
    val f = ArtifactsFolderDeletions
    return f.deleteWhere { (f.artifactId eq artifactId) and (f.orgId eq orgId) }
}

/** The rows a `RETURNING` statement returns, once it has run. */
internal fun ReturningStatement.rows(): List<ResultRow> = ReturningBlockingExecutable(this).toList()
