// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What an artifact keeps after a publication or a change (TeamCode's pruning and `expires_at`). */
interface ArtifactLifecycleRepository {
    /**
     * Keeps the artifact's [versions] newest versions and deletes the older ones; marks for collection, at [now], the
     * contents of its folder no kept version uses (the maintenance deletes them from the bucket); and sets when it
     * expires.
     *
     * @param expiresAt When it expires, or null to keep it.
     */
    suspend fun retain(orgId: Uuid, artifactId: Uuid, versions: Int, expiresAt: Instant?, now: Instant)

    /** Sets when the artifact expires, or null to keep it. */
    suspend fun expire(orgId: Uuid, artifactId: Uuid, expiresAt: Instant?)
}
