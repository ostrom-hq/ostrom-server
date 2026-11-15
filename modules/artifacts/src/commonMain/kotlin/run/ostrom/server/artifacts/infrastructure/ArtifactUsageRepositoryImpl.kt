// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactUsage
import run.ostrom.server.artifacts.domain.ArtifactUsageReport
import run.ostrom.server.artifacts.domain.ArtifactUsageReports
import run.ostrom.server.artifacts.domain.ArtifactUsageRepository
import run.ostrom.server.artifacts.domain.MemberArtifactUsage
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.asBigint
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [ArtifactUsageRepository] on the folders' contents (`artifacts_blobs`, one row per distinct content of an artifact)
 * and the published artifacts, under the organization's Row-Level Security.
 *
 * @param database The serving role's pool.
 */
internal class ArtifactUsageRepositoryImpl(private val database: JdbcDatabase) :
    ArtifactUsageRepository,
    ArtifactUsageReports {
    override suspend fun usage(orgId: Uuid, ownerId: String): ArtifactUsage = database.inOrg(orgId) {
        val bytes = usageBytes(orgId, ownerId).single()
        val owned = ownedArtifacts(orgId, ownerId).single()
        ArtifactUsage(bytes.owner, bytes.org, owned)
    }

    override suspend fun report(orgId: Uuid, limit: Int): ArtifactUsageReport = database.inOrg(orgId) {
        ArtifactUsageReport(publishedBytes(orgId).single(), usageOwners(orgId, limit).rows())
    }
}

/** The bytes of an owner's folders and of the organization's. */
internal data class UsageBytes(val owner: Long, val org: Long)

/** The bytes of the owner's folders and of the organization's: `artifacts_blobs_org`. */
internal fun usageBytes(orgId: Uuid, ownerId: String): Read<UsageBytes> {
    val a = ArtifactsArtifacts
    val b = ArtifactsBlobs
    val org = coalesce(b.size.sum(), longLiteral(0)).asBigint()
    val owner = coalesce(Sum(case().When(a.ownerId eq ownerId, b.size), LongColumnType()), longLiteral(0)).asBigint()
    val query = b.join(a, JoinType.INNER, a.id, b.artifactId).select(org, owner).where { b.orgId eq orgId }
    return Read(query) { UsageBytes(it[owner], it[org]) }
}

/** The bytes of the organization's published artifacts: `artifacts_blobs_org`, drafts left out. */
internal fun publishedBytes(orgId: Uuid): Read<Long> {
    val a = ArtifactsArtifacts
    val b = ArtifactsBlobs
    val bytes = coalesce(b.size.sum(), longLiteral(0)).asBigint()
    val query = b.join(a, JoinType.INNER, a.id, b.artifactId).select(bytes)
        .where { (b.orgId eq orgId) and (a.latestVersion greater 0) }
    return Read(query) { it[bytes] }
}

/** The largest owners of published artifacts: `artifacts_artifacts_owner` and the folders' primary key. */
internal fun usageOwners(orgId: Uuid, limit: Int): Read<MemberArtifactUsage> {
    val a = ArtifactsArtifacts
    val b = ArtifactsBlobs
    val artifacts = a.id.countDistinct()
    val bytes: Expression<Long> = coalesce(b.size.sum(), longLiteral(0)).asBigint()
    val query = a.join(b, JoinType.LEFT, b.artifactId, a.id).select(a.ownerId, artifacts, bytes)
        .where { (a.orgId eq orgId) and (a.latestVersion greater 0) }
        .groupBy(a.ownerId)
        .orderBy(bytes to SortOrder.DESC, a.ownerId to SortOrder.ASC)
        .limit(limit)
    return Read(query) { MemberArtifactUsage(it[a.ownerId], it[artifacts].toInt(), it[bytes]) }
}

/** The owner's published artifacts: `artifacts_artifacts_owner`. */
internal fun ownedArtifacts(orgId: Uuid, ownerId: String): Read<Int> {
    val a = ArtifactsArtifacts
    val owned = a.id.count()
    val query = a.select(owned)
        .where { (a.orgId eq orgId) and (a.ownerId eq ownerId) and (a.latestVersion greater 0) }
    return Read(query) { it[owned].toInt() }
}
