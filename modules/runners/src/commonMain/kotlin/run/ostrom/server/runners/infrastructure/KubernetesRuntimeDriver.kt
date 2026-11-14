// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
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
 * (`restartPolicy: Never`) per execution, and two NetworkPolicies shared by the session pods of a namespace. The
 * cluster's service account needs a namespaced Role only (`deploy/runners-rbac.yaml`); no cluster-wide object (CRD,
 * ClusterRole, PersistentVolume) is used.
 *
 * @param clusters Reaches a session's cluster.
 * @param settings The pods' priority class and the server pods' labels.
 * @param delivery The runner image, its binary and the URL the runner reaches the server at.
 */
@Suppress("TooManyFunctions") // The port's operations plus one builder per Kubernetes object.
class KubernetesRuntimeDriver(
    private val clusters: KubernetesClusters,
    private val settings: KubernetesSettings,
    private val delivery: RunnerDeliverySource,
) : RuntimeDriver {
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
        val claim = buildJsonObject {
            put("apiVersion", "v1")
            put("kind", "PersistentVolumeClaim")
            putJsonObject("metadata") {
                put("name", RuntimeNames.workspace(session.id))
                put("labels", labels(session.id, null))
            }
            putJsonObject("spec") {
                putJsonArray("accessModes") { add(JsonPrimitive("ReadWriteOnce")) }
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
                put("labels", labels(spec.sessionId, spec.epoch))
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
            // The claim is gone once the API no longer lists it; with the storage class's Delete policy, the
            // provisioner then deletes the volume (reading PersistentVolumes would need a cluster-wide role).
            scope.call(HttpMethod.Get, path).status == HttpStatusCode.NotFound
        }
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
            put("labels", JsonObject(labels(spec.sessionId, spec.epoch) + egressLabel))
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
            // nothing of its own on a volume the agent mounts, so the group shares the agent's side only.
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
                        put("resources", resources("50m", "64Mi", "500m", "256Mi", "64Mi"))
                        putJsonArray("volumeMounts") {
                            add(mount("agent-socket", RunnerEnvironment.AGENT_SOCKET_DIRECTORY))
                            add(mount("check-socket", RunnerEnvironment.CHECK_SOCKET_DIRECTORY))
                            add(binaryMount())
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
    ): HttpResponse {
        val response = try {
            target.client.request(url) {
                this.method = method
                bearerAuth(target.token)
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

    private fun labels(sessionId: Uuid, epoch: Long?): JsonObject = buildJsonObject {
        put(MANAGED_BY_LABEL, MANAGED_BY)
        put(SESSION_LABEL, sessionId.toHexString())
        epoch?.let { put(EPOCH_LABEL, it.toString()) }
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** Every object the driver creates carries this label. */
        const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
        const val MANAGED_BY = "ostrom"
        const val SESSION_LABEL = "ostrom.run/session"
        const val EPOCH_LABEL = "ostrom.run/epoch"
        const val EGRESS_LABEL = "ostrom.run/egress"
        const val RESTRICTED_POLICY = "ostrom-sessions-restricted"
        const val OPEN_POLICY = "ostrom-sessions-open"

        /** The bandwidth of a free plan's pod, enforced by CNIs with the bandwidth plugin. */
        const val RESTRICTED_BANDWIDTH = "20M"

        /** The hidden commands of `ostrom` the session's containers run. */
        const val CLOUD_RUNNER = "runner"
        const val CLOUD_AGENT = "agent-supervisor"
        const val CLOUD_CHECKS = "check-host"

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
