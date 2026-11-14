// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudClusterAccess
import run.ostrom.server.cloud.domain.CloudClusterCredentials
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * [KubernetesClusters] on the console's clusters (cloud module): their credentials are asked at each operation (the
 * cloud module opens them again once the cluster's version changes, and once 30 seconds old), and one HTTP client is kept
 * per cluster, trusting its CA, made again when the cluster's version or CA changes.
 *
 * A client is counted while operations use it: one replaced by a newer version is closed by its last operation, never
 * under one still running. Clients are made under one lock, so two operations never both make one and lose the other.
 *
 * @param access The clusters' credentials.
 * @param client Makes the client of a CA bundle; throws [IllegalArgumentException] for a CA that does not parse.
 */
class CloudKubernetesClusters(
    private val access: CloudClusterAccess,
    private val client: (String) -> HttpClient = { runtimeHttpClient(it, TIMEOUT_MILLIS) },
) : KubernetesClusters {
    /** A client, the version and CA it was made for, and how many operations use it. */
    private class Held(val version: Int, val ca: String, val client: HttpClient) {
        var users = 0
        var replaced = false
    }

    private val lock = Mutex()
    private val held = mutableMapOf<Uuid, Held>()

    override suspend fun <T> use(clusterId: String, block: suspend (KubernetesTarget) -> T): T {
        val id = Uuid.parseOrNull(clusterId) ?: throw RuntimeUnreachableException(GONE)
        val credentials = access.credentials(id) ?: throw RuntimeUnreachableException(GONE)
        val lease = acquire(id, credentials)
        try {
            val cluster = credentials.cluster
            return block(KubernetesTarget(lease.client, cluster.apiUrl, credentials.serviceAccountToken, cluster.version))
        } finally {
            withContext(NonCancellable) { release(lease) }
        }
    }

    /** How many clients are kept or still in use; for tests. */
    internal suspend fun clients(): Int = lock.withLock { held.size }

    private suspend fun acquire(id: Uuid, credentials: CloudClusterCredentials): Held = lock.withLock {
        val version = credentials.cluster.version
        val ca = credentials.caCertificate
        val current = held[id]
        val lease = if (current != null && current.version == version && current.ca == ca) {
            current
        } else {
            val made = try {
                client(ca)
            } catch (_: IllegalArgumentException) {
                throw RuntimeUnreachableException("The cluster's CA does not parse: an administrator enters it again.")
            }
            Held(version, ca, made).also { fresh ->
                held[id] = fresh
                current?.let(::retire)
            }
        }
        lease.users++
        lease
    }

    private suspend fun release(lease: Held) = lock.withLock {
        lease.users--
        if (lease.replaced && lease.users == 0) lease.client.close()
    }

    // Closed now when idle, else by its last operation.
    private fun retire(old: Held) {
        old.replaced = true
        if (old.users == 0) old.client.close()
    }

    private companion object {
        /** A Kubernetes API call never takes long. */
        const val TIMEOUT_MILLIS = 30_000L
        const val GONE = "The cluster is gone, or its credentials must be entered again."
    }
}
