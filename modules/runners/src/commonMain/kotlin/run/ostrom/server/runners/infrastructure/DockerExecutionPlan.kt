// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.ExecutionPlan
import run.ostrom.server.runners.domain.ExecutionPlanner
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.PlannedObject
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.domain.RuntimeNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.Uuid

/**
 * What a session is on a Docker Engine, as the Kubernetes pod is: three named volumes per session (the workspace, the
 * agent socket and the check socket), and per execution a binary volume and four containers, with the same labels,
 * names and runner environment as on Kubernetes.
 *
 * The `ostrom` binary is delivered as the pod's init container delivers it (TeamCode's delivery): the execution's
 * delivery container, from the runner image, mounts the empty binary volume over the directory of the binary, so the
 * engine copies that directory into it, and exits at once. The agent host, the check host and the runner then run the
 * session image with the volume read-only at `/opt/ostrom` (`/opt/ostrom/ostrom cloud …`). As in the pod, the agent
 * host runs the agent in its own container under its own uid, the check host runs the hooks' checks in another, and
 * only the runner's container has the token (S2-49).
 *
 * Bodies of the Engine API (`POST /volumes/create`, `POST /containers/create`), computed here for whoever creates them:
 * [DockerRuntimeDriver] on the server's own engine, or a registered runner on its machine ([plan]), whose `PlanGuard`
 * (ostrom-cli) accepts exactly this shape.
 *
 * @param network The containers' network, or null for the engine's default.
 * @param serverUrl The URL the runner reaches the server at.
 * @param delivery The runner image, its binary and the session image, read for each execution.
 */
