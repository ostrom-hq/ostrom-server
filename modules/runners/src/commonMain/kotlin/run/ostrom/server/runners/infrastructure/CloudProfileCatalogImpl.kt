// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudClusterAccess
import run.ostrom.server.cloud.domain.CloudProfile
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.runners.domain.CloudProfileCatalog
import run.ostrom.server.runners.domain.ProfileCluster
import run.ostrom.server.runners.domain.ProfileOption
import run.ostrom.server.runners.domain.SessionResources
import kotlin.uuid.Uuid

/**
 * [CloudProfileCatalog] on the cloud module: the organization's profiles at their current version, and a cluster
 * only when its credentials open ([CloudClusterAccess.credentials]).
 *
 * @param profiles The organizations' profiles.
 * @param clusters The clusters' credentials.
 */
class CloudProfileCatalogImpl(
    private val profiles: CloudProfilesRepository,
    private val clusters: CloudClusterAccess,
) : CloudProfileCatalog {
    override suspend fun profiles(orgId: Uuid): List<ProfileOption> = profiles.list(orgId).map { it.toOption() }

    override suspend fun profile(orgId: Uuid, id: Uuid): ProfileOption? = profiles.get(orgId, id)?.toOption()

    override suspend fun cluster(id: String): ProfileCluster? {
        val uuid = Uuid.parseOrNull(id) ?: return null
        val cluster = clusters.credentials(uuid)?.cluster
        return cluster?.let { ProfileCluster(it.allowedNamespaces, it.defaultStorageClass) }
    }

    private fun CloudProfile.toOption() = ProfileOption(
        id = id,
        version = version,
        assignedMemberIds = assignedMemberIds,
        clusterId = spec.clusterId,
        namespace = spec.namespace,
        image = spec.image,
        allowedImageOverrides = spec.allowedImageOverrides,
        imagePullSecrets = spec.imagePullSecrets,
        resources = SessionResources(spec.resources.cpuMillis, spec.resources.memoryMebibytes),
        workspaceGibibytes = spec.workspaceSizeGibibytes,
        storageClass = spec.storageClass,
    )
}
