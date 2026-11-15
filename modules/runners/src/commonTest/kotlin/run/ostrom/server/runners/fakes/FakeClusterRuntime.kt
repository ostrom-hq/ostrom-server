// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.runners.domain.ClaimObservation
import run.ostrom.server.runners.domain.CloudClusterObjects
import run.ostrom.server.runners.domain.ClusterReadForbiddenException
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.ManagedObjects
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.StorageClassObservation
import run.ostrom.server.runners.domain.VolumeObservation

/** The cluster of the fake runtime. */
const val FAKE_CLUSTER = "0b4c6f2e-8f1d-4a52-9b7e-2f8c3a1d5e60"

/** A Kubernetes profile on [FAKE_CLUSTER]. */
fun clusterProfile(storageClass: String? = null) =
    SessionProfile(
        clusterId = FAKE_CLUSTER,
        namespace = "ostrom",
        image = "img:1",
        workspaceGibibytes = 10,
        storageClass = storageClass,
    )

/**
 * A Kubernetes cluster in memory, modelling its provisioner as TeamCode's `FakeClusterPort`: a claim is bound to a new
 * PersistentVolume with its storage class's reclaim policy; once the claim is deleted the volume is `Released`, and a
 * `Delete` volume is removed by the provisioner at its next [provision] (at once when [provisionerDeletes] is set).
 */
class FakeClusterRuntime : RuntimeDriver, CloudClusterObjects {
    override val provesDeletion: Boolean = true

    /** The storage classes, by name, and their reclaim policy. */
    val storageClasses = mutableMapOf("standard" to "Delete")

    /** The class a claim without one gets. */
    var defaultClass: String? = "standard"

    /** Whether the provisioner deletes a released `Delete` volume as soon as its claim is gone. */
    var provisionerDeletes = true

    /** Whether every operation finds the cluster unreachable. */
    var unreachable = false

    /** Whether the role refuses the cluster-wide reads: PersistentVolumes and storage classes (no ClusterRole). */
    var clusterReadsForbidden = false

    /** The namespaces whose objects the role no longer lists. */
    val forbiddenNamespaces = mutableSetOf<String>()

    /** The namespaces whose pods the role lists but no longer deletes. */
    val undeletableNamespaces = mutableSetOf<String>()

    /** The claims by `namespace/name`, the volume each is bound to, and whether they carry the cluster label. */
    val claims = linkedMapOf<String, Pair<String?, Boolean>>()

    /** The pods by `namespace/name`, and whether they carry the cluster label. */
    val pods = linkedMapOf<String, Boolean>()
    val volumes = linkedMapOf<String, VolumeObservation>()
    private var next = 0

    private fun reach() {
        if (unreachable) throw RuntimeUnreachableException("gone")
    }

    private fun readClusterWide() {
        reach()
        if (clusterReadsForbidden) throw ClusterReadForbiddenException("read volumes")
    }

    private fun CloudSession.claimKey() = "${profile!!.namespace}/${RuntimeNames.workspace(id)}"

    override suspend fun ensureWorkspace(session: CloudSession) {
        reach()
        val key = session.claimKey()
        if (key in claims) return
        val policy = storageClasses.getValue(session.profile!!.storageClass ?: defaultClass!!)
        val name = "pv-${++next}"
        val namespace = session.profile!!.namespace
        volumes[name] = VolumeObservation(name, "Bound", policy, "vol-$next", namespace, key.substringAfter('/'))
        claims[key] = name to true
    }

    override suspend fun startExecution(spec: ExecutionSpec) {
        reach()
        pods["${spec.profile!!.namespace}/${RuntimeNames.execution(spec.sessionId, spec.epoch)}"] = true
    }

    override suspend fun executionStatus(session: CloudSession): ExecutionStatus {
        reach()
        val key = "${session.profile!!.namespace}/${RuntimeNames.execution(session.id, session.epoch)}"
        return if (key in pods) ExecutionStatus.RUNNING else ExecutionStatus.MISSING
    }

    override suspend fun stopExecutions(session: CloudSession) {
        reach()
        pods.keys.removeAll { it.contains(session.id.toHexString()) }
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        reach()
        if (session.profile == null) return true
        removeClaim(session.claimKey())
        return true
    }

    override suspend fun storageClass(session: CloudSession): StorageClassObservation? =
        storageClass(FAKE_CLUSTER, session.profile?.storageClass)

    override suspend fun workspaceClaim(session: CloudSession): ClaimObservation? {
        reach()
        return claims[session.claimKey()]?.let { ClaimObservation(it.first) }
    }

    override suspend fun volume(session: CloudSession, name: String): VolumeObservation? {
        readClusterWide()
        return volumes[name]
    }

    override suspend fun claimedVolume(session: CloudSession): VolumeObservation? {
        readClusterWide()
        val profile = session.profile!!
        return volumes.values.firstOrNull {
            it.claimNamespace == profile.namespace && it.claimName == RuntimeNames.workspace(session.id)
        }
    }

    override suspend fun storageClass(clusterId: String, name: String?): StorageClassObservation? {
        readClusterWide()
        val chosen = name ?: defaultClass ?: return null
        return storageClasses[chosen]?.let { StorageClassObservation(chosen, it) }
    }

    override suspend fun managed(clusterId: String, namespace: String): ManagedObjects {
        reach()
        check(namespace !in forbiddenNamespaces) { "forbidden" }
        fun names(objects: Map<String, Boolean>) =
            objects.filter { (key, labelled) -> labelled && key.startsWith("$namespace/") }
                .keys.map { it.substringAfter('/') }
        return ManagedObjects(names(pods), names(claims.mapValues { it.value.second }))
    }

    override suspend fun orphanedVolumes(clusterId: String): List<VolumeObservation> {
        readClusterWide()
        return volumes.values.filter {
            it.claimName?.startsWith(RuntimeNames.WORKSPACE_PREFIX) == true && it.phase in setOf("Released", "Failed")
        }
    }

    override suspend fun deletePod(clusterId: String, namespace: String, name: String) {
        reach()
        check(namespace !in undeletableNamespaces) { "forbidden" }
        pods.remove("$namespace/$name")
    }

    override suspend fun deleteClaim(clusterId: String, namespace: String, name: String) {
        reach()
        removeClaim("$namespace/$name")
    }

    /** The provisioner's pass: released `Delete` volumes are deleted with their provider volume. */
    fun provision() {
        volumes.values.removeAll { it.phase == "Released" && it.reclaimPolicy == "Delete" }
    }

    /** Plants objects no session accounts for: a labelled pod and claim (whose volume is retained), unlabelled ones. */
    fun plantOrphans(namespace: String) {
        pods["$namespace/ostrom-s-orphan-e1"] = true
        pods["$namespace/ostrom-s-older-e1"] = false
        claims["$namespace/${RuntimeNames.WORKSPACE_PREFIX}orphan"] = null to true
        claims["$namespace/${RuntimeNames.WORKSPACE_PREFIX}older"] = null to false
        volumes["pv-retained"] =
            VolumeObservation("pv-retained", "Released", "Retain", "vol-r", namespace, PREFIX + "a")
        volumes["pv-deleting"] =
            VolumeObservation("pv-deleting", "Released", "Delete", "vol-d", namespace, PREFIX + "b")
    }

    private fun removeClaim(key: String) {
        val volume = claims.remove(key)?.first ?: return
        volumes[volume]?.let { volumes[volume] = it.copy(phase = "Released") }
        if (provisionerDeletes) provision()
    }
}

private const val PREFIX = RuntimeNames.WORKSPACE_PREFIX
