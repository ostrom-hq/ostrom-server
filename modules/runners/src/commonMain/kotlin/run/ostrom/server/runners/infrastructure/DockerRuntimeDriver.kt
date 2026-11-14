// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.PlannedObject
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.RuntimeNames
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [RuntimeDriver] on a local Docker Engine, through its HTTP API, for tests and self-hosting: it creates on the engine
 * what [DockerExecutionPlan] describes (three volumes per session; per execution a binary volume, the delivery
 * container that fills it from the runner image, and three containers of the session image, the token in the runner's
 * container only, S2-49). The delivery is read at each execution's start, as on Kubernetes.
 *
 * The engine is reached over TCP ([DockerSettings.apiUrl]; for the local socket, expose it on the loopback, for
 * example with a socket proxy). The free plan's egress restriction is not enforced here: it is a Kubernetes
 * NetworkPolicy, and a self-hosted instance chooses its own network ([DockerSettings.network]).
 *
 * @param client The HTTP client.
 * @param settings The engine's URL and network.
 * @param serverUrl The URL the runner reaches the server at.
 * @param delivery The runner image, its binary and the session image.
 */
class DockerRuntimeDriver(
    private val client: HttpClient,
    settings: DockerSettings,
    serverUrl: String,
    private val delivery: RunnerDeliverySource,
) : RuntimeDriver {
    private val base = settings.apiUrl.trimEnd('/')
    private val plan = DockerExecutionPlan(settings.network, serverUrl, delivery)

    override suspend fun ensureWorkspace(session: CloudSession) {
        // Creating a volume that exists answers 201 with the existing volume. The socket's volume takes the owner and
        // mode of the image's /run/ostrom when the agent's container first mounts it.
        for (volume in plan.volumes(session.id)) {
            call(HttpMethod.Post, "/volumes/create", volume.body).requireSuccess("create volume")
        }
    }

    override suspend fun startExecution(spec: ExecutionSpec) {
        // Empty, so the delivery container's mount fills it; one left by a crashed start already holds the binary.
        call(HttpMethod.Post, "/volumes/create", plan.binaryVolume(spec).body).requireSuccess("create volume")
        for (container in plan.containers(spec, delivery.current())) createAndStart(container)
    }

    private suspend fun createAndStart(container: PlannedObject) {
        val name = container.name
        val body = container.body
        var created = call(HttpMethod.Post, "/containers/create", body, mapOf("name" to name))
        if (created.status == HttpStatusCode.NotFound) {
            // The runner image or the session image, whichever this container runs.
            pullImage(body.getValue("Image").jsonPrimitive.content)
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
        // The binaries end with their executions: the next one gets its own runner image's. One still held (409) is
        // removed at the next stop or with the workspace.
        removeBinaryVolumes(session)
    }

    override suspend fun deleteWorkspace(session: CloudSession): Boolean {
        val binariesGone = removeBinaryVolumes(session)
        val socketGone = removeVolume(DockerExecutionPlan.socketVolume(session.id))
        val checkGone = removeVolume(DockerExecutionPlan.checkVolume(session.id))
        val workspaceGone = removeVolume(RuntimeNames.workspace(session.id))
        return binariesGone && socketGone && checkGone && workspaceGone
    }

    /**
     * Removes the session's binary volumes, whatever their epoch: those with an epoch label (the session's own volumes
     * have none).
     *
     * @return True once every one is gone.
     */
    private suspend fun removeBinaryVolumes(session: CloudSession): Boolean {
        val label = "${KubernetesRuntimeDriver.SESSION_LABEL}=${session.id.toHexString()}"
        val filters = """{"label":["$label","${KubernetesRuntimeDriver.EPOCH_LABEL}"]}"""
        val listed = call(HttpMethod.Get, "/volumes", parameters = mapOf("filters" to filters))
        listed.requireSuccess("list volumes")
        // `Volumes` is null when none matches.
        val volumes = Json.parseToJsonElement(listed.bodyAsText()).jsonObject["Volumes"] as? JsonArray
        val names = volumes.orEmpty().mapNotNull { it.textAt("Name") }
        // Every one is tried, so one still held never keeps the others.
        return names.map { removeVolume(it) }.all { it }
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

    private suspend fun pullImage(reference: String) {
        val (image, tag) = splitImage(reference)
        call(HttpMethod.Post, "/images/create", parameters = mapOf("fromImage" to image, "tag" to tag))
            .requireSuccess("pull image")
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

    /** Constants and readers of the enclosing type. */
    companion object {
        private val GONE_OR_DONE = setOf(HttpStatusCode.NotModified, HttpStatusCode.NotFound)
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
