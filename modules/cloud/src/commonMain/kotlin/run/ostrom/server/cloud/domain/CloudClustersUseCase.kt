// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The API server, seen from a pod. */
const val IN_CLUSTER_API_URL: String = "https://kubernetes.default.svc"

/** The projected service-account token of a pod, which the kubelet rotates and which dies with the pod. */
const val IN_CLUSTER_TOKEN_FILE: String = "/var/run/secrets/kubernetes.io/serviceaccount/token"

/** The cluster CA of a pod. */
const val IN_CLUSTER_CA_FILE: String = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"

/** What the runtime driver reads to reach a cluster: its credentials, opened from their sealed values. */
interface CloudClusterAccess {
    /**
     * A cluster with its credentials: opened again once its version changes, and once 30 seconds old.
     *
     * @param id The cluster.
     *
     * @return It, or null when there is none or its credentials no longer open with the secrets key.
     */
    suspend fun credentials(id: Uuid): CloudClusterCredentials?
}

/** The instance's clusters, connected and changed by an administrator (TeamCode's `cloud_clusters`). */
interface CloudClustersUseCase : CloudClusterAccess {
    /**
     * Every cluster.
     *
     * @param caller An administrator of the instance.
     *
     * @return They, by name.
     */
    suspend fun list(caller: CloudAdminCaller): List<CloudCluster>

    /**
     * Connects a cluster.
     *
     * @param caller An administrator of the instance.
     * @param input The cluster, with its CA bundle and token.
     *
     * @return It.
     *
     * @throws InvalidCloudClusterException When a value is refused.
     * @throws CloudClusterConflictException When another cluster has this name.
     */
    suspend fun create(caller: CloudAdminCaller, input: CloudClusterInput): CloudCluster

    /**
     * Changes a cluster; a new `apiUrl` needs a new CA bundle and token (TeamCode 0ad9b80f).
     *
     * @param caller An administrator of the instance.
     * @param id The cluster.
     * @param input The cluster.
     *
     * @return It.
     *
     * @throws CloudClusterNotFoundException When there is no such cluster.
     * @throws InvalidCloudClusterException When a value is refused.
     * @throws CloudClusterConflictException When another save came first or the name is taken.
     */
    suspend fun update(caller: CloudAdminCaller, id: Uuid, input: CloudClusterInput): CloudCluster

    /**
     * Imports the former `OSTROM_RUNNERS_KUBERNETES_*` variables once, into a cluster named `imported`, when
     * `OSTROM_RUNNERS_DRIVER` was `kubernetes` and no cluster exists; afterwards they are ignored with a warning. The
     * pod's own service account (the default token and CA files) makes an in-cluster cluster, read from those files
     * at each use; other files are read once and sealed. A value refused is logged and nothing is imported, so the
     * server still starts.
     *
     * @param environment Reads a variable.
     *
     * @return The cluster imported now, or null.
     */
    suspend fun importLegacy(environment: (String) -> String?): CloudCluster?

    /**
     * The cluster of the former variables, for a session created before cloud profiles: the one imported at an
     * earlier start, else imported now (whatever other clusters an administrator made before the upgrade), when
     * `OSTROM_RUNNERS_DRIVER` is `kubernetes`. Asked again until it exists, so neither a crash after the import nor a
     * cluster made before the upgrade leaves such a session without it.
     *
     * @param environment Reads a variable.
     *
     * @return It, or null when the variables are not Kubernetes' or are refused.
     */
    suspend fun legacyCluster(environment: (String) -> String?): CloudCluster?
}

/**
 * [CloudClustersUseCase]: credentials sealed with the secrets key, bound to their cluster and field, never returned;
 * every change in the instance's audit log, never a credential.
 *
 * @param repository The stored clusters.
 * @param gate Who may.
 * @param cipher The secrets key.
 * @param audit The instance's audit log.
 * @param clock The clock.
 * @param logger Where an unreadable credential and the import are said, never a credential.
 * @param ttl How long credentials opened are used again while their cluster keeps its version: an in-cluster
 *   cluster's files are read again past it, since the kubelet rotates the token.
 * @param readFile Reads a text file, null when it cannot: the pod's service account of an in-cluster cluster.
 */
