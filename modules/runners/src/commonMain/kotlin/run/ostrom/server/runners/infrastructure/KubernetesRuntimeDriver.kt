// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.ClaimObservation
import run.ostrom.server.runners.domain.ClusterReadForbiddenException
import run.ostrom.server.runners.domain.CloudClusterObjects
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ManagedObjects
import run.ostrom.server.runners.domain.StorageClassObservation
import run.ostrom.server.runners.domain.VolumeObservation
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.PodTokenIdentity
import run.ostrom.server.runners.domain.PodTokenReviews
import run.ostrom.server.runners.domain.RunnerCredentialsImpl
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionResources
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * How the driver reaches one cluster, for one operation: its credentials are read again for the next one (an
 * in-cluster cluster's token rotates, an administrator may replace a stored one).
 *
 * @property client The HTTP client, trusting the cluster's CA.
 * @property apiUrl The API server.
 * @property token The service-account token.
 * @property version The cluster's version, which each save in the console increments.
 */
class KubernetesTarget(val client: HttpClient, val apiUrl: String, val token: String, val version: Int) {
    override fun toString(): String = "KubernetesTarget(apiUrl=$apiUrl, token=***, version=$version)"
}

/** The instance's clusters, as the driver reaches them. */
interface KubernetesClusters {
    /**
     * Runs one operation on a cluster, with a client no other step closes while it runs.
     *
     * @param clusterId The cluster.
     * @param block The operation.
     *
     * @return What [block] returns.
     *
     * @throws RuntimeUnreachableException When the cluster is gone, or its credentials or its CA no longer open.
     */
    suspend fun <T> use(clusterId: String, block: suspend (KubernetesTarget) -> T): T
}

/**
 * [RuntimeDriver] on Kubernetes, through its REST API (no client library: the surface is a handful of calls, as in
 * TeamCode's `KubernetesClusterPort`).
 *
 * Each session lives on the cluster and in the namespace of its profile ([CloudSession.profile], the console's
 * clusters, `runners.cloud.profiles.v1`): a workspace PersistentVolumeClaim per session, a Secret and a bare Pod
 * (`restartPolicy: Never`) per execution, and two NetworkPolicies shared by the session pods of a namespace, each
 * labelled with the session's cluster ([CLUSTER_LABEL]) for the garbage collection. A workspace's deletion is proven
 * down to the provider's volume, and a profile's storage class is checked, as in TeamCode: besides its namespaced Role
 * (pods, secrets, claims, NetworkPolicies), the cluster's service account needs `get` and `list` on the cluster-wide
 * `persistentvolumes` and `storageclasses` (a ClusterRole; docs/MODULES.md lists the rules). With pod identity
 * ([KubernetesSettings.podIdentity]) the runner proves its pod with a projected token this driver reviews
 * ([PodTokenReviews], TokenReview), which needs `create` on `tokenreviews` (`system:auth-delegator`).
 *
 * @param clusters Reaches a session's cluster.
 * @param settings The pods' priority class and the server pods' labels.
 * @param delivery The runner image, its binary and the URL the runner reaches the server at.
 */
@Suppress("TooManyFunctions") // The ports' operations plus one builder per Kubernetes object.
class KubernetesRuntimeDriver(
    private val clusters: KubernetesClusters,
    private val settings: KubernetesSettings,
    private val delivery: RunnerDeliverySource,
) : RuntimeDriver, CloudClusterObjects, PodTokenReviews {
    /**
     * The cluster, at its version, and namespace whose NetworkPolicies are in place, so that later starts skip them;
     * a cluster saved again (a new API server, say) gets them again.
     */
    // Replaced whole, never changed in place: concurrent steps at worst apply the same policies twice (a no-op).
    @Volatile
    private var policiesApplied: Set<Triple<String, Int, String>> = emptySet()

    /** One operation on a session's cluster and namespace. */
    private class Scope(val target: KubernetesTarget, val profile: SessionProfile) {
        val base = target.apiUrl.trimEnd('/')
        val namespace = profile.namespace
    }

    /**
     * Runs one operation in the cluster and namespace of a session's profile.
     *
     * @throws RuntimeUnreachableException When its cluster does not open: tried again later, for a bounded time.
     */
    private suspend fun <T> on(profile: SessionProfile, block: suspend (Scope) -> T): T =
        clusters.use(profile.clusterId) { target -> block(Scope(target, profile)) }

    // A session without a profile has none of its objects anywhere.
    override suspend fun ensureWorkspace(session: CloudSession) {
        on(session.profile ?: return) { scope -> ensureWorkspace(scope, session) }
    }

    private suspend fun ensureWorkspace(scope: Scope, session: CloudSession) {
        val accessMode = delivery.current().workspaceAccessMode
        val claim = buildJsonObject {
            put("apiVersion", "v1")
            put("kind", "PersistentVolumeClaim")
            putJsonObject("metadata") {
                put("name", RuntimeNames.workspace(session.id))
                put("labels", labels(session.id, null, scope.profile.clusterId))
            }
            putJsonObject("spec") {
                putJsonArray("accessModes") { add(JsonPrimitive(accessMode)) }
                scope.profile.storageClass?.let { put("storageClassName", it) }
                putJsonObject("resources") {
                    putJsonObject("requests") { put("storage", "${scope.profile.workspaceGibibytes}Gi") }
                }
            }
        }
        scope.create("/api/v1/namespaces/${scope.namespace}/persistentvolumeclaims", claim)
    }

    override suspend fun startExecution(spec: ExecutionSpec) {
        on(spec.profile ?: return) { scope -> startExecution(scope, spec) }
    }

    private suspend fun startExecution(scope: Scope, spec: ExecutionSpec) {
        scope.applyNetworkPolicies()
        val name = RuntimeNames.execution(spec.sessionId, spec.epoch)
        val secret = buildJsonObject {
            put("apiVersion", "v1")
            put("kind", "Secret")
            putJsonObject("metadata") {
                put("name", name)
                put("labels", labels(spec.sessionId, spec.epoch, scope.profile.clusterId))
            }
            put("type", "Opaque")
            putJsonObject("stringData") {
                put("token", spec.runnerToken)
                spec.prompt?.let { put("prompt", it) }
            }
        }
        scope.create("/api/v1/namespaces/${scope.namespace}/secrets", secret)
        val pod = podManifest(spec, scope.profile, name, delivery.current())
        scope.create("/api/v1/namespaces/${scope.namespace}/pods", pod)
    }

    override suspend fun executionStatus(session: CloudSession): ExecutionStatus {
        val profile = session.profile ?: return ExecutionStatus.MISSING
        return on(profile) { scope ->
            val name = RuntimeNames.execution(session.id, session.epoch)
            val response = scope.call(HttpMethod.Get, "${scope.base}/api/v1/namespaces/${scope.namespace}/pods/$name")
            if (response.status == HttpStatusCode.NotFound) {
                ExecutionStatus.MISSING
            } else {
                response.requireSuccess("read pod")
                podStatus(Json.parseToJsonElement(response.bodyAsText()).jsonObject)
            }
        }
    }

    override suspend fun stopExecutions(session: CloudSession) {
        on(session.profile ?: return) { scope -> stopExecutions(scope, session) }
    }

    private suspend fun stopExecutions(scope: Scope, session: CloudSession) {
        val selector = "$SESSION_LABEL=${session.id.toHexString()}"
        for (kind in listOf("pods", "secrets")) {
            val parameters = mapOf("labelSelector" to selector) +
                if (kind == "pods") mapOf("gracePeriodSeconds" to GRACE_SECONDS.toString()) else emptyMap()
            val path = "${scope.base}/api/v1/namespaces/${scope.namespace}/$kind"
            scope.call(HttpMethod.Delete, path, parameters = parameters)
                .requireSuccess("delete $kind")
        }
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        val profile = session.profile ?: return true
        return on(profile) { scope ->
            val path = scope.claim(session)
            val deleted = scope.call(HttpMethod.Delete, path)
            if (deleted.status != HttpStatusCode.NotFound) deleted.requireSuccess("delete workspace")
            // The claim is gone once the API no longer lists it; the reconciler then waits for its PersistentVolume
            // ([volume]): with the storage class's Delete policy, the provisioner deletes it with the provider's
            // volume.
            scope.call(HttpMethod.Get, path).status == HttpStatusCode.NotFound
        }
    }

    override val provesDeletion: Boolean get() = true

    override suspend fun storageClass(session: CloudSession): StorageClassObservation? {
        val profile = session.profile ?: return null
        return storageClass(profile.clusterId, profile.storageClass)
    }

    override suspend fun workspaceClaim(session: CloudSession): ClaimObservation? {
        val profile = session.profile ?: return null
        return on(profile) { scope ->
            scope.target.read(scope.claim(session), "read workspace")?.let { claim ->
                ClaimObservation(claim.textAt("spec", "volumeName"))
            }
        }
    }

    override suspend fun volume(session: CloudSession, name: String): VolumeObservation? {
        val profile = session.profile ?: return null
        return on(profile) { scope ->
            scope.target.read("${scope.base}/api/v1/persistentvolumes/$name", "read volume", clusterScoped = true)
        }?.let { volume(it) }
    }

    override suspend fun claimedVolume(session: CloudSession): VolumeObservation? {
        val profile = session.profile ?: return null
        val claim = RuntimeNames.workspace(session.id)
        return clusters.use(profile.clusterId) { target ->
            // Filtered here, the claim reference carrying no label: a listing cut before its end proves nothing.
            target.list(
                "${target.apiUrl.trimEnd('/')}/api/v1/persistentvolumes",
                null,
                MAX_VOLUME_PAGES,
                clusterScoped = true,
                whole = true,
            ).map { volume(it) }
                .firstOrNull { it.claimNamespace == profile.namespace && it.claimName == claim }
        }
    }

    override suspend fun storageClass(clusterId: String, name: String?): StorageClassObservation? =
        clusters.use(clusterId) { target ->
            val path = "${target.apiUrl.trimEnd('/')}/apis/storage.k8s.io/v1/storageclasses"
            if (name != null) {
                val named = target.read("$path/$name", "read storage class", clusterScoped = true)
                return@use named?.let { storageClass(it) }
            }
            // No class named: the cluster's default, as the API server picks it for the claim; either annotation
            // marks it.
            target.list(path, null, clusterScoped = true).firstOrNull { json ->
                DEFAULT_CLASS_ANNOTATIONS.any { json.textAt("metadata", "annotations", it) == "true" }
            }?.let { storageClass(it) }
        }

    override suspend fun managed(clusterId: String, namespace: String): ManagedObjects =
        clusters.use(clusterId) { target -> managed(target, clusterId, namespace) }

    private suspend fun managed(target: KubernetesTarget, clusterId: String, namespace: String): ManagedObjects {
        val selector = "$MANAGED_BY_LABEL=$MANAGED_BY,$CLUSTER_LABEL=$clusterId"
        val base = "${target.apiUrl.trimEnd('/')}/api/v1/namespaces/$namespace"
        return ManagedObjects(
            target.list("$base/pods", selector).mapNotNull { it.textAt("metadata", "name") },
            target.list("$base/persistentvolumeclaims", selector).mapNotNull { it.textAt("metadata", "name") },
        )
    }

    // PersistentVolumes are cluster-wide and carry no label of ours: read them all, those of a workspace claim kept.
    override suspend fun orphanedVolumes(clusterId: String): List<VolumeObservation> =
        clusters.use(clusterId) { target -> orphanedVolumes(target) }

    private suspend fun orphanedVolumes(target: KubernetesTarget): List<VolumeObservation> =
        target.list(
            "${target.apiUrl.trimEnd('/')}/api/v1/persistentvolumes",
            null,
            MAX_VOLUME_PAGES,
            clusterScoped = true,
        ).map { volume(it) }
            .filter { it.claimName?.startsWith(RuntimeNames.WORKSPACE_PREFIX) == true }
            .filter { it.phase == "Released" || it.phase == "Failed" }

    override suspend fun deletePod(clusterId: String, namespace: String, name: String) = clusters.use(clusterId) {
        val path = "${it.apiUrl.trimEnd('/')}/api/v1/namespaces/$namespace/pods/$name"
        it.delete(path, mapOf("gracePeriodSeconds" to "0"), "delete pod")
    }

    override suspend fun deleteClaim(clusterId: String, namespace: String, name: String) = clusters.use(clusterId) {
        val path = "${it.apiUrl.trimEnd('/')}/api/v1/namespaces/$namespace/persistentvolumeclaims/$name"
        it.delete(path, emptyMap(), "delete claim")
    }

    override suspend fun review(clusterId: String, token: String, audience: String): PodTokenIdentity? =
        clusters.use(clusterId) { target ->
            val body = buildJsonObject {
                put("apiVersion", "authentication.k8s.io/v1")
                put("kind", "TokenReview")
                putJsonObject("spec") {
                    put("token", token)
                    putJsonArray("audiences") { add(JsonPrimitive(audience)) }
                }
            }
            val url = "${target.apiUrl.trimEnd('/')}/apis/authentication.k8s.io/v1/tokenreviews"
            val response = target.send(HttpMethod.Post, url, body)
            response.requireSuccess("review token")
            tokenIdentity(Json.parseToJsonElement(response.bodyAsText()).jsonObject, audience)
        }

    /** Reads an object; null once it is gone. */
    private suspend fun KubernetesTarget.read(url: String, step: String, clusterScoped: Boolean = false): JsonObject? {
        val response = send(HttpMethod.Get, url)
        if (response.status == HttpStatusCode.NotFound) return null
        response.requireReadable(step, clusterScoped)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** Deletes an object; one already gone is a success. */
    private suspend fun KubernetesTarget.delete(url: String, parameters: Map<String, String>, step: String) {
        val response = send(HttpMethod.Delete, url, parameters = parameters)
        if (response.status != HttpStatusCode.NotFound) response.requireSuccess(step)
    }

    /** Lists a collection, page by page (at most [maxPages] of [PAGE_SIZE]). */
    private suspend fun KubernetesTarget.list(
        url: String,
        selector: String?,
        maxPages: Int = MAX_PAGES,
        clusterScoped: Boolean = false,
        whole: Boolean = false,
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var next: String? = null
        repeat(maxPages) {
            val parameters = buildMap {
                put("limit", PAGE_SIZE.toString())
                selector?.let { put("labelSelector", it) }
                next?.let { put("continue", it) }
            }
            val response = send(HttpMethod.Get, url, parameters = parameters)
            response.requireReadable("list", clusterScoped)
            val page = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            (page["items"] as? JsonArray)?.forEach { item -> (item as? JsonObject)?.let(items::add) }
            next = page.textAt("metadata", "continue")?.takeIf { it.isNotEmpty() } ?: return items
        }
        // Cut at maxPages: a listing that must be whole (a lookup filtered here) cannot be read, not empty.
        if (whole) throw ClusterReadForbiddenException("list every PersistentVolume within $maxPages pages")
        return items
    }

    // Its workspace claim is read: whatever the API answers but a refused token, the cluster opens.
    override suspend fun reachable(session: CloudSession): Boolean {
        val profile = session.profile ?: return true
        return try {
            on(profile) { scope -> scope.call(HttpMethod.Get, scope.claim(session)) }
            true
        } catch (_: RuntimeUnreachableException) {
            false
        }
    }

    private fun Scope.claim(session: CloudSession): String =
        "$base/api/v1/namespaces/$namespace/persistentvolumeclaims/${RuntimeNames.workspace(session.id)}"

    /** The session pods' network: no ingress; DNS, the server, and the internet without private ranges. */
    private suspend fun Scope.applyNetworkPolicies() {
        val key = Triple(profile.clusterId, target.version, namespace)
        if (key in policiesApplied) return
        for (restricted in listOf(true, false)) {
            val name = if (restricted) RESTRICTED_POLICY else OPEN_POLICY
            val policy = SessionNetworkPolicy.manifest(name, restricted, settings.serverSelector)
            val path = "$base/apis/networking.k8s.io/v1/namespaces/$namespace/networkpolicies"
            val created = call(HttpMethod.Post, path, policy)
            if (created.status == HttpStatusCode.Conflict) {
                val current = call(HttpMethod.Get, "$path/$name")
                current.requireSuccess("read network policy")
                val version = Json.parseToJsonElement(current.bodyAsText()).textAt("metadata", "resourceVersion")
                val metadata = policy.getValue("metadata").jsonObject + ("resourceVersion" to JsonPrimitive(version))
                val replaced = JsonObject(policy + ("metadata" to JsonObject(metadata)))
                call(HttpMethod.Put, "$path/$name", replaced).requireSuccess("replace network policy")
            } else {
                created.requireSuccess("create network policy")
            }
        }
        policiesApplied = policiesApplied + key
    }

    // A manifest reads top to bottom like the YAML it stands for: splitting it would scatter one object.
    @Suppress("LongMethod")
    private fun podManifest(
        spec: ExecutionSpec,
        profile: SessionProfile,
        name: String,
        delivery: RunnerDelivery,
    ): JsonObject = buildJsonObject {
        val image = profile.image
        put("apiVersion", "v1")
        put("kind", "Pod")
        putJsonObject("metadata") {
            put("name", name)
            val egressLabel = EGRESS_LABEL to JsonPrimitive(egress(spec))
            put("labels", JsonObject(labels(spec.sessionId, spec.epoch, profile.clusterId) + egressLabel))
            if (spec.limits.restrictedEgress) {
                putJsonObject("annotations") { put("kubernetes.io/egress-bandwidth", RESTRICTED_BANDWIDTH) }
            }
        }
        putJsonObject("spec") {
            put("restartPolicy", "Never")
            put("automountServiceAccountToken", false)
            put("enableServiceLinks", false)
            put("terminationGracePeriodSeconds", GRACE_SECONDS)
            settings.priorityClass?.let { put("priorityClassName", it) }
            if (profile.imagePullSecrets.isNotEmpty()) {
                putJsonArray("imagePullSecrets") {
                    profile.imagePullSecrets.forEach { secret -> add(buildJsonObject { put("name", secret) }) }
                }
            }
            // The runner and the agent never share a uid (S2-49): the agent host runs the agent in its own container,
            // which has neither the token nor a view of the runner's or the check host's processes. Every container is
            // in the pod's group (TeamCode's image contract: runner 1000, agent 1001, group 1000); the runner keeps
            // nothing of its own on a volume the agent mounts but the uploads, the members' files it writes 0640 for
            // the agent to read through that group.
            put("shareProcessNamespace", false)
            putJsonObject("securityContext") {
                put("runAsNonRoot", true)
                put("fsGroup", SHARED_GID)
                putJsonObject("seccompProfile") { put("type", "RuntimeDefault") }
            }
            // First the runner image's `ostrom`, copied into the binary volume (TeamCode's delivery); then the native
            // sidecars (Kubernetes 1.29+): they start before the runner and stop once the runner has ended.
            putJsonArray("initContainers") {
                add(install(delivery))
                add(agentHost(image, profile.resources))
                add(checkHost(image))
            }
            putJsonArray("containers") {
                add(
                    container("runner", RUNNER_UID, image) {
                        command(CLOUD_RUNNER)
                        put("env", environment(spec, name, delivery.serverUrl))
                        // The kubelet counts the uploads volume in the pod's ephemeral storage, the sum of its
                        // containers' limits: the runner, which writes it, carries its 1 GiB.
                        put("resources", resources("50m", "64Mi", "500m", "256Mi", RUNNER_DISK_LIMIT))
                        putJsonArray("volumeMounts") {
                            add(mount("agent-socket", RunnerEnvironment.AGENT_SOCKET_DIRECTORY))
                            add(mount("check-socket", RunnerEnvironment.CHECK_SOCKET_DIRECTORY))
                            add(mount(UPLOADS_VOLUME, RunnerEnvironment.UPLOADS_DIR))
                            add(binaryMount())
                            if (settings.podIdentity) {
                                add(
                                    buildJsonObject {
                                        put("name", POD_TOKEN_VOLUME)
                                        put("mountPath", POD_TOKEN_DIRECTORY)
                                        put("readOnly", true)
                                    },
                                )
                            }
                        }
                    },
                )
            }
            putJsonArray("volumes") {
                add(
                    buildJsonObject {
                        put("name", "workspace")
                        putJsonObject("persistentVolumeClaim") {
                            put("claimName", RuntimeNames.workspace(spec.sessionId))
                        }
                    },
                )
                add(emptyDir("tmp", "1Gi"))
                // In memory: besides the sockets, it holds the agent's gh configuration, which the runner writes.
                add(emptyDir("agent-socket", "1Mi", memory = true))
                add(emptyDir("check-tmp", "1Gi"))
                add(emptyDir("check-socket", "1Mi"))
                add(emptyDir(BINARY_VOLUME, BINARY_VOLUME_SIZE))
                // On disk: up to 100 MiB per file must not count against the containers' memory.
                add(emptyDir(UPLOADS_VOLUME, UPLOADS_SIZE_LIMIT))
                // The runner's proof of its pod, which the cluster reviews (TeamCode's `runner-token`); the kubelet
                // rotates it, and only the runner's container mounts it.
                if (settings.podIdentity) add(podToken())
            }
        }
    }

    private fun podToken(): JsonObject = buildJsonObject {
        put("name", POD_TOKEN_VOLUME)
        putJsonObject("projected") {
            putJsonArray("sources") {
                add(
                    buildJsonObject {
                        putJsonObject("serviceAccountToken") {
                            put("audience", RunnerCredentialsImpl.AUDIENCE)
                            put("expirationSeconds", POD_TOKEN_SECONDS)
                            put("path", "token")
                        }
                    },
                )
            }
        }
    }

    /**
     * The init container: copies `ostrom` out of the runner image into the binary volume, which every other container
     * mounts read-only. It runs as the runner's uid, so the agent's can never change the copy.
     */
    private fun install(delivery: RunnerDelivery): JsonObject =
        container("ostrom", RUNNER_UID, delivery.runnerImage) {
            putJsonArray("command") {
                add(JsonPrimitive("cp"))
                add(JsonPrimitive(delivery.runnerBinary))
                add(JsonPrimitive(RunnerDelivery.BINARY))
            }
            put("resources", resources("10m", "16Mi", "500m", "64Mi", "64Mi"))
            putJsonArray("volumeMounts") { add(mount(BINARY_VOLUME, RunnerDelivery.DIRECTORY)) }
        }

    /** The agent host (`ostrom cloud agent-supervisor`): the agent's uid, the workspace, the agent socket, no token. */
    private fun agentHost(image: String, sized: SessionResources?): JsonObject = container("agent", AGENT_UID, image) {
        put("restartPolicy", "Always")
        command(CLOUD_AGENT)
        put("workingDir", WORKSPACE)
        putJsonArray("env") {
            add(variable("OSTROM_AGENT_SOCKET", RunnerEnvironment.AGENT_SOCKET))
            add(variable("HOME", "$WORKSPACE/.home"))
        }
        // The profile's, requests equal to limits (TeamCode's); a back-filled session keeps the former ones.
        val cpu = sized?.let { "${it.cpuMillis}m" }
        val memory = sized?.let { "${it.memoryMebibytes}Mi" }
        put("resources", resources(cpu ?: "250m", memory ?: "512Mi", cpu ?: "2", memory ?: "4Gi", "2Gi"))
        putJsonArray("volumeMounts") {
            add(mount("workspace", WORKSPACE))
            add(mount("tmp", "/tmp"))
            add(mount("agent-socket", RunnerEnvironment.AGENT_SOCKET_DIRECTORY))
            // The same path as the runner's, the one the agent reads in its message; it reads, never replaces.
            add(
                buildJsonObject {
                    put("name", UPLOADS_VOLUME)
                    put("mountPath", RunnerEnvironment.UPLOADS_DIR)
                    put("readOnly", true)
                },
            )
            add(binaryMount())
        }
    }

    /**
     * The check host: runs the hooks' checks for the runner, on the workspace, without the token. Its socket is on a
     * volume the agent's container does not mount; its processes are out of the agent's sight (no shared process
     * namespace).
     */
    private fun checkHost(image: String): JsonObject = container("checks", AGENT_UID, image) {
        put("restartPolicy", "Always")
        command(CLOUD_CHECKS)
        put("workingDir", WORKSPACE)
        putJsonArray("env") {
            add(variable("OSTROM_CHECK_HOST_SOCKET", RunnerEnvironment.CHECK_HOST_SOCKET))
            add(variable("HOME", "/tmp"))
        }
        put("resources", resources("50m", "128Mi", "1", "2Gi", "1Gi"))
        putJsonArray("volumeMounts") {
            add(mount("workspace", WORKSPACE))
            add(mount("check-tmp", "/tmp"))
            add(mount("check-socket", RunnerEnvironment.CHECK_SOCKET_DIRECTORY))
            add(binaryMount())
        }
    }

    /** `/opt/ostrom/ostrom cloud <name>`. */
    private fun JsonObjectBuilder.command(name: String) = putJsonArray("command") {
        add(JsonPrimitive(RunnerDelivery.BINARY))
        add(JsonPrimitive("cloud"))
        add(JsonPrimitive(name))
    }

    /** The binary volume, read-only. */
    private fun binaryMount(): JsonObject = buildJsonObject {
        put("name", BINARY_VOLUME)
        put("mountPath", RunnerDelivery.DIRECTORY)
        put("readOnly", true)
    }

    private fun emptyDir(name: String, size: String, memory: Boolean = false): JsonObject = buildJsonObject {
        put("name", name)
        putJsonObject("emptyDir") {
            if (memory) put("medium", "Memory")
            put("sizeLimit", size)
        }
    }

    /** A container of the session pod: its own uid in the pod's group, no privilege, a read-only root. */
    private fun container(name: String, uid: Int, image: String, body: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("name", name)
            put("image", image)
            putJsonObject("securityContext") {
                put("runAsUser", uid)
                put("runAsGroup", SHARED_GID)
                put("allowPrivilegeEscalation", false)
                put("readOnlyRootFilesystem", true)
                putJsonObject("capabilities") { putJsonArray("drop") { add(JsonPrimitive("ALL")) } }
            }
            body()
        }

    /**
     * The runner's variables: identifiers as values, the token and the first message read from the execution's
     * Secret by the kubelet, so that they are neither in the pod's spec nor in a file.
     */
    private fun environment(spec: ExecutionSpec, secret: String, serverUrl: String): JsonArray = buildJsonArray {
        RunnerEnvironment.of(spec, serverUrl).forEach { (key, value) -> add(variable(key, value)) }
        if (settings.podIdentity) add(variable(RunnerEnvironment.POD_TOKEN_FILE, "$POD_TOKEN_DIRECTORY/token"))
        // The runner announces message files only with this volume (TeamCode's `TEAMCODE_UPLOADS_DIR`).
        add(variable(RunnerEnvironment.UPLOADS_DIR_VARIABLE, RunnerEnvironment.UPLOADS_DIR))
        val keys = listOf(RunnerEnvironment.TOKEN to "token") +
            listOfNotNull(spec.prompt?.let { RunnerEnvironment.PROMPT to "prompt" })
        keys.forEach { (variable, key) ->
            add(
                buildJsonObject {
                    put("name", variable)
                    putJsonObject("valueFrom") {
                        putJsonObject("secretKeyRef") {
                            put("name", secret)
                            put("key", key)
                        }
                    }
                },
            )
        }
    }

    private fun variable(name: String, value: String): JsonObject = buildJsonObject {
        put("name", name)
        put("value", value)
    }

    private fun mount(name: String, path: String): JsonObject = buildJsonObject {
        put("name", name)
        put("mountPath", path)
    }

    @Suppress("LongParameterList") // The five figures of a container's requests and limits.
    private fun resources(cpu: String, memory: String, cpuLimit: String, memoryLimit: String, disk: String) =
        buildJsonObject {
            putJsonObject("requests") {
                put("cpu", cpu)
                put("memory", memory)
                put("ephemeral-storage", EPHEMERAL_REQUEST)
            }
            putJsonObject("limits") {
                put("cpu", cpuLimit)
                put("memory", memoryLimit)
                put("ephemeral-storage", disk)
            }
        }

    private suspend fun Scope.create(path: String, body: JsonObject) {
        val response = call(HttpMethod.Post, "$base$path", body)
        // Creating what exists is a success: every step can be repeated after a crash.
        if (response.status != HttpStatusCode.Conflict) {
            response.requireSuccess("create ${body.textAt("kind")}")
        }
    }

    private suspend fun Scope.call(
        method: HttpMethod,
        url: String,
        body: JsonObject? = null,
        parameters: Map<String, String> = emptyMap(),
    ): HttpResponse = target.send(method, url, body, parameters)

    private suspend fun KubernetesTarget.send(
        method: HttpMethod,
        url: String,
        body: JsonObject? = null,
        parameters: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val response = try {
            client.request(url) {
                this.method = method
                bearerAuth(token)
                parameters.forEach { (key, value) -> parameter(key, value) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        } catch (_: IOException) {
            // Refused, timed out or unresolved: never the cause's text, which may name the cluster's internals.
            throw RuntimeUnreachableException("The cluster's API does not answer.")
        }
        // The token no longer opens the cluster: a revoked or expired service account.
        if (response.status == HttpStatusCode.Unauthorized) {
            throw RuntimeUnreachableException("The cluster's API refuses the service-account token.")
        }
        return response
    }

    private fun labels(sessionId: Uuid, epoch: Long?, clusterId: String): JsonObject = buildJsonObject {
        put(MANAGED_BY_LABEL, MANAGED_BY)
        put(SESSION_LABEL, sessionId.toHexString())
        epoch?.let { put(EPOCH_LABEL, it.toString()) }
        put(CLUSTER_LABEL, clusterId)
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** Every object the driver creates carries this label. */
        const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
        const val MANAGED_BY = "ostrom"
        const val SESSION_LABEL = "ostrom.run/session"
        const val EPOCH_LABEL = "ostrom.run/epoch"
        const val EGRESS_LABEL = "ostrom.run/egress"

        /** The cluster a session object was made on (TeamCode's `CLUSTER_LABEL`): the garbage collection's selector. */
        const val CLUSTER_LABEL = "ostrom.run/cluster"
        const val RESTRICTED_POLICY = "ostrom-sessions-restricted"
        const val OPEN_POLICY = "ostrom-sessions-open"

        /** The bandwidth of a free plan's pod, enforced by CNIs with the bandwidth plugin. */
        const val RESTRICTED_BANDWIDTH = "20M"

        /** The hidden commands of `ostrom` the session's containers run. */
        const val CLOUD_RUNNER = "runner"
        const val CLOUD_AGENT = "agent-supervisor"
        const val CLOUD_CHECKS = "check-host"

        /** The runner's projected service-account token, with pod identity (TeamCode's `runner-token`). */
        const val POD_TOKEN_VOLUME = "ostrom-pod-token"
        const val POD_TOKEN_DIRECTORY = "/var/run/secrets/ostrom"

        /** The projected token's lifetime, the API's minimum (TeamCode's 600 s); the kubelet rotates the file. */
        const val POD_TOKEN_SECONDS = 600

        /** The files of members' messages (TeamCode's `uploads`), at [RunnerEnvironment.UPLOADS_DIR]. */
        const val UPLOADS_VOLUME = "uploads"

        /** Above the runner's own bound of 768 MiB per execution: going over the limit would evict the pod. */
        private const val UPLOADS_MEBIBYTES = 1024
        const val UPLOADS_SIZE_LIMIT = "${UPLOADS_MEBIBYTES}Mi"

        /** The runner's ephemeral storage: its own 64 MiB and the uploads volume's [UPLOADS_SIZE_LIMIT]. */
        const val RUNNER_DISK_LIMIT = "${RUNNER_OWN_DISK_MEBIBYTES + UPLOADS_MEBIBYTES}Mi"

        /** What the runner itself may write (logs), before the uploads volume. */
        private const val RUNNER_OWN_DISK_MEBIBYTES = 64

        /** The volume the init container copies `ostrom` into. */
        const val BINARY_VOLUME = "ostrom-bin"
        private const val BINARY_VOLUME_SIZE = "256Mi"
        const val WORKSPACE = "/workspace"
        /** The agent's uid (TeamCode's `agent`, 1001), owner of the workspace; the check host runs as it too. */
        const val AGENT_UID = 1001

        /** The runner's uid (TeamCode's `runner`, 1000): never the agent's (S2-49). */
        const val RUNNER_UID = 1000

        /** The group of every container of the session (`runAsGroup`) and the pod's fsGroup, TeamCode's 1000. */
        const val SHARED_GID = 1000
        const val GRACE_SECONDS = 20
        private const val EPHEMERAL_REQUEST = "64Mi"

        /** Either annotation marks a cluster's default storage class. */
        private val DEFAULT_CLASS_ANNOTATIONS = listOf(
            "storageclass.kubernetes.io/is-default-class",
            "storageclass.beta.kubernetes.io/is-default-class",
        )

        /** Items asked per page of a list. */
        private const val PAGE_SIZE = 500

        /** Pages read of a namespaced list. */
        private const val MAX_PAGES = 20

        /** Pages read of the cluster's PersistentVolumes, which carry no label of ours (TeamCode's 200). */
        private const val MAX_VOLUME_PAGES = 200

        /** A storage class as the API returns it; no policy means `Delete`, the API's default. */
        fun storageClass(json: JsonObject): StorageClassObservation =
            StorageClassObservation(json.textAt("metadata", "name").orEmpty(), json.textAt("reclaimPolicy") ?: "Delete")

        /** A PersistentVolume as the API returns it; no policy is read as `Retain`, which keeps the provider's. */
        fun volume(json: JsonObject): VolumeObservation = VolumeObservation(
            name = json.textAt("metadata", "name").orEmpty(),
            phase = json.textAt("status", "phase") ?: "Pending",
            reclaimPolicy = json.textAt("spec", "persistentVolumeReclaimPolicy") ?: "Retain",
            volumeHandle = json.textAt("spec", "csi", "volumeHandle"),
            claimNamespace = json.textAt("spec", "claimRef", "namespace"),
            claimName = json.textAt("spec", "claimRef", "name"),
        )

        /**
         * The pod a TokenReview binds a token to (TeamCode's `reviewToken`).
         *
         * @param review The TokenReview as the API returns it.
         * @param audience The audience the token must carry.
         *
         * @return The service account's namespace and the pod's name, or null when the token is not authenticated
         *   for [audience] or is not a service account's.
         */
        // Each field the review lacks or contradicts refuses the token.
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        fun tokenIdentity(review: JsonObject, audience: String): PodTokenIdentity? {
            val status = review["status"] as? JsonObject ?: return null
            if ((status["authenticated"] as? JsonPrimitive)?.content != "true") return null
            val audiences = (status["audiences"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            if (audience !in audiences) return null
            val user = status["user"] as? JsonObject ?: return null
            val parts = (user.textAt("username") ?: return null).split(':')
            if (parts.size != SERVICE_ACCOUNT_PARTS || parts[0] != "system" || parts[1] != "serviceaccount") return null
            val pod = ((user["extra"] as? JsonObject)?.get(POD_NAME_EXTRA) as? JsonArray)?.singleOrNull()
            return PodTokenIdentity(parts[2], (pod as? JsonPrimitive)?.content)
        }

        /** `system:serviceaccount:<namespace>:<name>`. */
        private const val SERVICE_ACCOUNT_PARTS = 4
        private const val POD_NAME_EXTRA = "authentication.kubernetes.io/pod-name"

        /** The egress label value of an execution. */
        fun egress(spec: ExecutionSpec): String = if (spec.limits.restrictedEgress) "restricted" else "open"

        /**
         * Maps a pod to an execution state.
         *
         * @param pod The pod as the API returns it.
         *
         * @return The state.
         */
        fun podStatus(pod: JsonObject): ExecutionStatus {
            val status = pod["status"] as? JsonObject ?: return ExecutionStatus.PENDING
            val containers = status["containerStatuses"] as? JsonArray ?: JsonArray(emptyList())
            val waiting = containers.mapNotNull { it.textAt("state", "waiting", "reason") }
            val phase = status.textAt("phase")
            return when {
                waiting.any { it in FATAL_WAITING } -> ExecutionStatus.FAILED
                phase == "Running" -> ExecutionStatus.RUNNING
                phase == "Succeeded" || phase == "Failed" -> ExecutionStatus.ENDED
                else -> ExecutionStatus.PENDING
            }
        }

        /** Waiting reasons a pod never recovers from (TeamCode: image name errors fail the start at once). */
        private val FATAL_WAITING = setOf("InvalidImageName", "ErrImageNeverPull", "CreateContainerConfigError")
    }
}

/**
 * A call to the Kubernetes API failed. The message names the step and the status only, never the API's body.
 *
 * @param step What the driver was doing.
 * @param status The HTTP status.
 */
class KubernetesApiException(step: String, status: HttpStatusCode) :
    Exception("Kubernetes API: $step answered ${status.value}.")

private fun HttpResponse.requireSuccess(step: String) {
    if (!status.isSuccess()) throw KubernetesApiException(step, status)
}

// A cluster-scoped read the service account's role does not allow: PersistentVolumes and storage classes need a
// ClusterRole, which the chart grants only when asked. A namespaced read refused stays a KubernetesApiException.
private fun HttpResponse.requireReadable(step: String, clusterScoped: Boolean) {
    if (clusterScoped && status == HttpStatusCode.Forbidden) throw ClusterReadForbiddenException(step)
    requireSuccess(step)
}
