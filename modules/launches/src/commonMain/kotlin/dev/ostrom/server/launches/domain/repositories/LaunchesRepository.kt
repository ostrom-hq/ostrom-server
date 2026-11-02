// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.repositories

import dev.ostrom.server.launches.domain.models.Launch
import kotlin.uuid.Uuid

/** Storage of the launches, isolated by organization. */
interface LaunchesRepository {
    /**
     * Stores a new launch.
     *
     * @param launch The launch.
     */
    suspend fun create(launch: Launch)

    /**
     * Reads a launch.
     *
     * @param orgId The organization.
     * @param id The launch id.
     *
     * @return The launch, or null when it is not one of the organization's.
     */
    suspend fun find(orgId: Uuid, id: String): Launch?
}
