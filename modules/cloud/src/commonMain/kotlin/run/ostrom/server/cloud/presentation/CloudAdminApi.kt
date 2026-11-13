// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.presentation

import run.ostrom.server.cloud.domain.CloudProfileSpec
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET` and `PUT /api/v1/cloud/runtime` (`getCloudRuntime`, `saveCloudRuntime`). */
@Serializable
@Resource("/api/v1/cloud/runtime")
class CloudRuntimeApi

/** `GET` and `POST /api/v1/cloud/clusters` (`listCloudClusters`, `createCloudCluster`). */
@Serializable
@Resource("/api/v1/cloud/clusters")
class CloudClustersApi {
    /**
     * `PUT /api/v1/cloud/clusters/{id}` (`updateCloudCluster`).
     *
     * @property parent The clusters.
     * @property id The cluster.
     */
    @Serializable
    @Resource("{id}")
    class One(val parent: CloudClustersApi = CloudClustersApi(), val id: String)
}

/** `GET` and `POST /api/v1/cloud/profiles` (`listCloudProfiles`, `createCloudProfile`). */
@Serializable
@Resource("/api/v1/cloud/profiles")
class CloudProfilesApi {
    /**
     * `PUT /api/v1/cloud/profiles/{id}` (`updateCloudProfile`).
     *
     * @property parent The profiles.
     * @property id The profile.
     */
    @Serializable
    @Resource("{id}")
    class One(val parent: CloudProfilesApi = CloudProfilesApi(), val id: String)
}

/** `CloudRuntime`: the runtime with the URL pods call back. */
@Serializable
data class CloudRuntimeResponse(
    /** Whether new sessions start. */
    val enabled: Boolean,
    /** The delivery image of the binary. */
    val runnerImage: String? = null,
    /** Where the binary is in it. */
    val runnerBinary: String,
    /** The stored callback origin. */
    val serverUrl: String? = null,
    /** The origin pods call back. */
    val effectiveServerUrl: String,
    /** The workspace claim's access mode. */
    val workspaceAccessMode: String,
    /** Days a paused workspace is kept. */
    val pausedRetentionDays: Int? = null,
    /** Days a failed workspace is kept. */
    val failedWorkspaceRetentionDays: Int,
    /** Sessions a member runs at once. */
    val maxRunningSessionsPerMember: Int,
    /** Sessions a member keeps. */
    val maxRetainedSessionsPerMember: Int,
    /** Days transcript events are kept. */
    val transcriptRetentionDays: Int? = null,
    /** `offer` or `disabled`. */
    val memberTransfers: String,
    /** When it was saved last. */
    val updatedAt: String? = null,
)

/** `SaveCloudRuntimeRequest`: the whole runtime and the `updatedAt` it replaces. */
@Serializable
data class SaveCloudRuntimeRequest(
    /** Whether new sessions start. */
    val enabled: Boolean,
    /** The delivery image of the binary. */
    val runnerImage: String? = null,
    /** Where the binary is in it. */
    val runnerBinary: String,
    /** The callback origin. */
    val serverUrl: String? = null,
    /** The workspace claim's access mode. */
    val workspaceAccessMode: String,
    /** Days a paused workspace is kept. */
    val pausedRetentionDays: Int? = null,
    /** Days a failed workspace is kept. */
    val failedWorkspaceRetentionDays: Int,
    /** Sessions a member runs at once. */
    val maxRunningSessionsPerMember: Int,
    /** Sessions a member keeps. */
    val maxRetainedSessionsPerMember: Int,
    /** Days transcript events are kept. */
    val transcriptRetentionDays: Int? = null,
    /** `offer` or `disabled`. */
    val memberTransfers: String,
    /** The `updatedAt` of the runtime edited. */
    val updatedAt: String? = null,
)

/** `InstanceSecretState`. */
@Serializable
data class CloudSecretStateResponse(
    /** Whether it is set. */
    val configured: Boolean,
)

/** `CloudCluster`: never its credentials. */
@Serializable
data class CloudClusterResponse(
    /** The cluster. */
    val id: String,
    /** Its name. */
    val name: String,
    /** Its API server. */
    val apiUrl: String,
    /** The namespaces profiles may name. */
    val allowedNamespaces: List<String>,
    /** Pods it runs at once. */
    val maxRunningPods: Int,
    /** Its default storage class. */
    val defaultStorageClass: String? = null,
    /** Its version. */
    val version: Int,
    /** Whether its CA bundle is set. */
    val caCertificate: CloudSecretStateResponse,
    /** Whether its token is set. */
    val serviceAccountToken: CloudSecretStateResponse,
    /** When it was connected. */
    val createdAt: String,
    /** When it was changed last. */
    val updatedAt: String,
)

/** `CloudClusterList`. */
@Serializable
data class CloudClusterListResponse(
    /** They, by name. */
    val clusters: List<CloudClusterResponse>,
)

/** `SaveCloudClusterRequest`. */
@Serializable
data class SaveCloudClusterRequest(
    /** Its name. */
    val name: String,
    /** Its API server. */
    val apiUrl: String,
    /** The PEM bundle; absent keeps it. */
    val caCertificate: String? = null,
    /** The token; absent keeps it. */
    val serviceAccountToken: String? = null,
    /** The namespaces profiles may name. */
    val allowedNamespaces: List<String>,
    /** Pods it runs at once. */
    val maxRunningPods: Int,
    /** Its default storage class. */
    val defaultStorageClass: String? = null,
    /** The version replaced. */
    val version: Int,
) {
    override fun toString(): String = "SaveCloudClusterRequest(name=$name, apiUrl=$apiUrl, [REDACTED])"
}

/** `CloudProfile`. */
@Serializable
data class CloudProfileResponse(
    /** The profile. */
    val id: String,
    /** Its name. */
    val name: String,
    /** Its current version. */
    val version: Int,
    /** That version's spec. */
    val spec: CloudProfileSpec,
    /** The members allowed to use it. */
    val assignedMemberIds: List<String>,
    /** The cluster's name. */
    val clusterName: String? = null,
    /** When it was created. */
    val createdAt: String,
    /** When its version was published. */
    val updatedAt: String,
)

/** `CloudProfileList`. */
@Serializable
data class CloudProfileListResponse(
    /** They, by name. */
    val profiles: List<CloudProfileResponse>,
)

/** `SaveCloudProfileRequest`. */
@Serializable
data class SaveCloudProfileRequest(
    /** Its name. */
    val name: String,
    /** The version replaced. */
    val version: Int,
    /** The new version's spec. */
    val spec: CloudProfileSpec,
    /** The members allowed to use it. */
    val assignedMemberIds: List<String> = emptyList(),
)
