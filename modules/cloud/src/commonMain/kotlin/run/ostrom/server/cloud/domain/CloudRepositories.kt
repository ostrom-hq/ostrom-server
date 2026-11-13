// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The instance's cloud runtime, one row (`cloud_runtime`). */
interface CloudRuntimeRepository {
    /**
     * The stored runtime.
     *
     * @return It, or null while nothing is stored.
     */
    suspend fun get(): CloudRuntime?

    /**
     * Stores [runtime] when the stored one still carries [expectedUpdatedAt] (null: nothing stored yet).
     *
     * @param runtime The runtime, its `updatedAt` the new one.
     * @param expectedUpdatedAt The `updatedAt` of the runtime edited.
     * @param by Who saved it; null for the one-time seed.
     *
     * @return False when another save came first.
     */
    suspend fun save(runtime: CloudRuntime, expectedUpdatedAt: Instant?, by: Uuid?): Boolean
}

/** A cluster as stored: its sealed credentials beside it. */
data class StoredCloudCluster(
    /** The cluster. */
    val cluster: CloudCluster,
    /** The CA bundle sealed with the secrets key. */
    val sealedCaCertificate: String,
    /** The token sealed with the secrets key. */
    val sealedServiceAccountToken: String,
) {
    override fun toString(): String = "StoredCloudCluster(cluster=${cluster.id}, [SEALED])"
}

/** The instance's clusters (`cloud_clusters`). */
interface CloudClustersRepository {
    /**
     * Every cluster.
     *
     * @return They, by name.
     */
    suspend fun list(): List<CloudCluster>

    /**
     * One cluster with its sealed credentials.
     *
     * @param id The cluster.
     *
     * @return It, or null.
     */
    suspend fun get(id: Uuid): StoredCloudCluster?

    /**
     * Inserts a new cluster.
     *
     * @param stored It, at version 1.
     *
     * @return False when a cluster of that name exists.
     */
    suspend fun insert(stored: StoredCloudCluster): Boolean

    /**
     * Replaces a cluster when it is still at the version before.
     *
     * @param stored It, at its new version.
     *
     * @return False when another save came first or the name is taken.
     */
    suspend fun update(stored: StoredCloudCluster): Boolean
}

/** The organizations' profiles and their immutable versions (`cloud_profiles`, `cloud_profile_versions`). */
interface CloudProfilesRepository {
    /**
     * The organization's profiles at their current version.
     *
     * @param orgId The organization.
     *
     * @return They, by name.
     */
    suspend fun list(orgId: Uuid): List<CloudProfile>

    /**
     * One profile of the organization.
     *
     * @param orgId The organization.
     * @param id The profile.
     *
     * @return It, or null when it is none of the organization's.
     */
    suspend fun get(orgId: Uuid, id: Uuid): CloudProfile?

    /**
     * Publishes [profile] at its version: a new profile at 1, or a new version over the one before.
     *
     * @param profile The profile at its new version.
     *
     * @return False when another save came first.
     */
    suspend fun publish(profile: CloudProfile): Boolean
}
