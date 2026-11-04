// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

/** The machine's Docker Engine, as a registered runner uses it: only the objects labelled as this runner's. */
interface DockerEngine {
    /**
     * Reads the machine's processors and memory, as the engine sees them.
     *
     * @return Them.
     */
    suspend fun info(): EngineInfo

    /**
     * Lists this runner's containers.
     *
     * @return Them, whatever their state.
     */
    suspend fun containers(): List<HeldContainer>

    /**
     * Lists this runner's volumes.
     *
     * @return Them.
     */
    suspend fun volumes(): List<HeldVolume>

    /**
     * Creates a volume; one that exists is kept.
     *
     * @param volume Its name and body.
     */
    suspend fun createVolume(volume: PlannedObject)

    /**
     * Creates and starts a container, pulling its image when the engine does not have it; one that exists is started.
     *
     * @param container Its name and body.
     */
    suspend fun startContainer(container: PlannedObject)

    /**
     * Stops and removes a container; one already gone is fine.
     *
     * @param id The container.
     */
    suspend fun removeContainer(id: String)

    /**
     * Removes a volume; one already gone, or still used by a container being removed, is left to the next pass.
     *
     * @param name The volume.
     */
    suspend fun removeVolume(name: String)
}

/**
 * A container of this runner.
 *
 * @property id Its id.
 * @property name Its name.
 * @property sessionId Its session.
 * @property epoch Its execution.
 * @property state The engine's state (`created`, `running`, `exited`…).
 */
data class HeldContainer(val id: String, val name: String, val sessionId: Uuid, val epoch: Long, val state: String)

/**
 * The machine's processors and memory.
 *
 * @property cpus Its processors (`NCPU`).
 * @property memoryBytes Its memory (`MemTotal`).
 */
data class EngineInfo(val cpus: Int, val memoryBytes: Long)

/**
 * A volume of this runner.
 *
 * @property name Its name.
 * @property sessionId Its session.
 */
data class HeldVolume(val name: String, val sessionId: Uuid)

/**
 * [DockerEngine] over the Engine's HTTP API (`OSTROM_DOCKER_API_URL`; for the local socket, a socket proxy on the
 * loopback), confined to the objects labelled with this runner's id.
 *
 * @param client An HTTP client that does not throw on an error status, with a timeout long enough for an image pull.
 * @param apiUrl The Engine's URL.
 * @param runnerId This runner.
 */
class HttpDockerEngine(private val client: HttpClient, apiUrl: String, runnerId: Uuid) : DockerEngine {
    private val base = apiUrl.trimEnd('/')
    private val mine = """{"label":["${PlanGuard.RUNNER_LABEL}=$runnerId"]}"""

    override suspend fun info(): EngineInfo {
        val read = call(HttpMethod.Get, "/info")
        read.requireSuccess("read the engine")
        val info = LISTING.decodeFromString(ListedInfo.serializer(), read.bodyAsText())
        return EngineInfo(info.cpus, info.memory)
    }

    override suspend fun containers(): List<HeldContainer> {
        val listed = call(HttpMethod.Get, "/containers/json", parameters = mapOf("all" to "true", "filters" to mine))
        listed.requireSuccess("list containers")
        return LISTING.decodeFromString(ListSerializer(ListedContainer.serializer()), listed.bodyAsText())
            .mapNotNull { container ->
                val session = container.labels[SESSION_LABEL]?.let(::sessionOf) ?: return@mapNotNull null
                val epoch = container.labels[EPOCH_LABEL]?.toLongOrNull() ?: return@mapNotNull null
                val name = container.names.firstOrNull().orEmpty().trimStart('/')
                HeldContainer(container.id, name, session, epoch, container.state)
            }
    }

