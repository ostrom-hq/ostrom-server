// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import kotlin.uuid.Uuid

/**
 * The storage classes of the instance's clusters, read on the cluster where cloud sessions run on Kubernetes (the
 * runners module binds it): a profile's workspace storage class is checked when it is published, as TeamCode's.
 */
fun interface WorkspaceStorageClasses {
    /**
     * Reads a storage class on a cluster.
     *
     * @param clusterId The cluster.
     * @param name The class; null for the cluster's default.
     *
     * @return What the cluster says of it.
     */
    suspend fun find(clusterId: Uuid, name: String?): StorageClassLookup
}

/** What a cluster says of a storage class. */
sealed interface StorageClassLookup {
    /**
     * The class exists.
     *
     * @property name Its name.
     * @property reclaimPolicy What happens to a volume once its claim is deleted: only `Delete` deletes the provider's.
     */
    data class Found(val name: String, val reclaimPolicy: String) : StorageClassLookup

    /** There is no such class (or no default). */
    data object Missing : StorageClassLookup

    /** It could not be read: the cluster does not open, or sessions do not run on Kubernetes. Not checked then. */
    data object Unknown : StorageClassLookup
}
