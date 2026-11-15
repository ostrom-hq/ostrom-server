// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

/**
 * The cluster-wide reads and deletions of the garbage collection and of a profile's storage class check (the part of
 * TeamCode's `ClusterPort` they use). Only Kubernetes has them.
 */
interface CloudClusterObjects {
    /**
     * A storage class of the cluster.
     *
     * @param clusterId The cluster.
     * @param name The class; null for the cluster's default, as the API server would pick it for a claim.
     *
     * @return It, or null when there is no such class (or no default).
     *
     * @throws RuntimeUnreachableException When the cluster does not open.
     */
    suspend fun storageClass(clusterId: String, name: String?): StorageClassObservation?

    /**
     * The session pods and workspace claims this server made on the cluster, in a namespace: only those labelled
     * with the cluster's id, so objects made before the label are never collected.
     *
     * @param clusterId The cluster.
     * @param namespace The namespace.
     *
     * @return Their names.
     */
    suspend fun managed(clusterId: String, namespace: String): ManagedObjects

    /**
     * The PersistentVolumes of session workspaces that are `Released` or `Failed`: their claim is gone.
     *
     * @param clusterId The cluster.
     *
     * @return Them.
     */
    suspend fun orphanedVolumes(clusterId: String): List<VolumeObservation>

    /**
     * Deletes a pod at once; one already gone is a success.
     *
     * @param clusterId The cluster.
     * @param namespace Its namespace.
     * @param name The pod.
     */
    suspend fun deletePod(clusterId: String, namespace: String, name: String)

    /**
     * Deletes a claim; one already gone is a success.
     *
     * @param clusterId The cluster.
     * @param namespace Its namespace.
     * @param name The claim.
     */
    suspend fun deleteClaim(clusterId: String, namespace: String, name: String)
}

/**
 * The session objects of one namespace (TeamCode's `ManagedObjects`).
 *
 * @property pods The pods' names.
 * @property claims The workspace claims' names.
 */
data class ManagedObjects(val pods: List<String>, val claims: List<String>)
