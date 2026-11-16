// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactLifecycleRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.write
import org.jetbrains.exposed.v1.core.CustomOperator
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactLifecycleRepository] in one transaction of the artifact's organization: the versions pruned, the contents
 * no kept version uses marked (`artifacts_blobs.marked_at`), the expiry set.
 *
 * @param database The serving role's pool.
 */
internal class ArtifactLifecycleRepositoryImpl(private val database: JdbcDatabase) : ArtifactLifecycleRepository {
    override suspend fun retain(orgId: Uuid, artifactId: Uuid, versions: Int, expiresAt: Instant?, now: Instant) {
        database.inOrg(orgId) {
            write { versionsPrune(orgId, artifactId, versions) }
            markUnused(orgId, artifactId, now)
            write { expiryUpdate(orgId, artifactId, expiresAt) }
        }
    }

    override suspend fun expire(orgId: Uuid, artifactId: Uuid, expiresAt: Instant?) {
        database.inOrg(orgId) { write { expiryUpdate(orgId, artifactId, expiresAt) } }
    }

    override suspend fun busy(orgId: Uuid, artifactId: Uuid): Boolean =
        database.inOrg(orgId) { markedBlob(orgId, artifactId).any() }
}

/** Marks the folder's contents no kept version lists and no live upload announced, once (see [blobsMark]). */
internal fun JdbcTransaction.markUnused(orgId: Uuid, artifactId: Uuid, now: Instant) {
    write { blobsMark(orgId, artifactId, now) }
}

/** The versions older than the newest [keep]: the primary key `(artifact_id, number)`. */
internal fun StatementBuilder.versionsPrune(orgId: Uuid, artifactId: Uuid, keep: Int): DeleteStatement {
    val v = ArtifactsVersions
    val a = ArtifactsArtifacts
    val latest = a.select(a.latestVersion).where { (a.id eq artifactId) and (a.orgId eq orgId) }
    val kept = QueryParameter(keep, IntegerColumnType())
    val oldest = CustomOperator("-", IntegerColumnType(), wrapAsExpression<Int>(latest), kept)
    return v.deleteWhere { (v.artifactId eq artifactId) and (v.orgId eq orgId) and (v.number lessEq oldest) }
}

/** A content of the folder marked: the primary key `(artifact_id, sha256)`. */
internal fun markedBlob(orgId: Uuid, artifactId: Uuid): Query {
    val b = ArtifactsBlobs
    return b.select(intLiteral(1))
        .where { (b.artifactId eq artifactId) and (b.orgId eq orgId) and b.markedAt.isNotNull() }
        .limit(1)
}

/** The folder's contents no kept version lists and no live upload announced, marked once. */
internal fun StatementBuilder.blobsMark(orgId: Uuid, artifactId: Uuid, now: Instant): UpdateStatement {
    val b = ArtifactsBlobs
    return b.update({
        (b.artifactId eq artifactId) and (b.orgId eq orgId) and b.markedAt.isNull() and
            notExists(keptByVersion(b)) and notExists(announced(b, now))
    }) { it[markedAt] = now }
}

/** The versions that list the content of [blob]. */
private fun keptByVersion(blob: ArtifactsBlobs): Query {
    val v = ArtifactsVersions
    return v.select(intLiteral(1)).where { (v.artifactId eq blob.artifactId) and ListsFile(v.files, blob.sha256) }
}

/** The live uploads that announced the content of [blob]. */
internal fun announced(blob: ArtifactsBlobs, now: Instant): Query {
    val u = ArtifactsUploads
    val ub = ArtifactsUploadBlobs
    return u.join(ub, JoinType.INNER, ub.uploadId, u.id).select(intLiteral(1))
        .where { (u.artifactId eq blob.artifactId) and (u.expiresAt greater now) and (ub.sha256 eq blob.sha256) }
}

/** Sets an artifact's expiry (null: it never expires). */
internal fun StatementBuilder.expiryUpdate(orgId: Uuid, artifactId: Uuid, expiresAt: Instant?): UpdateStatement {
    val a = ArtifactsArtifacts
    return a.update({ (a.id eq artifactId) and (a.orgId eq orgId) }) { it[this.expiresAt] = expiresAt }
}

/**
 * Whether the JSON list of files [files] holds a file of content [sha256]: PostgreSQL's `files::jsonb @>
 * jsonb_build_array(jsonb_build_object('sha256', sha256))`, true for any element that has this `sha256` (the former
 * version unnested the list with `jsonb_array_elements`, which Exposed cannot join).
 */
private class ListsFile(private val files: Expression<String>, private val sha256: Expression<String>) :
    Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("CAST(")
        append(files)
        append(" AS JSONB) @> jsonb_build_array(jsonb_build_object('sha256', ")
        append(sha256)
        append("))")
    }
}
