// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import kotlin.uuid.Uuid

/**
 * What an owner and their organization hold, against the quotas.
 *
 * @property ownerBytes The bytes of the owner's artifacts' folders.
 * @property orgBytes The bytes of every folder of the organization.
 * @property ownerArtifacts The owner's published artifacts (a draft does not count).
 */
data class ArtifactUsage(val ownerBytes: Long, val orgBytes: Long, val ownerArtifacts: Int)

/** Reads the usage the quotas are checked on. */
fun interface ArtifactUsageRepository {
    /** The usage of [ownerId] and of [orgId]. */
    suspend fun usage(orgId: Uuid, ownerId: String): ArtifactUsage
}
