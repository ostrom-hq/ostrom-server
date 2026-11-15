// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A cluster the garbage collection visits.
 *
 * @property id The cluster.
 * @property allowedNamespaces The namespaces its profiles may name.
 */
data class GarbageCluster(val id: String, val allowedNamespaces: List<String>)

/**
 * The objects of a cluster's sessions that the garbage collection keeps (TeamCode's `LiveObjects`).
 *
 * @property claims The workspace claims of the sessions not over, or whose claim deletion is not seen yet.
 * @property pods The pods of the current executions of the sessions holding one.
 */
data class LiveObjects(val claims: Set<String>, val pods: Set<String>)

/** The sessions' objects on a cluster, read across organizations for the garbage collection. */
interface LiveCloudObjectsRepository {
    /**
     * @param clusterId The cluster.
     * @return The namespaces of its sessions whose workspace deletion is not proven (TeamCode's `store.namespaces`): a
     *   namespace removed from the cluster's allowed ones still holds their objects.
     */
    suspend fun namespaces(clusterId: String): Set<String>

    /**
     * @param clusterId The cluster.
     * @return What its sessions account for.
     */
    suspend fun liveObjects(clusterId: String): LiveObjects
}

/** The instance's clusters, as the garbage collection lists them and records its report on them. */
interface GarbageClusters {
    /** @return Every cluster. */
    suspend fun clusters(): List<GarbageCluster>

    /**
     * Records a collection's report on the cluster (`orphanedVolumes`, `lastGarbageCollectionAt` of
     * `GET /api/v1/cloud/clusters`).
     *
     * @param clusterId The cluster.
     * @param orphanedVolumes The PersistentVolumes no provisioner deletes; null when they cannot be read.
     * @param at When the collection ran.
     */
    suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int?, at: Instant)
}

/**
 * The result of one garbage collection of a cluster; counts only, no object names leave the domain.
 *
 * @property deletedPods Session pods no session accounts for, deleted.
 * @property deletedClaims Workspace claims no session accounts for, deleted.
 * @property orphanedVolumes Workspace PersistentVolumes whose claim is gone and that their provisioner will not delete;
 *   null when the cluster does not let its PersistentVolumes be read (no ClusterRole).
 * @property skippedNamespaces Namespaces whose objects could not be listed or deleted (a role no longer granted
 *   there): the others are collected all the same.
 */
data class GarbageReport(
    val deletedPods: Int,
    val deletedClaims: Int,
    val orphanedVolumes: Int?,
    val skippedNamespaces: Int = 0,
)

/**
 * Deletes the session pods and workspace claims of a cluster that no session accounts for, and counts the provider
 * volumes left behind (a `Retain` reclaim policy, a failed volume) so that an administrator is told (TeamCode's
 * `CollectCloudGarbageUseCase`). Only objects labelled with the cluster's id are seen: those made before the label
 * are never deleted.
 */
fun interface CollectCloudGarbageUseCase {
    /**
     * Collects one cluster.
     *
     * @param cluster The cluster.
     *
     * @return What was deleted and counted.
     *
     * @throws RuntimeUnreachableException When the cluster does not open.
     */
    suspend operator fun invoke(cluster: GarbageCluster): GarbageReport
}

/**
 * [CollectCloudGarbageUseCase]. The cluster's objects are listed before the live set is read: an object is created only
 * after its session's row commits, so a fresh object is never mistaken for an orphan.
 *
 * @param store What the sessions account for.
 * @param objects The cluster's objects.
 * @param clusters Where the report is recorded.
 * @param clock The time.
 */
class CollectCloudGarbageUseCaseImpl(
    private val store: LiveCloudObjectsRepository,
    private val objects: CloudClusterObjects,
    private val clusters: GarbageClusters,
    private val clock: Clock,
) : CollectCloudGarbageUseCase {
    override suspend fun invoke(cluster: GarbageCluster): GarbageReport {
        // Namespaces removed from the cluster's allowed list still hold objects until their sessions are cleaned up.
        val namespaces = cluster.allowedNamespaces.toSet() + store.namespaces(cluster.id)
        val observed = namespaces.associateWith { listed(cluster.id, it) }
        val volumes = try {
            objects.orphanedVolumes(cluster.id)
        } catch (_: ClusterReadForbiddenException) {
            null
        }
        val live = store.liveObjects(cluster.id)
        var pods = 0
        var claims = 0
        var skipped = 0
        for ((namespace, managed) in observed) {
            val deleted = managed?.let { collected(cluster.id, namespace, it, live) }
            if (deleted == null) {
                skipped++
            } else {
                pods += deleted.first
                claims += deleted.second
            }
        }
        // A Delete-policy volume whose claim is gone is being removed by its provisioner; a Retain or failed one is
        // not. Only volumes whose claim lived in one of this cluster's namespaces count as its orphans.
        val orphaned = volumes?.count {
            it.claimNamespace in namespaces && it.claimName !in live.claims &&
                (it.reclaimPolicy != RECLAIM_DELETE || it.phase == "Failed")
        }
        clusters.recordGarbageCollection(cluster.id, orphaned, clock.now())
        return GarbageReport(pods, claims, orphaned, skipped)
    }

    /** A namespace's labelled objects; null when they cannot be listed there, the cluster's others collected. */
    private suspend fun listed(clusterId: String, namespace: String): ManagedObjects? = guarded {
        objects.managed(clusterId, namespace)
    }

    /** Deletes what no session accounts for: the pods and claims deleted, or null when a deletion fails there. */
    private suspend fun collected(
        clusterId: String,
        namespace: String,
        managed: ManagedObjects,
        live: LiveObjects,
    ): Pair<Int, Int>? = guarded {
        val pods = managed.pods.filter { it !in live.pods }
        val claims = managed.claims.filter { it !in live.claims }
        pods.forEach { objects.deletePod(clusterId, namespace, it) }
        claims.forEach { objects.deleteClaim(clusterId, namespace, it) }
        pods.size to claims.size
    }

    /** A namespace's step; a cluster that does not open stops the whole collection, any other failure only it. */
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeUnreachableException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
