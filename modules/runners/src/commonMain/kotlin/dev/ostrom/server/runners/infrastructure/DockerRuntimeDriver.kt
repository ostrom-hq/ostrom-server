// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.ExecutionSpec
import dev.ostrom.server.runners.domain.ExecutionStatus
import dev.ostrom.server.runners.domain.RuntimeDriver
import dev.ostrom.server.runners.domain.RuntimeNames
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.Uuid

/**
 * [RuntimeDriver] on a local Docker Engine, through its HTTP API, for tests and self-hosting: two named volumes per
 * session (the workspace and the agent socket) and two containers per execution, with the same labels, names and
 * runner environment as on Kubernetes. As in the pod, the agent host runs the agent in its own container under its
 * own uid, and only the runner's container has the token (S2-49).
 *
 * The engine is reached over TCP ([DockerSettings.apiUrl]; for the local socket, expose it on the loopback, for
 * example with a socket proxy). The free plan's egress restriction is not enforced here: it is a Kubernetes
 * NetworkPolicy, and a self-hosted instance chooses its own network ([DockerSettings.network]).
 *
 * @param client The HTTP client.
 * @param settings The engine's URL and network.
 * @param serverUrl The URL the runner reaches the server at.
 * @param runnerImage The session container's image.
 */
@Suppress("TooManyFunctions") // The port's operations plus one builder per container and volume.
class DockerRuntimeDriver(
    private val client: HttpClient,
    private val settings: DockerSettings,
    private val serverUrl: String,
    private val runnerImage: String,
) : RuntimeDriver {
    private val base = settings.apiUrl.trimEnd('/')

    override suspend fun ensureWorkspace(session: CloudSession) {
        // Creating a volume that exists answers 201 with the existing volume. The socket's volume takes the owner and
        // mode of the image's /run/ostrom when the agent's container first mounts it.
        for (name in listOf(RuntimeNames.workspace(session.id), socketVolume(session.id))) {
            val volume = buildJsonObject {
                put("Name", name)
                put("Labels", labels(session.id.toHexString(), null))
            }
            call(HttpMethod.Post, "/volumes/create", volume).requireSuccess("create volume")
        }
    }

    override suspend fun startExecution(spec: ExecutionSpec) {
        val name = RuntimeNames.execution(spec.sessionId, spec.epoch)
        // The agent host first: the runner connects to its socket.
        createAndStart(agentContainer(name), agentManifest(spec))
        createAndStart(name, runnerManifest(spec))
    }

    private suspend fun createAndStart(name: String, body: JsonObject) {
        var created = call(HttpMethod.Post, "/containers/create", body, mapOf("name" to name))
        if (created.status == HttpStatusCode.NotFound) {
            pullImage()
            created = call(HttpMethod.Post, "/containers/create", body, mapOf("name" to name))
        }
        if (created.status != HttpStatusCode.Conflict) created.requireSuccess("create container")
        val started = call(HttpMethod.Post, "/containers/$name/start")
        // 304: already started, after a crash between the two calls.
        if (started.status != HttpStatusCode.NotModified) started.requireSuccess("start container")
    }

    override suspend fun executionStatus(session: CloudSession): ExecutionStatus {
        val response = call(HttpMethod.Get, "/containers/${RuntimeNames.execution(session.id, session.epoch)}/json")
        if (response.status == HttpStatusCode.NotFound) return ExecutionStatus.MISSING
        response.requireSuccess("inspect container")
        return when (Json.parseToJsonElement(response.bodyAsText()).textAt("State", "Status")) {
            "running", "paused", "restarting" -> ExecutionStatus.RUNNING
            "exited", "dead" -> ExecutionStatus.ENDED
            else -> ExecutionStatus.PENDING
        }
    }

    override suspend fun stopExecutions(session: CloudSession) {
        val filters = """{"label":["${KubernetesRuntimeDriver.SESSION_LABEL}=${session.id.toHexString()}"]}"""
        val listed = call(HttpMethod.Get, "/containers/json", parameters = mapOf("all" to "true", "filters" to filters))
        listed.requireSuccess("list containers")
        val ids = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
            .map { it.textAt("Id") }
            .filterNotNull()
        for (id in ids) {
            val grace = mapOf("t" to KubernetesRuntimeDriver.GRACE_SECONDS.toString())
            val stopped = call(HttpMethod.Post, "/containers/$id/stop", parameters = grace)
            if (stopped.status !in GONE_OR_DONE) stopped.requireSuccess("stop container")
            val removed = call(HttpMethod.Delete, "/containers/$id", parameters = mapOf("force" to "true"))
            if (removed.status != HttpStatusCode.NotFound) removed.requireSuccess("remove container")
        }
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        val socketGone = removeVolume(socketVolume(session.id))
        val workspaceGone = removeVolume(RuntimeNames.workspace(session.id))
        return socketGone && workspaceGone
    }

    /** Removes a volume; true once it is gone. */
    private suspend fun removeVolume(name: String): Boolean {
        val path = "/volumes/$name"
        val removed = call(HttpMethod.Delete, path)
        // 409: still used by a container being removed; the reconciler asks again.
        if (removed.status == HttpStatusCode.Conflict) return false
        if (removed.status != HttpStatusCode.NotFound) removed.requireSuccess("remove volume")
        return call(HttpMethod.Get, path).status == HttpStatusCode.NotFound
    }

    private suspend fun pullImage() {
        val (image, tag) = splitImage(runnerImage)
        call(HttpMethod.Post, "/images/create", parameters = mapOf("fromImage" to image, "tag" to tag))
            .requireSuccess("pull image")
    }

    /** The agent host's container: the agent's uid, the workspace and the socket, no token. */
    private fun agentManifest(spec: ExecutionSpec): JsonObject = container(spec, AGENT_USER, MEMORY_BYTES) {
        // The whole command as the entrypoint: the image's own is the runner, which would take these as arguments.
        putJsonArray("Entrypoint") {
            add(JsonPrimitive(KubernetesRuntimeDriver.RUNNER_BINARY))
            add(JsonPrimitive("agent-host"))
        }
        put("WorkingDir", KubernetesRuntimeDriver.WORKSPACE)
        putJsonArray("Env") {
            add(JsonPrimitive("OSTROM_AGENT_SOCKET=${RunnerEnvironment.AGENT_SOCKET}"))
            add(JsonPrimitive("HOME=${KubernetesRuntimeDriver.WORKSPACE}/.home"))
        }
    }

    /** The runner's container: its own uid, the shared group for the socket, the token, and nothing else mounted. */
    private fun runnerManifest(spec: ExecutionSpec): JsonObject = container(spec, RUNNER_USER, RUNNER_MEMORY_BYTES) {
        putJsonArray("Entrypoint") { add(JsonPrimitive(KubernetesRuntimeDriver.RUNNER_BINARY)) }
        putJsonArray("Env") {
            (RunnerEnvironment.of(spec, serverUrl) + RunnerEnvironment.secrets(spec))
                .forEach { (key, value) -> add(JsonPrimitive("$key=$value")) }
        }
    }

    private fun container(
        spec: ExecutionSpec,
        user: String,
        memory: Long,
        body: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        val agent = user == AGENT_USER
        put("Image", runnerImage)
        put("User", user)
        body()
        put(
            "Labels",
            JsonObject(
                labels(spec.sessionId.toHexString(), spec.epoch) +
                    (KubernetesRuntimeDriver.EGRESS_LABEL to JsonPrimitive(KubernetesRuntimeDriver.egress(spec))),
            ),
        )
        putJsonObject("HostConfig") {
            val socket = "${socketVolume(spec.sessionId)}:${RunnerEnvironment.AGENT_SOCKET_DIRECTORY}"
            val workspace = "${RuntimeNames.workspace(spec.sessionId)}:${KubernetesRuntimeDriver.WORKSPACE}"
            putJsonArray("Binds") {
                if (agent) add(JsonPrimitive(workspace))
                add(JsonPrimitive(socket))
            }
            if (!agent) putJsonArray("GroupAdd") { add(JsonPrimitive(KubernetesRuntimeDriver.SHARED_GID.toString())) }
            settings.network?.let { put("NetworkMode", it) }
            putJsonArray("ExtraHosts") { add(JsonPrimitive("host.docker.internal:host-gateway")) }
            putJsonObject("RestartPolicy") { put("Name", "no") }
            putJsonArray("CapDrop") { add(JsonPrimitive("ALL")) }
            putJsonArray("SecurityOpt") { add(JsonPrimitive("no-new-privileges")) }
            put("ReadonlyRootfs", true)
            putJsonObject("Tmpfs") { put("/tmp", "rw,nosuid,size=1g") }
            put("PidsLimit", PIDS_LIMIT)
            put("Memory", memory)
            put("NanoCpus", NANO_CPUS)
        }
    }

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        parameters: Map<String, String> = emptyMap(),
    ): HttpResponse = client.request("$base$path") {
        this.method = method
        parameters.forEach { (key, value) -> parameter(key, value) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }

    private fun labels(sessionHex: String, epoch: Long?): JsonObject = buildJsonObject {
        put(KubernetesRuntimeDriver.MANAGED_BY_LABEL, KubernetesRuntimeDriver.MANAGED_BY)
        put(KubernetesRuntimeDriver.SESSION_LABEL, sessionHex)
        epoch?.let { put(KubernetesRuntimeDriver.EPOCH_LABEL, it.toString()) }
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        private val GONE_OR_DONE = setOf(HttpStatusCode.NotModified, HttpStatusCode.NotFound)
        private const val MEMORY_BYTES = 4L * 1024 * 1024 * 1024
        private const val RUNNER_MEMORY_BYTES = 256L * 1024 * 1024
        private const val AGENT_USER = "${KubernetesRuntimeDriver.AGENT_UID}:${KubernetesRuntimeDriver.AGENT_UID}"
        private const val RUNNER_USER = "${KubernetesRuntimeDriver.RUNNER_UID}:${KubernetesRuntimeDriver.RUNNER_UID}"

        /** The agent host's container of an execution. */
        fun agentContainer(execution: String): String = "$execution-agent"

        /** The volume of a session's agent socket. */
        fun socketVolume(sessionId: Uuid): String = "${RuntimeNames.workspace(sessionId)}-socket"
        private const val NANO_CPUS = 2_000_000_000L
        private const val PIDS_LIMIT = 4096

        /**
         * Splits an image reference into the name and the tag the engine's pull expects.
         *
         * @param reference `registry/name:tag`, `name@sha256:…` or `name`.
         *
         * @return The name and the tag (or digest), `latest` when absent.
         */
        fun splitImage(reference: String): Pair<String, String> {
            val at = reference.indexOf('@')
            if (at >= 0) return reference.substring(0, at) to reference.substring(at + 1)
            val colon = reference.lastIndexOf(':')
            return if (colon > reference.lastIndexOf('/')) {
                reference.substring(0, colon) to reference.substring(colon + 1)
            } else {
                reference to "latest"
            }
        }
    }
}

/**
 * A call to the Docker Engine API failed. The message names the step and the status only.
 *
 * @param step What the driver was doing.
 * @param status The HTTP status.
 */
class DockerApiException(step: String, status: HttpStatusCode) :
    Exception("Docker Engine API: $step answered ${status.value}.")

private fun HttpResponse.requireSuccess(step: String) {
    if (!status.isSuccess()) throw DockerApiException(step, status)
}
