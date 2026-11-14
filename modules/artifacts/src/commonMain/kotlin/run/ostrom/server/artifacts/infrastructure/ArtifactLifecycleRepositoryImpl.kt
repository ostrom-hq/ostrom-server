// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.ArtifactLifecycleRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactLifecycleRepository] in one transaction of the artifact's organization: the versions pruned, the contents
 * no kept version uses marked (`artifacts_blobs.marked_at`), the expiry set.
 *
 * @param driver The database.
 */
internal class ArtifactLifecycleRepositoryImpl(private val driver: Driver) : ArtifactLifecycleRepository {
    override suspend fun retain(orgId: Uuid, artifactId: Uuid, versions: Int, expiresAt: Instant?, now: Instant) {
        driver.inTenant(orgId) {
            execute(Statement.create(LifecycleQueries.PRUNE).bind("id", artifactId).bind("keep", versions)).getOrThrow()
            execute(Statement.create(LifecycleQueries.MARK).bind("id", artifactId).bind("now", now)).getOrThrow()
            execute(expiry(artifactId, expiresAt)).getOrThrow()
        }
    }

    override suspend fun expire(orgId: Uuid, artifactId: Uuid, expiresAt: Instant?) {
        driver.inTenant(orgId) { execute(expiry(artifactId, expiresAt)).getOrThrow() }
    }

    private fun expiry(artifactId: Uuid, expiresAt: Instant?) =
        Statement.create("UPDATE artifacts_artifacts SET expires_at = :expires WHERE id = :id")
            .bind("id", artifactId).bind("expires", expiresAt)
}

/** The statements of a publication's retention. */
internal object LifecycleQueries {
    /** The versions older than the newest [keep]: the primary key `(artifact_id, number)`. */
    const val PRUNE = "DELETE FROM artifacts_versions WHERE artifact_id = :id AND number <= " +
        "(SELECT latest_version FROM artifacts_artifacts WHERE id = :id) - :keep"

    /** The folder's contents no kept version lists, marked once. */
    const val MARK = "UPDATE artifacts_blobs b SET marked_at = :now WHERE b.artifact_id = :id AND " +
        "b.marked_at IS NULL AND NOT EXISTS (SELECT 1 FROM artifacts_versions v, " +
        "jsonb_array_elements(CAST(v.files AS jsonb)) f WHERE v.artifact_id = b.artifact_id AND " +
        "f ->> 'sha256' = b.sha256)"
}
