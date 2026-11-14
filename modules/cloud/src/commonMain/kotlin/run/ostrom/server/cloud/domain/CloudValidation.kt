// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.network.isReachableUrl

// The checks of TeamCode's CloudRuntimeService, CloudAdministrationService and profile validation. Each returns why a
// value is refused, naming the field and never echoing it, or null.

private val IMAGE = Regex("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}")
private val BINARY = Regex("/[A-Za-z0-9._/+-]{1,255}")
private val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
private val DNS_SUBDOMAIN = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")
private val TOKEN = Regex("[A-Za-z0-9._~+/=-]{1,16384}")
private const val PEM_HEADER = "-----BEGIN CERTIFICATE-----"
private const val MAX_NAME = 100
private const val MAX_CA = 65_536
private const val MAX_NAMESPACES = 100
private const val MAX_PODS = 100_000
private const val MAX_RETENTION_DAYS = 3650
private const val MAX_RUNNING = 1000
private const val MAX_RETAINED = 10_000
private const val MAX_OVERRIDES = 50
private const val MAX_PULL_SECRETS = 20
private const val MIN_CPU = 100
private const val MAX_CPU = 64_000
private const val MIN_MEMORY = 128
private const val MAX_MEMORY = 262_144
private const val MAX_WORKSPACE = 2048
private const val MAX_IDLE = 1440
private const val MAX_MEMBERS = 1000

/** Whether an image reference is acceptable. */
internal fun isImage(value: String): Boolean = IMAGE.matches(value)

/** Why a runtime is refused, or null. */
internal fun runtimeProblem(runtime: CloudRuntime): String? = listOfNotNull(
    "runnerImage is not an image reference.".takeIf { runtime.runnerImage?.let(::isImage) == false },
    "runnerBinary is not an absolute path.".takeIf { !BINARY.matches(runtime.runnerBinary) },
    "serverUrl is an https origin session pods may call, without a path, a query or credentials.".takeIf {
        runtime.serverUrl?.let { url -> !isReachableUrl(url) || url.substringAfter("://").any { it in "/?#@" } } == true
    },
    "workspaceAccessMode is ReadWriteOncePod or ReadWriteOnce.".takeIf {
        runtime.workspaceAccessMode !in CloudRuntime.ACCESS_MODES
    },
    "pausedRetentionDays is 1 to 3650.".takeIf { runtime.pausedRetentionDays?.let(::isRetention) == false },
    "failedWorkspaceRetentionDays is 1 to 3650.".takeIf { !isRetention(runtime.failedWorkspaceRetentionDays) },
    "maxRunningSessionsPerMember is 1 to 1000.".takeIf { runtime.maxRunningSessionsPerMember !in 1..MAX_RUNNING },
    "maxRetainedSessionsPerMember is 1 to 10000.".takeIf { runtime.maxRetainedSessionsPerMember !in 1..MAX_RETAINED },
    "transcriptRetentionDays is 1 to 3650.".takeIf { runtime.transcriptRetentionDays?.let(::isRetention) == false },
    "memberTransfers is offer or disabled.".takeIf { runtime.memberTransfers !in CloudRuntime.TRANSFERS },
    "runnerImage is needed to turn cloud sessions on.".takeIf { runtime.enabled && runtime.runnerImage == null },
).firstOrNull()

private fun isRetention(days: Int) = days in 1..MAX_RETENTION_DAYS

// A prefix stops at a path segment or a tag, so `ghcr.io/acme/*` never lets in `ghcr.io/acme-evil/x`.
private fun isOverride(entry: String): Boolean {
    val prefix = entry.endsWith("/*") || entry.endsWith(":*")
    return isImage(if (prefix) entry.dropLast(1) else entry)
}

/**
 * Why a cluster is refused, or null (TeamCode a73e03f3 and 0ad9b80f).
 *
 * @param input What is saved.
 * @param stored The cluster replaced, or null for a new one.
 */
