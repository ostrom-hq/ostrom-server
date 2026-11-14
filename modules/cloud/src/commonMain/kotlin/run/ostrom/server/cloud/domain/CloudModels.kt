// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * How cloud sessions run on this instance, as TeamCode's `CloudRuntime` and `CloudSettings`: administrators edit it in
 * the console; the database is the source of truth, the former `OSTROM_RUNNERS_*` variables only seed it once.
 *
 * @property enabled Whether new cloud sessions start; off refuses only new work, running ones go on.
 * @property runnerImage The delivery image holding the `ostrom` Linux binary at [runnerBinary], copied into each
 *   session pod by an init container; null before an administrator sets it.
 * @property runnerBinary Where the binary is in [runnerImage].
 * @property serverUrl The https origin session pods call back; null uses the server's public URL.
 * @property workspaceAccessMode `ReadWriteOncePod`, or `ReadWriteOnce` for storage drivers without it.
 * @property pausedRetentionDays Days a paused session keeps its workspace; null keeps it.
 * @property failedWorkspaceRetentionDays Days a failed session keeps its workspace.
 * @property maxRunningSessionsPerMember Sessions one member runs at once.
 * @property maxRetainedSessionsPerMember Sessions one member keeps.
 * @property transcriptRetentionDays Days transcript events are kept; null keeps them.
 * @property memberTransfers `offer`, or `disabled` (administrators only).
 * @property updatedAt When it was saved last; null while nothing is stored.
 */
@Serializable
data class CloudRuntime(
    val enabled: Boolean = false,
    val runnerImage: String? = null,
    val runnerBinary: String = DEFAULT_RUNNER_BINARY,
    val serverUrl: String? = null,
    val workspaceAccessMode: String = DEFAULT_ACCESS_MODE,
    val pausedRetentionDays: Int? = DEFAULT_PAUSED_RETENTION_DAYS,
    val failedWorkspaceRetentionDays: Int = DEFAULT_FAILED_RETENTION_DAYS,
    val maxRunningSessionsPerMember: Int = DEFAULT_MAX_RUNNING,
    val maxRetainedSessionsPerMember: Int = DEFAULT_MAX_RETAINED,
    val transcriptRetentionDays: Int? = DEFAULT_TRANSCRIPT_RETENTION_DAYS,
    val memberTransfers: String = TRANSFERS_OFFER,
    val updatedAt: Instant? = null,
) {
    /** The defaults and the accepted values. */
    companion object {
        /** Where the CLI release's runner image keeps the binary. */
        const val DEFAULT_RUNNER_BINARY: String = "/usr/local/bin/ostrom"

        /** The access mode of a workspace claim by default. */
        const val DEFAULT_ACCESS_MODE: String = "ReadWriteOncePod"

        /** The access modes accepted. */
        val ACCESS_MODES: Set<String> = setOf("ReadWriteOncePod", "ReadWriteOnce")

        /** Owners may offer their sessions to another member. */
        const val TRANSFERS_OFFER: String = "offer"

        /** The transfer settings accepted. */
        val TRANSFERS: Set<String> = setOf(TRANSFERS_OFFER, "disabled")

        private const val DEFAULT_PAUSED_RETENTION_DAYS = 30
        private const val DEFAULT_FAILED_RETENTION_DAYS = 7
        private const val DEFAULT_MAX_RUNNING = 2
        private const val DEFAULT_MAX_RETAINED = 20
        private const val DEFAULT_TRANSCRIPT_RETENTION_DAYS = 30
    }
}