@Suppress("TooManyFunctions") // One builder per object of the plan, as KubernetesRuntimeDriver's.
class DockerExecutionPlan(
    private val network: String?,
    private val serverUrl: String,
    private val delivery: RunnerDeliverySource,
) : ExecutionPlanner {
    override suspend fun plan(spec: ExecutionSpec): ExecutionPlan = plan(spec, delivery.current())

    /**
     * An execution's plan with a given delivery: the session's volumes, then the execution's binary volume, and its
     * containers.
     *
     * @param spec The execution.
     * @param delivery The images and the binary.
     *
     * @return The plan, with the runner's secrets.
     */
    fun plan(spec: ExecutionSpec, delivery: RunnerDelivery): ExecutionPlan =
        ExecutionPlan(volumes(spec.sessionId) + binaryVolume(spec), containers(spec, delivery))

    /**
     * The session's volumes, workspace first.
     *
     * @param sessionId The session.
     *
     * @return Their names and creation bodies.
     */
    fun volumes(sessionId: Uuid): List<PlannedObject> =
        listOf(RuntimeNames.workspace(sessionId), socketVolume(sessionId), checkVolume(sessionId)).map { name ->
            PlannedObject(name, volumeBody(name, labels(sessionId.toHexString(), null)))
        }

    /**
     * The execution's binary volume: labelled with the session and the epoch, so that the driver and the machines find
     * it and remove it with the execution.
     *
     * @param spec The execution.
     *
     * @return Its name and creation body.
     */
    fun binaryVolume(spec: ExecutionSpec): PlannedObject {
        val name = binaryVolume(spec.sessionId, spec.epoch)
        return PlannedObject(name, volumeBody(name, labels(spec.sessionId.toHexString(), spec.epoch)))
    }

    private fun volumeBody(name: String, labels: JsonObject): JsonObject = buildJsonObject {
        put("Name", name)
        put("Labels", labels)
    }

    /**
     * The execution's containers, in the order they start: the delivery first, since the engine fills the binary volume
     * from the first container that mounts it; then the hosts, since the runner connects to their sockets.
     *
     * @param spec The execution.
     * @param delivery The images and the binary.
     * @param secrets Whether the runner's container holds its secrets (the token, the first message); without them,
     *   the plan can be kept or shown.
     *
     * @return Their names and creation bodies.
     */
    fun containers(spec: ExecutionSpec, delivery: RunnerDelivery, secrets: Boolean = true): List<PlannedObject> {
        val name = RuntimeNames.execution(spec.sessionId, spec.epoch)
        val image = delivery.sessionImage
        return listOf(
            PlannedObject(deliveryContainer(name), deliveryManifest(spec, delivery)),
            PlannedObject(agentContainer(name), agentManifest(spec, image)),
            PlannedObject(checkContainer(name), checkManifest(spec, image)),
            PlannedObject(name, runnerManifest(spec, image, secrets)),
        )
    }

    /**
     * The delivery container: the runner's uid, the binary volume alone, read-write, over the binary's directory. The
     * engine copies that directory of the runner image into the empty volume as it mounts it; the binary then prints
     * its version and the container ends.
     */
    private fun deliveryManifest(spec: ExecutionSpec, delivery: RunnerDelivery): JsonObject =
        container(spec, Role.DELIVERY, delivery.runnerImage, deliveryBinds(spec, delivery), DELIVERY_MEMORY_BYTES) {
            putJsonArray("Entrypoint") {
                add(JsonPrimitive(delivery.runnerBinary))
                add(JsonPrimitive("--version"))
            }
            putJsonArray("Env") { add(JsonPrimitive("HOME=/tmp")) }
        }

    /** The agent host's container: the agent's uid, the workspace and the socket, no token. */
    private fun agentManifest(spec: ExecutionSpec, image: String): JsonObject =
        container(spec, Role.AGENT, image, sessionBinds(spec, Role.AGENT), MEMORY_BYTES) {
            // The whole command as the entrypoint: the image's own one, if any, is never used.
            command(KubernetesRuntimeDriver.CLOUD_AGENT)
            put("WorkingDir", KubernetesRuntimeDriver.WORKSPACE)
            putJsonArray("Env") {
                add(JsonPrimitive("OSTROM_AGENT_SOCKET=${RunnerEnvironment.AGENT_SOCKET}"))
                add(JsonPrimitive("HOME=${KubernetesRuntimeDriver.WORKSPACE}/.home"))
            }
        }

    /** The check host's container: the agent's uid, the workspace and the check socket, no token. */
    private fun checkManifest(spec: ExecutionSpec, image: String): JsonObject =
        container(spec, Role.CHECKS, image, sessionBinds(spec, Role.CHECKS), CHECK_MEMORY_BYTES) {
            command(KubernetesRuntimeDriver.CLOUD_CHECKS)
            put("WorkingDir", KubernetesRuntimeDriver.WORKSPACE)
            putJsonArray("Env") {
                add(JsonPrimitive("OSTROM_CHECK_HOST_SOCKET=${RunnerEnvironment.CHECK_HOST_SOCKET}"))
                add(JsonPrimitive("HOME=/tmp"))
            }
        }

    /** The runner's container: its own uid in the session's group, the token, and no workspace. */
    private fun runnerManifest(spec: ExecutionSpec, image: String, secrets: Boolean): JsonObject =
        container(spec, Role.RUNNER, image, sessionBinds(spec, Role.RUNNER), RUNNER_MEMORY_BYTES) {
            command(KubernetesRuntimeDriver.CLOUD_RUNNER)
            putJsonArray("Env") {
                val environment = RunnerEnvironment.of(spec, serverUrl)
                (if (secrets) environment + RunnerEnvironment.secrets(spec) else environment)
                    .forEach { (key, value) -> add(JsonPrimitive("$key=$value")) }
            }
        }

    /** `/opt/ostrom/ostrom cloud <name>`. */
    private fun JsonObjectBuilder.command(name: String) = putJsonArray("Entrypoint") {
        add(JsonPrimitive(RunnerDelivery.BINARY))
        add(JsonPrimitive("cloud"))
        add(JsonPrimitive(name))
    }

    @Suppress("LongParameterList") // The container, its role, image, mounts and memory, and its own keys.
    private fun container(
        spec: ExecutionSpec,
        role: Role,
        image: String,
        binds: List<String>,
        memory: Long,
        body: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("Image", image)
        put("User", if (role == Role.AGENT || role == Role.CHECKS) AGENT_USER else RUNNER_USER)
        body()
        put(
            "Labels",
            JsonObject(
                labels(spec.sessionId.toHexString(), spec.epoch) +
                    (KubernetesRuntimeDriver.EGRESS_LABEL to JsonPrimitive(KubernetesRuntimeDriver.egress(spec))),
            ),
        )
        putJsonObject("HostConfig") {
            putJsonArray("Binds") { binds.forEach { add(JsonPrimitive(it)) } }
            network?.let { put("NetworkMode", it) }
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

    /** The delivery mounts the binary volume alone, read-write, over the binary's directory in the runner image. */
    private fun deliveryBinds(spec: ExecutionSpec, delivery: RunnerDelivery): List<String> =
        listOf("${binaryVolume(spec.sessionId, spec.epoch)}:${delivery.runnerBinary.substringBeforeLast('/')}")

    /**
     * What a session's container mounts: the agent never the check socket, the check host never the agent socket, the
     * runner never the workspace; each the binary volume, read-only.
     */
    private fun sessionBinds(spec: ExecutionSpec, role: Role): List<String> =
        listOfNotNull(
            "${RuntimeNames.workspace(spec.sessionId)}:${KubernetesRuntimeDriver.WORKSPACE}".takeIf {
                role != Role.RUNNER
            },
            "${socketVolume(spec.sessionId)}:${RunnerEnvironment.AGENT_SOCKET_DIRECTORY}".takeIf {
                role != Role.CHECKS
            },
            "${checkVolume(spec.sessionId)}:${RunnerEnvironment.CHECK_SOCKET_DIRECTORY}".takeIf { role != Role.AGENT },
            "${binaryVolume(spec.sessionId, spec.epoch)}:${RunnerDelivery.DIRECTORY}:ro",
        )

    private fun labels(sessionHex: String, epoch: Long?): JsonObject = buildJsonObject {
        put(KubernetesRuntimeDriver.MANAGED_BY_LABEL, KubernetesRuntimeDriver.MANAGED_BY)
        put(KubernetesRuntimeDriver.SESSION_LABEL, sessionHex)
        epoch?.let { put(KubernetesRuntimeDriver.EPOCH_LABEL, it.toString()) }
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        private const val MEMORY_BYTES = 4L * 1024 * 1024 * 1024
        private const val RUNNER_MEMORY_BYTES = 256L * 1024 * 1024
        private const val CHECK_MEMORY_BYTES = 2L * 1024 * 1024 * 1024
        private const val DELIVERY_MEMORY_BYTES = 64L * 1024 * 1024
        // Both in the session's group, as in the pod: the agent socket's volume is the agent's, setgid to that group.
        private const val AGENT_USER = "${KubernetesRuntimeDriver.AGENT_UID}:${KubernetesRuntimeDriver.SHARED_GID}"
        private const val RUNNER_USER = "${KubernetesRuntimeDriver.RUNNER_UID}:${KubernetesRuntimeDriver.SHARED_GID}"
        private const val NANO_CPUS = 2_000_000_000L
        private const val PIDS_LIMIT = 4096

        /** The suffix of an execution's delivery container and binary volume (ostrom-cli's `PlanGuard`). */
        const val DELIVERY_SUFFIX = "-bin"

        /** The delivery container of an execution. */
        fun deliveryContainer(execution: String): String = "$execution$DELIVERY_SUFFIX"

        /** The agent host's container of an execution. */
        fun agentContainer(execution: String): String = "$execution-agent"

        /** The check host's container of an execution. */
        fun checkContainer(execution: String): String = "$execution-checks"

        /** The volume of a session's check socket. */
        fun checkVolume(sessionId: Uuid): String = "${RuntimeNames.workspace(sessionId)}-checks"

        /** The volume of a session's agent socket. */
        fun socketVolume(sessionId: Uuid): String = "${RuntimeNames.workspace(sessionId)}-socket"

        /** The binary volume of an execution: `ostrom-ws-<32 hex>-e<epoch>-bin`. */
        fun binaryVolume(sessionId: Uuid, epoch: Long): String =
            "${RuntimeNames.workspace(sessionId)}-e$epoch$DELIVERY_SUFFIX"
    }
}

/** Which of an execution's containers. */
private enum class Role { DELIVERY, AGENT, CHECKS, RUNNER }
