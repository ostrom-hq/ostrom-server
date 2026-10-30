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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [RuntimeDriver] on a local Docker Engine, through its HTTP API, for tests and self-hosting: a named volume per
 * session and a container per execution, with the same labels, names and runner environment as on Kubernetes.
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
class DockerRuntimeDriver(
    private val client: HttpClient,
    private val settings: DockerSettings,
    private val serverUrl: String,
    private val runnerImage: String,
) : RuntimeDriver {
    private val base = settings.apiUrl.trimEnd('/')

    override suspend fun ensureWorkspace(session: CloudSession) {
        val volume = buildJsonObject {
            put("Name", RuntimeNames.workspace(session.id))
            put("Labels", labels(session.id.toHexString(), null))
        }
        // Creating a volume that exists answers 201 with the existing volume.
        call(HttpMethod.Post, "/volumes/create", volume).requireSuccess("create volume")
    }

    override suspend fun startExecution(spec: ExecutionSpec) {
        val name = RuntimeNames.execution(spec.sessionId, spec.epoch)
        val body = containerManifest(spec)
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
        val state = Json.parseToJsonElement(response.bodyAsText()).jsonObject["State"]?.jsonObject
        return when (state?.get("Status")?.jsonPrimitive?.content) {
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
            .mapNotNull { it.jsonObject["Id"]?.jsonPrimitive?.content }
        for (id in ids) {
            val grace = mapOf("t" to KubernetesRuntimeDriver.GRACE_SECONDS.toString())
            val stopped = call(HttpMethod.Post, "/containers/$id/stop", parameters = grace)
            if (stopped.status !in GONE_OR_DONE) stopped.requireSuccess("stop container")
            val removed = call(HttpMethod.Delete, "/containers/$id", parameters = mapOf("force" to "true"))
            if (removed.status != HttpStatusCode.NotFound) removed.requireSuccess("remove container")
        }
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        val path = "/volumes/${RuntimeNames.workspace(session.id)}"
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

    private fun containerManifest(spec: ExecutionSpec): JsonObject = buildJsonObject {
        put("Image", runnerImage)
        putJsonArray("Cmd") { add(JsonPrimitive(KubernetesRuntimeDriver.RUNNER_BINARY)) }
        put("WorkingDir", KubernetesRuntimeDriver.WORKSPACE)
        put("User", "${KubernetesRuntimeDriver.RUNNER_UID}:${KubernetesRuntimeDriver.RUNNER_UID}")
        putJsonArray("Env") {
            RunnerEnvironment.of(spec, serverUrl, null).forEach { (key, value) -> add(JsonPrimitive("$key=$value")) }
        }
        put(
            "Labels",
            JsonObject(
                labels(spec.sessionId.toHexString(), spec.epoch) +
                    (KubernetesRuntimeDriver.EGRESS_LABEL to JsonPrimitive(KubernetesRuntimeDriver.egress(spec))),
            ),
        )
        putJsonObject("HostConfig") {
            val bind = "${RuntimeNames.workspace(spec.sessionId)}:${KubernetesRuntimeDriver.WORKSPACE}"
            putJsonArray("Binds") { add(JsonPrimitive(bind)) }
            settings.network?.let { put("NetworkMode", it) }
            putJsonArray("ExtraHosts") { add(JsonPrimitive("host.docker.internal:host-gateway")) }
            putJsonObject("RestartPolicy") { put("Name", "no") }
            putJsonArray("CapDrop") { add(JsonPrimitive("ALL")) }
            putJsonArray("SecurityOpt") { add(JsonPrimitive("no-new-privileges")) }
            put("ReadonlyRootfs", true)
            putJsonObject("Tmpfs") { put("/tmp", "rw,nosuid,size=1g") }
            put("PidsLimit", PIDS_LIMIT)
            put("Memory", MEMORY_BYTES)
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