/**
 * A connected Kubernetes cluster (TeamCode's `CloudCluster`). Its CA bundle and service-account token stay sealed in
 * the database: the domain only knows whether they are set.
 *
 * @property id The cluster.
 * @property name Its name.
 * @property apiUrl Its API server, an https URL that is never a link-local or metadata address.
 * @property allowedNamespaces The namespaces profiles may name.
 * @property maxRunningPods Pods it runs at once.
 * @property defaultStorageClass The storage class of a profile that names none; null for the cluster's default.
 * @property version Its version, for the optimistic save.
 * @property hasCaCertificate Whether a CA bundle is stored.
 * @property hasServiceAccountToken Whether a token is stored.
 * @property createdAt When it was connected.
 * @property updatedAt When it was changed last.
 * @property inCluster Whether it is the server's own cluster, reached with the server pod's mounted service account:
 *   its token and CA are read from the pod's files at each use (the kubelet rotates them), nothing is stored. Only
 *   the cluster imported from the former `OSTROM_RUNNERS_KUBERNETES_*` variables is; saving a CA and a token turns
 *   it into a stored cluster.
 * @property imported Whether it is the cluster imported from the former variables, the one the sessions created before
 *   cloud profiles run on (never shown: an administrator may rename it).
 */
data class CloudCluster(
    val id: Uuid,
    val name: String,
    val apiUrl: String,
    val allowedNamespaces: List<String>,
    val maxRunningPods: Int,
    val defaultStorageClass: String?,
    val version: Int,
    val hasCaCertificate: Boolean,
    val hasServiceAccountToken: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val inCluster: Boolean = false,
    val imported: Boolean = false,
)

/**
 * A cluster as an administrator saves it (TeamCode's `CloudClusterInput`).
 *
 * @property name Its name.
 * @property apiUrl Its API server.
 * @property caCertificate The PEM bundle; null keeps the stored one (not when [apiUrl] changes).
 * @property serviceAccountToken The bearer token; null keeps the stored one (not when [apiUrl] changes).
 * @property allowedNamespaces The namespaces profiles may name.
 * @property maxRunningPods Pods it runs at once.
 * @property defaultStorageClass The default storage class, or null.
 * @property version The version replaced, 0 for a new cluster.
 */
data class CloudClusterInput(
    val name: String,
    val apiUrl: String,
    val caCertificate: String?,
    val serviceAccountToken: String?,
    val allowedNamespaces: List<String>,
    val maxRunningPods: Int,
    val defaultStorageClass: String?,
    val version: Int,
) {
    override fun toString(): String =
        "CloudClusterInput(name=$name, apiUrl=$apiUrl, caCertificate=[REDACTED], serviceAccountToken=[REDACTED])"
}

/**
 * What the runtime driver needs to reach a cluster, opened from its sealed values; never logged.
 *
 * @property cluster The cluster.
 * @property caCertificate Its PEM bundle.
 * @property serviceAccountToken Its bearer token.
 */
class CloudClusterCredentials(
    val cluster: CloudCluster,
    val caCertificate: String,
    val serviceAccountToken: String,
) {
    override fun toString(): String = "CloudClusterCredentials(cluster=${cluster.id}, [REDACTED])"
}

/**
 * The resources of a session pod.
 *
 * @property cpuMillis CPU, in thousandths of a core.
 * @property memoryMebibytes Memory, in MiB.
 */
@Serializable
data class CloudResources(val cpuMillis: Int = DEFAULT_CPU, val memoryMebibytes: Int = DEFAULT_MEMORY)

private const val DEFAULT_CPU = 1000
private const val DEFAULT_MEMORY = 2048

/**
 * What a session of a profile runs (TeamCode's `CloudProfileSpec`); a version of it never changes once published.
 *
 * @property clusterId The cluster.
 * @property namespace One of the cluster's allowed namespaces.
 * @property image The session image the team chose: Ostrom imposes none.
 * @property allowedImageOverrides Images a member may ask for instead: an exact reference or a prefix ending in `*`.
 * @property imagePullSecrets Names of Secrets of the namespace.
 * @property resources The pod's resources.
 * @property workspaceSizeGibibytes The workspace claim's size.
 * @property storageClass The claim's storage class; null for the cluster's default.
 * @property agentKind The agent run.
 * @property agentVersion Its version, or null for the image's.
 * @property idleTimeoutMinutes Minutes without activity before the session pauses.
 * @property minIdleTimeoutMinutes The least a member may ask.
 * @property maxIdleTimeoutMinutes The most a member may ask.
 */
