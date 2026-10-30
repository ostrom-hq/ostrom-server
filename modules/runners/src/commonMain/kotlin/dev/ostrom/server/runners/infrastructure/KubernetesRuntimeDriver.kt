// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.ExecutionSpec
import dev.ostrom.server.runners.domain.ExecutionStatus
import dev.ostrom.server.runners.domain.RuntimeDriver
import dev.ostrom.server.runners.domain.RuntimeNames
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
import kotlin.uuid.Uuid

/**
 * [RuntimeDriver] on a Kubernetes namespace, through its REST API (no client library: the surface is a handful of
 * calls, as in TeamCode's `KubernetesClusterPort`).
 *
 * Everything lives in [KubernetesSettings.namespace] (`ostrom` on ControlResell's production cluster, chosen by
 * Lyes): a workspace PersistentVolumeClaim per session, a Secret and a bare Pod (`restartPolicy: Never`) per
 * execution, and two NetworkPolicies shared by the session pods. The server's service account needs a namespaced
 * Role only (`deploy/runners-rbac.yaml`); it never reads or writes anything outside the namespace, and no
 * cluster-wide object (CRD, ClusterRole, PersistentVolume) is used.
 *
 * @param client The HTTP client, trusting the cluster's CA.
 * @param settings The namespace and the workspace volumes.
 * @param serverUrl The URL the runner reaches the server at.
 * @param runnerImage The session pod's image.
 * @param token Reads the service-account token, at every call (the kubelet rotates it).
 */
