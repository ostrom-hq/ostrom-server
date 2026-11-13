// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.domain.repositories

import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchLimit
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Storage of the launches, isolated by organization. */
interface LaunchesRepository {
    /**
     * Stores a new launch unless its person or its organization already launched as many as [limit] allows: the count
     * and the insert hold the organization's lock, so two launches at once never both take the last place.
     *
     * @param launch The launch.
     * @param limit The limits, counted back from the launch's creation.
     *
     * @return Null when stored; otherwise how long until a place frees up, and nothing is stored.
     */
    suspend fun create(launch: Launch, limit: LaunchLimit): Duration?

    /**
     * Reads a launch.
     *
     * @param orgId The organization.
     * @param id The launch id.
     *
     * @return The launch, or null when it is not one of the organization's.
     */
    suspend fun find(orgId: Uuid, id: String): Launch?

    /**
     * Deletes the launches expired before [before], of every organization: none of them acts any more.
     *
     * @param before The expiry before which a launch is deleted.
     *
     * @return How many were deleted.
     */
    suspend fun purge(before: Instant): Long
}