@Serializable
data class CloudProfileSpec(
    val clusterId: String,
    val namespace: String,
    val image: String,
    val allowedImageOverrides: List<String> = emptyList(),
    val imagePullSecrets: List<String> = emptyList(),
    val resources: CloudResources = CloudResources(),
    val workspaceSizeGibibytes: Int = DEFAULT_WORKSPACE,
    val storageClass: String? = null,
    val agentKind: String = "claude",
    val agentVersion: String? = null,
    val idleTimeoutMinutes: Int = DEFAULT_IDLE,
    val minIdleTimeoutMinutes: Int = DEFAULT_MIN_IDLE,
    val maxIdleTimeoutMinutes: Int = DEFAULT_MAX_IDLE,
)

private const val DEFAULT_WORKSPACE = 10
private const val DEFAULT_IDLE = 30
private const val DEFAULT_MIN_IDLE = 5
private const val DEFAULT_MAX_IDLE = 1440

/**
 * A profile of an organization at its current version (TeamCode's `CloudProfile`).
 *
 * @property id The profile.
 * @property orgId Its organization.
 * @property name Its name.
 * @property version Its current version.
 * @property spec That version's spec.
 * @property assignedMemberIds The members allowed to use it; owners and admins always may.
 * @property createdAt When it was created.
 * @property updatedAt When its current version was published.
 */
data class CloudProfile(
    val id: Uuid,
    val orgId: Uuid,
    val name: String,
    val version: Int,
    val spec: CloudProfileSpec,
    val assignedMemberIds: Set<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A profile as an owner or an admin saves it.
 *
 * @property name Its name.
 * @property version The version replaced, 0 for a new profile.
 * @property spec The new version's spec.
 * @property assignedMemberIds The members allowed to use it.
 */
data class CloudProfileInput(
    val name: String,
    val version: Int,
    val spec: CloudProfileSpec,
    val assignedMemberIds: Set<String>,
)

/**
 * An organization's cloud session settings (TeamCode's team `cloud_settings`), edited by its owners and admins in the
 * console; the defaults apply while nothing is stored.
 *
 * @property backgroundWorkMaxMinutes How long the agent's background work (background subagents and commands,
 *   monitors, scheduled wake-ups) holds off the idle pause, from the start of its unbroken stretch; 0 never holds it
 *   off (TeamCode af1a5f7b).
 * @property maxRunningAutomationsPerMember How many cloud sessions started by a member's automations may work at
 *   once, per owner in this organization; a run over it waits queued (up to 30 minutes). A run started by a workflow
 *   action does not count (TeamCode's team setting).
 * @property updatedAt When it was saved last; null while nothing is stored.
 * @property updatedBy The member who saved it last; null while nothing is stored.
 */
@Serializable
data class CloudSettings(
    val backgroundWorkMaxMinutes: Int = DEFAULT_BACKGROUND_WORK_MAX_MINUTES,
    val maxRunningAutomationsPerMember: Int = DEFAULT_MAX_RUNNING_AUTOMATIONS,
    @Transient val updatedAt: Instant? = null,
    @Transient val updatedBy: Uuid? = null,
) {
    /** Constants of the enclosing type. */
    companion object {
        /** The cap while the organization saved none, TeamCode's. */
        const val DEFAULT_BACKGROUND_WORK_MAX_MINUTES = 240

        /** The largest cap: a day. */
        const val MAX_BACKGROUND_WORK_MINUTES = 1440

        /** The cap of running automations per member while the organization saved none, TeamCode's. */
        const val DEFAULT_MAX_RUNNING_AUTOMATIONS = 2

        /** The largest cap of running automations per member. */
        const val MAX_RUNNING_AUTOMATIONS = 100
    }
}
