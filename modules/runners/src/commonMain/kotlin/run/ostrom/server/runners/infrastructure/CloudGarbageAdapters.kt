// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.StorageClassLookup
import run.ostrom.server.cloud.domain.WorkspaceStorageClasses
import run.ostrom.server.runners.domain.CloudClusterObjects
import run.ostrom.server.runners.domain.ClusterReadForbiddenException
import run.ostrom.server.runners.domain.GarbageCluster
import run.ostrom.server.runners.domain.GarbageClusters
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GarbageClusters] on the console's clusters (cloud module).
 *
 * @param clusters The stored clusters.
 */
class CloudGarbageClusters(private val clusters: CloudClustersRepository) : GarbageClusters {
    override suspend fun clusters(): List<GarbageCluster> =
        clusters.list().map { GarbageCluster(it.id.toString(), it.allowedNamespaces) }

    override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int?, at: Instant) {
        val id = Uuid.parseOrNull(clusterId) ?: return
        clusters.recordGarbageCollection(id, orphanedVolumes, at)
    }
}

/**
 * [WorkspaceStorageClasses] on the clusters the Kubernetes driver reaches: a class that cannot be read now (the cluster
 * does not open, or refuses the read) is [StorageClassLookup.Unknown], checked again at each session's first start.
 *
 * @param objects The clusters' objects; null where sessions do not run on Kubernetes.
 */
class ClusterWorkspaceStorageClasses(private val objects: CloudClusterObjects?) : WorkspaceStorageClasses {
    override suspend fun find(clusterId: Uuid, name: String?): StorageClassLookup {
        val reader = objects ?: return StorageClassLookup.Unknown
        return try {
            val found = reader.storageClass(clusterId.toString(), name)
            found?.let { StorageClassLookup.Found(it.name, it.reclaimPolicy) } ?: StorageClassLookup.Missing
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeUnreachableException) {
            StorageClassLookup.Unknown
        } catch (_: ClusterReadForbiddenException) {
            StorageClassLookup.Unknown
        } catch (_: KubernetesApiException) {
            StorageClassLookup.Unknown
        }
    }
}
