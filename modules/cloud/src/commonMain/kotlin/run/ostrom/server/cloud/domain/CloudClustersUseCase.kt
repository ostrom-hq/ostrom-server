// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What the runtime driver reads to reach a cluster: its credentials, opened from their sealed values. */
interface CloudClusterAccess {
    /**
     * A cluster with its credentials.
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
 */
class CloudClustersUseCaseImpl(
    private val repository: CloudClustersRepository,
    private val gate: CloudAdminGate,
    private val cipher: SecretCipher,
    private val audit: InstanceAuditTrail,
    private val clock: Clock,
) : CloudClustersUseCase {
    override suspend fun list(caller: CloudAdminCaller): List<CloudCluster> {
        gate.require(caller)
        return repository.list()
    }

    override suspend fun create(caller: CloudAdminCaller, input: CloudClusterInput): CloudCluster {
        val userId = gate.require(caller)
        val clean = normalize(input)
        clusterProblem(clean, null)?.let { throw InvalidCloudClusterException(it) }
        val now = clock.nowMillis()
        val id = Uuid.random()
        val cluster = CloudCluster(
            id, clean.name, clean.apiUrl, clean.allowedNamespaces, clean.maxRunningPods, clean.defaultStorageClass,
            1, hasCaCertificate = true, hasServiceAccountToken = true, createdAt = now, updatedAt = now,
        )
        val sealedCa = seal(id, CA, clean.caCertificate)
        val stored = StoredCloudCluster(cluster, sealedCa, seal(id, TOKEN, clean.serviceAccountToken))
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
        val cluster = before.cluster.copy(
            name = clean.name,
            apiUrl = clean.apiUrl,
            allowedNamespaces = clean.allowedNamespaces,
            maxRunningPods = clean.maxRunningPods,
            defaultStorageClass = clean.defaultStorageClass,
            version = before.cluster.version + 1,
            updatedAt = clock.nowMillis(),
        )
        val stored = StoredCloudCluster(
            cluster,
            clean.caCertificate?.let { seal(id, CA, it) } ?: before.sealedCaCertificate,
            clean.serviceAccountToken?.let { seal(id, TOKEN, it) } ?: before.sealedServiceAccountToken,
        )
        if (!repository.update(stored)) throw CloudClusterConflictException()
        val renewed = clean.caCertificate != null || clean.serviceAccountToken != null
        record(userId, "cloud.cluster.changed", cluster, renewed)
        return cluster
    }

    override suspend fun credentials(id: Uuid): CloudClusterCredentials? {
        val stored = repository.get(id) ?: return null
        val ca = cipher.open(stored.sealedCaCertificate, context(id, CA))
        val token = cipher.open(stored.sealedServiceAccountToken, context(id, TOKEN))
        return if (ca == null || token == null) {
            null
        } else {
            CloudClusterCredentials(stored.cluster, ca.decodeToString(), token.decodeToString())
        }
    }

    private suspend fun seal(id: Uuid, field: String, value: String?): String =
        cipher.seal(value.orEmpty().encodeToByteArray(), context(id, field))

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
        const val TOKEN = "token"
        const val MAX_DETAIL = 100

        fun context(id: Uuid, field: String) = "cloud-cluster:$id:$field"

        fun normalize(input: CloudClusterInput) = input.copy(
            name = input.name.trim(),
            apiUrl = input.apiUrl.trim().trimEnd('/'),
            caCertificate = input.caCertificate?.trim()?.takeIf { it.isNotEmpty() },
            serviceAccountToken = input.serviceAccountToken?.trim()?.takeIf { it.isNotEmpty() },
            allowedNamespaces = input.allowedNamespaces.map { it.trim() },
            defaultStorageClass = input.defaultStorageClass?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