// The administrator's operations, the driver's read and the one-time import; the file reader is the pod's.
@Suppress("TooManyFunctions", "LongParameterList")
class CloudClustersUseCaseImpl(
    private val repository: CloudClustersRepository,
    private val gate: CloudAdminGate,
    private val cipher: SecretCipher,
    private val audit: InstanceAuditTrail,
    private val clock: Clock,
    private val logger: Logger,
    private val ttl: Duration = 30.seconds,
    private val readFile: (String) -> String?,
) : CloudClustersUseCase {
    /** Credentials opened, the version of their cluster then and when. */
    private class Opened(val version: Int, val at: Instant, val credentials: CloudClusterCredentials)

    // Replaced whole, never changed in place: concurrent reads at worst open the same credentials twice.
    @Volatile
    private var cache: Map<Uuid, Opened> = emptyMap()

    override suspend fun list(caller: CloudAdminCaller): List<CloudCluster> {
        gate.require(caller)
        return repository.list()
    }

    override suspend fun create(caller: CloudAdminCaller, input: CloudClusterInput): CloudCluster {
        val userId = gate.require(caller)
        val clean = normalize(input)
        val ca = clean.caCertificate
        val token = clean.serviceAccountToken
        if (ca == null || token == null) throw InvalidCloudClusterException(NEEDS_CREDENTIALS)
        clusterProblem(clean, null)?.let { throw InvalidCloudClusterException(it) }
        val now = clock.nowMillis()
        val id = Uuid.random()
        val cluster = CloudCluster(
            id, clean.name, clean.apiUrl, clean.allowedNamespaces, clean.maxRunningPods, clean.defaultStorageClass,
            1, hasCaCertificate = true, hasServiceAccountToken = true, createdAt = now, updatedAt = now,
        )
        val stored = StoredCloudCluster(cluster, seal(id, CA, ca), seal(id, TOKEN, token))
        if (!repository.insert(stored)) throw CloudClusterConflictException()
        record(userId, "cloud.cluster.created", cluster, newCredentials = true)
        return cluster
    }

    @Suppress("ThrowsCount") // Who may, which cluster, at which version, with which values.
    override suspend fun update(caller: CloudAdminCaller, id: Uuid, input: CloudClusterInput): CloudCluster {
        val userId = gate.require(caller)
        val before = repository.get(id) ?: throw CloudClusterNotFoundException()
        val clean = normalize(input)
        if (clean.version != before.cluster.version) throw CloudClusterConflictException()
        clusterProblem(clean, before.cluster)?.let { throw InvalidCloudClusterException(it) }
        val stores = storesBoth(before.cluster, clean)
        val cluster = before.cluster.copy(
            name = clean.name,
            apiUrl = clean.apiUrl,
            allowedNamespaces = clean.allowedNamespaces,
            maxRunningPods = clean.maxRunningPods,
            defaultStorageClass = clean.defaultStorageClass,
            version = before.cluster.version + 1,
            updatedAt = clock.nowMillis(),
            hasCaCertificate = before.cluster.hasCaCertificate || clean.caCertificate != null,
            hasServiceAccountToken = before.cluster.hasServiceAccountToken || clean.serviceAccountToken != null,
            inCluster = before.cluster.inCluster && !stores,
        )
        val stored = StoredCloudCluster(
            cluster,
            if (clean.caCertificate == null) before.sealedCaCertificate else seal(id, CA, clean.caCertificate),
            if (clean.serviceAccountToken == null) {
                before.sealedServiceAccountToken
            } else {
                seal(id, TOKEN, clean.serviceAccountToken)
            },
        )
        if (!repository.update(stored)) throw CloudClusterConflictException()
        val renewed = clean.caCertificate != null || clean.serviceAccountToken != null
        record(userId, "cloud.cluster.changed", cluster, renewed)
        return cluster
    }

    // An in-cluster cluster reads both from the pod, or stores both: never one of each.
    private fun storesBoth(before: CloudCluster, input: CloudClusterInput): Boolean {
        val both = input.caCertificate != null && input.serviceAccountToken != null
        val one = input.caCertificate != null || input.serviceAccountToken != null
        if (before.inCluster && one && !both) throw InvalidCloudClusterException(BOTH_FOR_IN_CLUSTER)
        return both
    }

    // Each operation of the runtime driver asks: the cluster's version only is read then, unless it changed or the
    // credentials are older than the ttl. A save on any replica bumps the version, so none is used once replaced.
    override suspend fun credentials(id: Uuid): CloudClusterCredentials? {
        val version = repository.version(id)
        val now = clock.now()
        val held = cache[id]
        if (held != null && held.version == version && now - held.at < ttl) return held.credentials
        val read = (if (version == null) null else repository.get(id))?.let { read(it) }
        // What does not open is asked again at the next operation, as an administrator may enter it any time.
        cache = if (read == null) cache - id else cache + (id to Opened(read.cluster.version, now, read))
        return read
    }

    private suspend fun read(stored: StoredCloudCluster): CloudClusterCredentials? {
        val cluster = stored.cluster
        return when {
            cluster.inCluster -> podCredentials(cluster)
            // Imported without them (unreadable files): an administrator enters them in the console.
            !cluster.hasCaCertificate || !cluster.hasServiceAccountToken -> null
            else -> opened(stored)
        }
    }

    private suspend fun opened(stored: StoredCloudCluster): CloudClusterCredentials? {
        val id = stored.cluster.id
        val ca = cipher.open(stored.sealedCaCertificate, context(id, CA))
        val token = cipher.open(stored.sealedServiceAccountToken, context(id, TOKEN))
        return if (ca == null || token == null) {
            // The secrets key changed, or the row is damaged: the administrator enters them again in the console.
            val fields = mapOf("cluster" to id.toString())
            logger.warn("Cloud cluster credentials no longer open: enter them again.", fields)
            null
        } else {
            CloudClusterCredentials(stored.cluster, ca.decodeToString(), token.decodeToString())
        }
    }

    // Read at each use: the kubelet rotates the projected token, which dies with the pod.
    private fun podCredentials(cluster: CloudCluster): CloudClusterCredentials? {
        val ca = readFile(IN_CLUSTER_CA_FILE).trimmedOrNull()
        val token = readFile(IN_CLUSTER_TOKEN_FILE).trimmedOrNull()
        if (ca == null || token == null) {
            logger.warn("The server pod's service account is not readable.", mapOf("cluster" to cluster.id.toString()))
            return null
        }
        return CloudClusterCredentials(cluster, ca, token)
    }

    override suspend fun importLegacy(environment: (String) -> String?): CloudCluster? {
        val set = LEGACY_VARIABLES.filter { !environment(it).isNullOrEmpty() }
        if (!kubernetes(environment) || repository.list().isNotEmpty()) {
            if (set.isNotEmpty()) {
                logger.warn(
                    "Ignored deprecated variables: clusters are stored and changed in the console only.",
                    mapOf("variables" to set.joinToString(",")),
                )
            }
            return null
        }
        return imported(environment, set)
    }

    @Suppress("ReturnCount") // Imported before, then not Kubernetes, then imported now.
    override suspend fun legacyCluster(environment: (String) -> String?): CloudCluster? {
        val clusters = repository.list()
        clusters.firstOrNull { it.imported }?.let { return it }
        if (!kubernetes(environment)) return null
        val set = LEGACY_VARIABLES.filter { !environment(it).isNullOrEmpty() }
        // An administrator's cluster made before the upgrade may hold the name.
        val name = if (clusters.any { it.name == IMPORTED }) "$IMPORTED-$LEGACY" else IMPORTED
        // Another replica imported first: its cluster is the one.
        return imported(environment, set, name) ?: repository.list().firstOrNull { it.imported }
    }

    private fun kubernetes(environment: (String) -> String?): Boolean =
        environment(DRIVER).orEmpty().trim().lowercase() == "kubernetes"

    /** What the former variables name, its credentials read once unless the pod's own are named. */
    private class Legacy(val input: CloudClusterInput, val inCluster: Boolean, val ca: String?, val token: String?)

    private fun legacy(environment: (String) -> String?, name: String): Legacy {
        fun read(name: String, default: String) = environment(name).trimmedOrNull() ?: default
        val caFile = read(CA_FILE, IN_CLUSTER_CA_FILE)
        val tokenFile = read(TOKEN_FILE, IN_CLUSTER_TOKEN_FILE)
        val inCluster = caFile == IN_CLUSTER_CA_FILE && tokenFile == IN_CLUSTER_TOKEN_FILE
        val ca = if (inCluster) null else readFile(caFile).trimmedOrNull()
        val token = if (inCluster) null else readFile(tokenFile).trimmedOrNull()
        val input = normalize(
            CloudClusterInput(
                name = name,
                apiUrl = read(API_URL, IN_CLUSTER_API_URL),
                // Checked as an administrator's, then dropped when the files did not open.
                caCertificate = ca ?: PLACEHOLDER_CA,
                serviceAccountToken = token ?: PLACEHOLDER_TOKEN,
                allowedNamespaces = listOf(read(NAMESPACE, DEFAULT_NAMESPACE)),
                maxRunningPods = IMPORTED_MAX_PODS,
                defaultStorageClass = environment(STORAGE_CLASS),
                version = 0,
            ),
        )
        return Legacy(input, inCluster, ca, token)
    }

    private suspend fun imported(
        environment: (String) -> String?,
        variables: List<String>,
        name: String = IMPORTED,
    ): CloudCluster? {
        val legacy = legacy(environment, name)
        val input = legacy.input
        val (inCluster, ca, token) = Triple(legacy.inCluster, legacy.ca, legacy.token)
        val problem = clusterProblem(input, null)
        if (problem != null) {
            logger.warn("Did not import the deprecated cluster variables: $problem", mapOf("variables" to variables))
            return null
        }
        val now = clock.nowMillis()
        val id = Uuid.random()
        val sealed = if (ca != null && token != null) seal(id, CA, ca) to seal(id, TOKEN, token) else null
        val stores = sealed != null
        val cluster = CloudCluster(
            id, input.name, input.apiUrl, input.allowedNamespaces, input.maxRunningPods, input.defaultStorageClass, 1,
            hasCaCertificate = stores, hasServiceAccountToken = stores, createdAt = now, updatedAt = now,
            inCluster = inCluster, imported = true,
        )
        val stored = StoredCloudCluster(cluster, sealed?.first.orEmpty(), sealed?.second.orEmpty())
        // Another replica imported first: its cluster is the one.
        return if (repository.insert(stored)) announced(cluster, stores, variables) else null
    }

    private suspend fun announced(cluster: CloudCluster, stores: Boolean, variables: List<String>): CloudCluster {
        val inCluster = cluster.inCluster
        val id = cluster.id
        val mode = if (inCluster) "in_cluster" else if (stores) "stored" else "missing"
        audit.record(
            InstanceAuditRecord(
                null, "cloud.cluster.imported", "cloud_cluster", id.toString(), mapOf("credentials" to mode),
            ),
        )
        val then = if (inCluster || stores) {
            "change it in the console (Admin → Cloud) and remove the variables."
        } else {
            "its CA and token files did not open: an administrator enters a long-lived service-account token and the " +
                "CA in the console (Admin → Cloud)."
        }
        logger.warn(
            "Imported deprecated variables into the cloud cluster `${cluster.name}`, once: $then",
            mapOf("variables" to variables.joinToString(","), "credentials" to mode),
        )
        return cluster
    }

    private suspend fun seal(id: Uuid, field: String, value: String): String =
        cipher.seal(value.encodeToByteArray(), context(id, field))

    private suspend fun record(userId: Uuid, event: String, cluster: CloudCluster, newCredentials: Boolean) {
        val details = mapOf(
            "name" to cluster.name.take(MAX_DETAIL),
            "version" to cluster.version.toString(),
            "credentials" to if (newCredentials) "replaced" else "kept",
        )
        audit.record(InstanceAuditRecord(userId, event, "cloud_cluster", cluster.id.toString(), details))
    }

    private companion object {
        const val CA = "ca"
        const val NEEDS_CREDENTIALS = "caCertificate and serviceAccountToken are needed for a new apiUrl."
        const val TOKEN = "token"
        const val MAX_DETAIL = 100
        const val BOTH_FOR_IN_CLUSTER =
            "caCertificate and serviceAccountToken are given together for an in-cluster cluster."
        const val DRIVER = "OSTROM_RUNNERS_DRIVER"
        const val API_URL = "OSTROM_RUNNERS_KUBERNETES_API_URL"
        const val NAMESPACE = "OSTROM_RUNNERS_KUBERNETES_NAMESPACE"
        const val CA_FILE = "OSTROM_RUNNERS_KUBERNETES_CA_FILE"
        const val TOKEN_FILE = "OSTROM_RUNNERS_KUBERNETES_TOKEN_FILE"
        const val STORAGE_CLASS = "OSTROM_RUNNERS_KUBERNETES_STORAGE_CLASS"
        private val LEGACY_VARIABLES = listOf(API_URL, NAMESPACE, CA_FILE, TOKEN_FILE, STORAGE_CLASS)
        const val IMPORTED = "imported"
        const val LEGACY = "legacy"
        const val DEFAULT_NAMESPACE = "ostrom"
        const val IMPORTED_MAX_PODS = 1000
        const val PLACEHOLDER_CA = "-----BEGIN CERTIFICATE-----"
        const val PLACEHOLDER_TOKEN = "unset"

        fun context(id: Uuid, field: String) = "cloud-cluster:$id:$field"

        fun normalize(input: CloudClusterInput) = input.copy(
            name = input.name.trim(),
            apiUrl = input.apiUrl.trim().trimEnd('/'),
            caCertificate = input.caCertificate.trimmedOrNull(),
            serviceAccountToken = input.serviceAccountToken.trimmedOrNull(),
            allowedNamespaces = input.allowedNamespaces.map { it.trim() },
            defaultStorageClass = input.defaultStorageClass.trimmedOrNull(),
        )
    }
}