    override suspend fun volumes(): List<HeldVolume> {
        val listed = call(HttpMethod.Get, "/volumes", parameters = mapOf("filters" to mine))
        listed.requireSuccess("list volumes")
        // The engine answers `"Volumes": null` when there is none.
        return LISTING.decodeFromString(ListedVolumes.serializer(), listed.bodyAsText()).volumes.orEmpty()
            .mapNotNull { volume ->
                volume.labels?.get(SESSION_LABEL)?.let(::sessionOf)?.let { HeldVolume(volume.name, it) }
            }
    }

    override suspend fun createVolume(volume: PlannedObject) {
        // Creating a volume that exists answers 201 with the existing volume.
        call(HttpMethod.Post, "/volumes/create", volume.body).requireSuccess("create volume")
    }

    override suspend fun startContainer(container: PlannedObject) {
        val name = mapOf("name" to container.name)
        var created = call(HttpMethod.Post, "/containers/create", container.body, name)
        if (created.status == HttpStatusCode.NotFound) {
            // The plan guard has checked that the image is text.
            pull(container.body.getValue("Image").jsonPrimitive.content)
            created = call(HttpMethod.Post, "/containers/create", container.body, name)
        }
        // 409: it exists, from a pass cut short.
        if (created.status != HttpStatusCode.Conflict) created.requireSuccess("create container")
        val started = call(HttpMethod.Post, "/containers/${container.name}/start")
        // 304: already started.
        if (started.status != HttpStatusCode.NotModified) started.requireSuccess("start container")
    }

    override suspend fun removeContainer(id: String) {
        val stopped = call(HttpMethod.Post, "/containers/$id/stop", parameters = mapOf("t" to STOP_GRACE_SECONDS))
        if (stopped.status != HttpStatusCode.NotModified && stopped.status != HttpStatusCode.NotFound) {
            stopped.requireSuccess("stop container")
        }
        val removed = call(HttpMethod.Delete, "/containers/$id", parameters = mapOf("force" to "true"))
        if (removed.status != HttpStatusCode.NotFound) removed.requireSuccess("remove container")
    }

    override suspend fun removeVolume(name: String) {
        val removed = call(HttpMethod.Delete, "/volumes/$name")
        if (removed.status != HttpStatusCode.NotFound && removed.status != HttpStatusCode.Conflict) {
            removed.requireSuccess("remove volume")
        }
    }

    private suspend fun pull(reference: String) {
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
        /** The label the server's plan gives every object: its session (the same as on Kubernetes). */
        const val SESSION_LABEL = "ostrom.run/session"

        /** The label the server's plan gives every container: its execution. */
        const val EPOCH_LABEL = "ostrom.run/epoch"

        /** As on Kubernetes: the runner has this long to report its last state. */
        private const val STOP_GRACE_SECONDS = "10"

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
 * @param step What the runner was doing.
 * @param status The HTTP status.
 */
class DockerApiException(step: String, status: HttpStatusCode) :
    Exception("Docker Engine API: $step answered ${status.value}.")

private fun HttpResponse.requireSuccess(step: String) {
    if (!status.isSuccess()) throw DockerApiException(step, status)
}

/** A session's id from its label (32 hex digits), or null. */
private fun sessionOf(hex: String): Uuid? = if (HEX.matches(hex)) Uuid.parseHex(hex) else null

private val HEX = Regex("[0-9a-f]{32}")

/** The engine's listings carry much more than a runner reads. */
private val LISTING = Json { ignoreUnknownKeys = true }

/** A container in `GET /containers/json`. */
@Serializable
private class ListedContainer(
    @SerialName("Id") val id: String,
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("Labels") val labels: Map<String, String> = emptyMap(),
    @SerialName("State") val state: String = "",
)

/** The answer of `GET /volumes`. */
@Serializable
private class ListedInfo(@SerialName("NCPU") val cpus: Int, @SerialName("MemTotal") val memory: Long)

@Serializable
private class ListedVolumes(@SerialName("Volumes") val volumes: List<ListedVolume>? = null)

/** A volume in `GET /volumes`. */
@Serializable
private class ListedVolume(
    @SerialName("Name") val name: String,
    @SerialName("Labels") val labels: Map<String, String>? = null,
)