internal fun clusterProblem(input: CloudClusterInput, stored: CloudCluster?): String? {
    // A credential is never sent to another server: a new URL needs both again, as a new cluster does.
    val newServer = stored == null || stored.apiUrl != input.apiUrl
    return listOfNotNull(
        "name is 1 to 100 characters.".takeIf { input.name.isBlank() || input.name.length > MAX_NAME },
        ("apiUrl is an https URL that is not a link-local, unspecified or cloud metadata address.")
            .takeIf { !isReachableUrl(input.apiUrl) },
        "caCertificate is a PEM bundle of 64 KiB at most.".takeIf {
            input.caCertificate?.let { it.length > MAX_CA || !it.contains(PEM_HEADER) } == true
        },
        "serviceAccountToken is a bearer token.".takeIf { input.serviceAccountToken?.let(TOKEN::matches) == false },
        "caCertificate and serviceAccountToken are needed for a new apiUrl.".takeIf {
            newServer && (input.caCertificate == null || input.serviceAccountToken == null)
        },
        "allowedNamespaces holds 1 to 100 distinct namespaces.".takeIf {
            input.allowedNamespaces.size !in 1..MAX_NAMESPACES ||
                input.allowedNamespaces.toSet().size != input.allowedNamespaces.size ||
                !input.allowedNamespaces.all(DNS_LABEL::matches)
        },
        "maxRunningPods is 1 to 100000.".takeIf { input.maxRunningPods !in 1..MAX_PODS },
        "defaultStorageClass is a storage class name.".takeIf {
            input.defaultStorageClass?.let(DNS_SUBDOMAIN::matches) == false
        },
        "version is the cluster's.".takeIf { input.version != (stored?.version ?: 0) },
    ).firstOrNull()
}

/**
 * Why a profile is refused, or null.
 *
 * @param input What is saved.
 * @param cluster The cluster it names, or null when there is none.
 */
internal fun profileProblem(input: CloudProfileInput, cluster: CloudCluster?): String? {
    val spec = input.spec
    return listOfNotNull(
        "name is 1 to 100 characters.".takeIf { input.name.isBlank() || input.name.length > MAX_NAME },
        "spec.clusterId names no cluster.".takeIf { cluster == null },
        "spec.namespace is not one the cluster allows.".takeIf {
            cluster != null && spec.namespace !in cluster.allowedNamespaces
        },
        "spec.image is not an image reference.".takeIf { !isImage(spec.image) },
        "spec.allowedImageOverrides holds up to 50 images, or prefixes ending in /* or :*.".takeIf {
            spec.allowedImageOverrides.size > MAX_OVERRIDES || !spec.allowedImageOverrides.all(::isOverride)
        },
        "spec.imagePullSecrets holds up to 20 Secret names.".takeIf {
            spec.imagePullSecrets.size > MAX_PULL_SECRETS || !spec.imagePullSecrets.all(DNS_SUBDOMAIN::matches)
        },
        "spec.resources is 100 to 64000 millicores and 128 to 262144 MiB.".takeIf {
            spec.resources.cpuMillis !in MIN_CPU..MAX_CPU || spec.resources.memoryMebibytes !in MIN_MEMORY..MAX_MEMORY
        },
        "spec.workspaceSizeGibibytes is 1 to 2048.".takeIf { spec.workspaceSizeGibibytes !in 1..MAX_WORKSPACE },
        "spec.storageClass is a storage class name.".takeIf { spec.storageClass?.let(DNS_SUBDOMAIN::matches) == false },
        "spec.agentKind is claude or codex.".takeIf { spec.agentKind !in AGENTS },
        "spec idle timeouts are 1 to 1440 minutes, the default between the least and the most.".takeIf {
            spec.minIdleTimeoutMinutes !in 1..MAX_IDLE || spec.maxIdleTimeoutMinutes !in 1..MAX_IDLE ||
                spec.idleTimeoutMinutes !in spec.minIdleTimeoutMinutes..spec.maxIdleTimeoutMinutes
        },
        "assignedMemberIds holds up to 1000 members.".takeIf { input.assignedMemberIds.size > MAX_MEMBERS },
    ).firstOrNull()
}

private val AGENTS = setOf("claude", "codex")

/** The value trimmed, or null when it is null or blank. */
internal fun String?.trimmedOrNull(): String? = this?.let { value -> value.trim().takeIf { it.isNotEmpty() } }

/**
 * Why the settings are refused, or null.
 *
 * @param settings The settings.
 *
 * @return The reason, naming the field.
 */
internal fun settingsProblem(settings: CloudSettings): String? =
    "backgroundWorkMaxMinutes is 0 to 1440.".takeIf {
        settings.backgroundWorkMaxMinutes !in 0..CloudSettings.MAX_BACKGROUND_WORK_MINUTES
    }