@Suppress("TooManyFunctions") // The port's operations plus one builder per Kubernetes object.
class KubernetesRuntimeDriver(
    private val client: HttpClient,
    private val settings: KubernetesSettings,
    private val serverUrl: String,
    private val runnerImage: String,
    private val token: () -> String,
) : RuntimeDriver {
    private val base = settings.apiUrl.trimEnd('/')
    private val namespace = settings.namespace

    /** Set once both NetworkPolicies are in place, so that later starts skip them (they never change at runtime). */
    private var policiesApplied = false

    override suspend fun ensureWorkspace(session: CloudSession) {
        val claim = buildJsonObject {
            put("apiVersion", "v1")
            put("kind", "PersistentVolumeClaim")
            putJsonObject("metadata") {
                put("name", RuntimeNames.workspace(session.id))
                put("labels", labels(session.id, null))
            }
            putJsonObject("spec") {
                putJsonArray("accessModes") { add(JsonPrimitive("ReadWriteOnce")) }
                settings.storageClass?.let { put("storageClassName", it) }
                putJsonObject("resources") { putJsonObject("requests") { put("storage", settings.workspaceSize) } }
            }
        }
        create("/api/v1/namespaces/$namespace/persistentvolumeclaims", claim)
    }

    override suspend fun startExecution(spec: ExecutionSpec) {
        applyNetworkPolicies()
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
        create("/api/v1/namespaces/$namespace/secrets", secret)
        create("/api/v1/namespaces/$namespace/pods", podManifest(spec, name))
    }

    override suspend fun executionStatus(session: CloudSession): ExecutionStatus {
        val name = RuntimeNames.execution(session.id, session.epoch)
        val response = call(HttpMethod.Get, "$base/api/v1/namespaces/$namespace/pods/$name")
        if (response.status == HttpStatusCode.NotFound) return ExecutionStatus.MISSING
        response.requireSuccess("read pod")
        val pod = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return podStatus(pod)
    }

    override suspend fun stopExecutions(session: CloudSession) {
        val selector = "$SESSION_LABEL=${session.id.toHexString()}"
        for (kind in listOf("pods", "secrets")) {
            val parameters = mapOf("labelSelector" to selector) +
                if (kind == "pods") mapOf("gracePeriodSeconds" to GRACE_SECONDS.toString()) else emptyMap()
            call(HttpMethod.Delete, "$base/api/v1/namespaces/$namespace/$kind", parameters = parameters)
                .requireSuccess("delete $kind")
        }
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        val path = "$base/api/v1/namespaces/$namespace/persistentvolumeclaims/${RuntimeNames.workspace(session.id)}"
        val deleted = call(HttpMethod.Delete, path)
        if (deleted.status != HttpStatusCode.NotFound) deleted.requireSuccess("delete workspace")
        // The claim is gone once the API no longer lists it; with the storage class's Delete policy, the
        // provisioner then deletes the volume (reading PersistentVolumes would need a cluster-wide role).
        return call(HttpMethod.Get, path).status == HttpStatusCode.NotFound
    }

    /** The session pods' network: no ingress; DNS, the server, and the internet without private ranges. */
    private suspend fun applyNetworkPolicies() {
        if (policiesApplied) return
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
        policiesApplied = true
    }

    // A manifest reads top to bottom like the YAML it stands for: splitting it would scatter one object.
    @Suppress("LongMethod")
    private fun podManifest(spec: ExecutionSpec, name: String): JsonObject = buildJsonObject {
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
            settings.imagePullSecret?.let { secret ->
                putJsonArray("imagePullSecrets") { add(buildJsonObject { put("name", secret) }) }
            }
            // The runner and the agent never share a uid (S2-49): the agent host runs the agent in its own container,
            // which has neither the token nor a view of the runner's processes; the pod's group is only the socket's.
            put("shareProcessNamespace", false)
            putJsonObject("securityContext") {
                put("runAsNonRoot", true)
                put("fsGroup", SHARED_GID)
                putJsonObject("seccompProfile") { put("type", "RuntimeDefault") }
            }
            // A native sidecar (Kubernetes 1.29+): it starts before the runner and stops once the runner has ended.
            putJsonArray("initContainers") {
                add(
                    container("agent", AGENT_UID) {
                        put("restartPolicy", "Always")
                        putJsonArray("command") {
                            add(JsonPrimitive(RUNNER_BINARY))
                            add(JsonPrimitive("agent-host"))
                        }
                        put("workingDir", WORKSPACE)
                        putJsonArray("env") {
                            add(variable("OSTROM_AGENT_SOCKET", RunnerEnvironment.AGENT_SOCKET))
                            add(variable("HOME", "$WORKSPACE/.home"))
                        }
                        put("resources", resources("250m", "512Mi", "2", "4Gi", "2Gi"))
                        putJsonArray("volumeMounts") {
                            add(mount("workspace", WORKSPACE))
                            add(mount("tmp", "/tmp"))
                            add(mount("agent-socket", RunnerEnvironment.AGENT_SOCKET_DIRECTORY))
                        }
                    },
                )
            }
            putJsonArray("containers") {
                add(
                    container("runner", RUNNER_UID) {
                        putJsonArray("command") { add(JsonPrimitive(RUNNER_BINARY)) }
                        put("env", environment(spec, name))
                        put("resources", resources("50m", "64Mi", "500m", "256Mi", "64Mi"))
                        putJsonArray("volumeMounts") {
                            add(mount("agent-socket", RunnerEnvironment.AGENT_SOCKET_DIRECTORY))
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
                add(
                    buildJsonObject {
                        put("name", "tmp")
                        putJsonObject("emptyDir") { put("sizeLimit", "1Gi") }
                    },
                )
                add(
                    buildJsonObject {
                        put("name", "agent-socket")
                        putJsonObject("emptyDir") { put("sizeLimit", "1Mi") }
                    },
                )
            }
        }
    }

    /** A container of the session pod: its own uid and group, no privilege, a read-only root. */
    private fun container(name: String, uid: Int, body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("name", name)
        put("image", runnerImage)
        putJsonObject("securityContext") {
            put("runAsUser", uid)
            put("runAsGroup", uid)
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
    private fun environment(spec: ExecutionSpec, secret: String): JsonArray = buildJsonArray {
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

    private suspend fun create(path: String, body: JsonObject) {
        val response = call(HttpMethod.Post, "$base$path", body)
        // Creating what exists is a success: every step can be repeated after a crash.
        if (response.status != HttpStatusCode.Conflict) {
            response.requireSuccess("create ${body.textAt("kind")}")
        }
    }

    private suspend fun call(
        method: HttpMethod,
        url: String,
        body: JsonObject? = null,
        parameters: Map<String, String> = emptyMap(),
    ): HttpResponse = client.request(url) {
        this.method = method
        bearerAuth(token())
        parameters.forEach { (key, value) -> parameter(key, value) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
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

        const val RUNNER_BINARY = "/usr/local/bin/ostrom-runner"
        const val WORKSPACE = "/workspace"
        /** The agent's uid (the image's `node` user), owner of the workspace. */
        const val AGENT_UID = 1000

        /** The runner's uid: never the agent's (S2-49). */
        const val RUNNER_UID = 1001

        /** The group both containers share, for the agent socket only (the pod's fsGroup). */
        const val SHARED_GID = 2000
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
