// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactUsage
import run.ostrom.server.artifacts.domain.ArtifactUsageRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.uuid.Uuid

/**
 * [ArtifactUsageRepository] on the folders' contents (`artifacts_blobs`, one row per distinct content of an artifact)
 * and the published artifacts, under the organization's Row-Level Security.
 *
 * @param driver The database.
 */
internal class ArtifactUsageRepositoryImpl(private val driver: Driver) : ArtifactUsageRepository {
    override suspend fun usage(orgId: Uuid, ownerId: String): ArtifactUsage = driver.inTenant(orgId) {
        val bytes = fetchAll(
            Statement.create(UsageQueries.BYTES).bind("org_id", orgId).bind("owner", ownerId),
        ).getOrThrow().single()
        val artifacts = fetchAll(
            Statement.create(UsageQueries.ARTIFACTS).bind("org_id", orgId).bind("owner", ownerId),
        ).getOrThrow().single()
        val owned = artifacts.get("n").asLong().toInt()
        ArtifactUsage(bytes.get("owner_bytes").asLong(), bytes.get("org_bytes").asLong(), owned)
    }
}

/** The request path's reads of the usage, for their plan test. */
internal object UsageQueries {
    /** The bytes of the owner's folders and of the organization's: `artifacts_blobs_org`. */
    const val BYTES = "SELECT CAST(COALESCE(SUM(b.size), 0) AS BIGINT) AS org_bytes, " +
        "CAST(COALESCE(SUM(b.size) FILTER (WHERE a.owner_id = :owner), 0) AS BIGINT) AS owner_bytes " +
        "FROM artifacts_blobs b JOIN artifacts_artifacts a ON a.id = b.artifact_id WHERE b.org_id = :org_id"

    /** The owner's published artifacts: `artifacts_artifacts_owner`. */
    const val ARTIFACTS = "SELECT COUNT(*) AS n FROM artifacts_artifacts WHERE org_id = :org_id AND " +
        "owner_id = :owner AND latest_version > 0"
}
