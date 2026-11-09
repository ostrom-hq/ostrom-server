// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.ExecutionPlan
import run.ostrom.server.runners.domain.ExecutionPlanner
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.PlannedObject
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
 * What a session is on a Docker Engine: three named volumes per session (the workspace, the agent socket and the check
 * socket) and three containers per execution, with the same labels, names and runner environment as on Kubernetes. As
 * in the pod, the agent host runs the agent in its own container under its own uid, the check host runs the hooks'
 * checks in another, and only the runner's container has the token (S2-49).
 *
 * Bodies of the Engine API (`POST /volumes/create`, `POST /containers/create`), computed once here for whoever creates
 * them: [DockerRuntimeDriver] on the server's own engine, or a registered runner on its machine ([plan]).
 *
 * @param network The containers' network, or null for the engine's default.
 * @param serverUrl The URL the runner reaches the server at.
 * @param runnerImage The session container's image.
 */
class DockerExecutionPlan(
    private val network: String?,
    private val serverUrl: String,
    private val runnerImage: String,
) : ExecutionPlanner {
    override fun plan(spec: ExecutionSpec): ExecutionPlan = ExecutionPlan(volumes(spec.sessionId), containers(spec))

    /**
     * The session's volumes, workspace first.
     *
     * @param sessionId The session.
     *
     * @return Their names and creation bodies.
     */
    fun volumes(sessionId: Uuid): List<PlannedObject> =
        listOf(RuntimeNames.workspace(sessionId), socketVolume(sessionId), checkVolume(sessionId)).map { name ->
            PlannedObject(
                name,
                buildJsonObject {
                    put("Name", name)
                    put("Labels", labels(sessionId.toHexString(), null))
                },
            )
        }

    /**
     * The execution's containers, in the order they start: the hosts first, since the runner connects to their
     * sockets.
     *
     * @param spec The execution.
     * @param secrets Whether the runner's container holds its secrets (the token, the first message); without them,
     *   the plan can be kept or shown.
     *
     * @return Their names and creation bodies.
     */
    fun containers(spec: ExecutionSpec, secrets: Boolean = true): List<PlannedObject> {
        val name = RuntimeNames.execution(spec.sessionId, spec.epoch)
        return listOf(
            PlannedObject(agentContainer(name), agentManifest(spec)),
            PlannedObject(checkContainer(name), checkManifest(spec)),
            PlannedObject(name, runnerManifest(spec, secrets)),
        )
    }

    /** The agent host's container: the agent's uid, the workspace and the socket, no token. */
    private fun agentManifest(spec: ExecutionSpec): JsonObject = container(spec, Role.AGENT, MEMORY_BYTES) {
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

    /** The check host's container: the agent's uid, the workspace and the check socket, no token. */
    private fun checkManifest(spec: ExecutionSpec): JsonObject = container(spec, Role.CHECKS, CHECK_MEMORY_BYTES) {
        putJsonArray("Entrypoint") {
            add(JsonPrimitive(KubernetesRuntimeDriver.RUNNER_BINARY))
            add(JsonPrimitive("check-host"))
        }
        put("WorkingDir", KubernetesRuntimeDriver.WORKSPACE)
        putJsonArray("Env") {
            add(JsonPrimitive("OSTROM_CHECK_HOST_SOCKET=${RunnerEnvironment.CHECK_HOST_SOCKET}"))
            add(JsonPrimitive("HOME=/tmp"))
        }
    }

    /** The runner's container: its own uid, the shared group for the sockets, the token, and no workspace. */
    private fun runnerManifest(spec: ExecutionSpec, secrets: Boolean): JsonObject =
        container(spec, Role.RUNNER, RUNNER_MEMORY_BYTES) {
            putJsonArray("Entrypoint") { add(JsonPrimitive(KubernetesRuntimeDriver.RUNNER_BINARY)) }
            putJsonArray("Env") {
                val environment = RunnerEnvironment.of(spec, serverUrl)
                (if (secrets) environment + RunnerEnvironment.secrets(spec) else environment)
                    .forEach { (key, value) -> add(JsonPrimitive("$key=$value")) }
            }
        }

    private fun container(
        spec: ExecutionSpec,
        role: Role,
        memory: Long,
        body: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("Image", runnerImage)
        put("User", if (role == Role.RUNNER) RUNNER_USER else AGENT_USER)
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
            val check = "${checkVolume(spec.sessionId)}:${RunnerEnvironment.CHECK_SOCKET_DIRECTORY}"
            val workspace = "${RuntimeNames.workspace(spec.sessionId)}:${KubernetesRuntimeDriver.WORKSPACE}"
            // The agent never mounts the check socket, the check host never the agent socket.
            putJsonArray("Binds") {
                if (role != Role.RUNNER) add(JsonPrimitive(workspace))
                if (role != Role.CHECKS) add(JsonPrimitive(socket))
                if (role != Role.AGENT) add(JsonPrimitive(check))
            }
            if (role == Role.RUNNER) {
                putJsonArray("GroupAdd") { add(JsonPrimitive(KubernetesRuntimeDriver.SHARED_GID.toString())) }
            }
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
        private const val AGENT_USER = "${KubernetesRuntimeDriver.AGENT_UID}:${KubernetesRuntimeDriver.AGENT_UID}"
        private const val RUNNER_USER = "${KubernetesRuntimeDriver.RUNNER_UID}:${KubernetesRuntimeDriver.RUNNER_UID}"
        private const val NANO_CPUS = 2_000_000_000L
        private const val PIDS_LIMIT = 4096

        /** The agent host's container of an execution. */
        fun agentContainer(execution: String): String = "$execution-agent"

        /** The check host's container of an execution. */
        fun checkContainer(execution: String): String = "$execution-checks"

        /** The volume of a session's check socket. */
        fun checkVolume(sessionId: Uuid): String = "${RuntimeNames.workspace(sessionId)}-checks"

        /** The volume of a session's agent socket. */
        fun socketVolume(sessionId: Uuid): String = "${RuntimeNames.workspace(sessionId)}-socket"
    }
}

/** Which of an execution's containers. */
private enum class Role { AGENT, CHECKS, RUNNER }
