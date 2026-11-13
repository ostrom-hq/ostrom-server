// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Host protocol v1: the internal HTTP contract between the server's MCP relay and this host, the same as TeamCode's
 * (docs/mcp.md, Host protocol v1), so either side works with the other's. Every `/v1/…` request carries
 * `TeamCode-Host-Protocol: 1` and `Authorization: Bearer <OSTROM_MCP_HOST_TOKEN>`.
 */
object HostProtocol {
    /** The protocol header, TeamCode's name kept for compatibility. */
    const val HEADER: String = "TeamCode-Host-Protocol"

    /** The protocol version. */
    const val VERSION: String = "1"

    /** One operation. */
    const val OPS: String = "/v1/ops"

    /** A start in the background. */
    const val WARM: String = "/v1/warm"

    /** The keys still in use. */
    const val RETAIN: String = "/v1/retain"

    /** An administrator's restart. */
    const val STOP: String = "/v1/stop"

    /** Every process's state. */
    const val STATUS: String = "/v1/status"

    /** A process key: the relay's SHA-256 (hex) of org, server, version, and member and assignment for a member's. */
    val KEY: Regex = Regex("[0-9a-f]{64}")

    /** The JSON of the protocol. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

/** Error codes of an operation or of a refusal. */
object HostErrors {
    /** The process did not start. */
    const val START_FAILED: String = "start_failed"

    /** The process ended during the operation. */
    const val CRASHED: String = "crashed"

    /** No answer within the deadline. */
    const val TIMEOUT: String = "timeout"

    /** The answer exceeds the limit. */
    const val TOO_LARGE: String = "too_large"

    /** Every request slot of the process stayed taken. */
    const val BUSY: String = "busy"

    /** The process answered with something that is not MCP. */
    const val PROTOCOL: String = "protocol"

    /** The key failed too often: no start for a while. */
    const val BACKOFF: String = "backoff"

    /** Every process slot is taken by a busy process. */
    const val CAPACITY: String = "capacity"

    /** The server answered the tools/call with a JSON-RPC error. */
    const val TOOL_ERROR: String = "tool_error"

    /** The definition breaks a host rule. */
    const val INVALID_SPEC: String = "invalid_spec"

    /** A malformed request. */
    const val BAD_REQUEST: String = "bad_request"

    /** A missing or wrong token. */
    const val UNAUTHORIZED: String = "unauthorized"

    /** Another protocol version. */
    const val UNSUPPORTED_PROTOCOL: String = "unsupported_protocol"

    /** The host is stopping. */
    const val SHUTTING_DOWN: String = "shutting_down"
}

/**
 * What to run for one key, at launch only: the host never stores it. [env] and [secrets] become the process
 * environment, `${NAME}` in [args] is replaced by the secret `NAME`; every secret value is scrubbed from what the host
 * returns or keeps. [declaredAt] (epoch milliseconds) freezes dependency resolution (npm `before`, uv
 * `--exclude-newer`).
 *
 * @property launcher `npm` or `pypi`.
 * @property pkg The exactly pinned package.
 * @property args The arguments after the package.
 * @property env The visible variables.
 * @property secrets The secret variables.
 * @property declaredAt When the server was declared, epoch milliseconds.
 */
@Serializable
data class ProcessSpec(
    val launcher: String,
    @SerialName("package") val pkg: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
    val declaredAt: Long,
) {
    /** Never the secret values, nor the arguments (one may hold a pasted credential). */
    override fun toString(): String =
        "ProcessSpec(launcher=$launcher, package=$pkg, args=${args.size}, env=${env.keys}, secrets=${secrets.keys}, " +
            "declaredAt=$declaredAt)"
}

/**
 * One `list` or `call`.
 *
 * @property key The process key.
 * @property spec The definition.
 * @property op `list` or `call`.
 * @property tool The tool of a call.
 * @property arguments The arguments of a call.
 * @property deadlineMs The deadline.
 * @property maxResponseBytes The largest answer accepted.
 */
@Serializable
data class OpsRequest(
    val key: String,
    val spec: ProcessSpec,
    val op: String,
    val tool: String? = null,
    val arguments: JsonObject? = null,
    val deadlineMs: Long,
    val maxResponseBytes: Int,
) {
    /** Never the tool's input. */
    override fun toString(): String =
        "OpsRequest(key=$key, spec=$spec, op=$op, tool=$tool, deadlineMs=$deadlineMs, " +
            "maxResponseBytes=$maxResponseBytes)"
}

/**
 * A refusal or failure.
 *
 * @property code One of [HostErrors].
 * @property message A message without any secret.
 */
@Serializable
data class HostError(val code: String, val message: String)

/**
 * The answer of an operation: exactly one of [result] and [error].
 *
 * @property result `{"tools": […]}` for `list`, the server's `CallToolResult` for `call`.
 * @property error The failure.
 * @property cold Whether the operation waited for a start.
 * @property state The key's state afterwards.
 */
@Serializable
data class OpsResponse(
    val result: JsonObject? = null,
    val error: HostError? = null,
    val cold: Boolean,
    val state: String,
)

/**
 * A start in the background.
 *
 * @property key The process key.
 * @property spec The definition.
 */
@Serializable
data class WarmRequest(val key: String, val spec: ProcessSpec)

/**
 * The keys still in use.
 *
 * @property keys Every valid key.
 */
@Serializable
data class RetainRequest(val keys: List<String>)

/**
 * An administrator's restart.
 *
 * @property key The process key.
 */
@Serializable
data class StopRequest(val key: String)

/**
 * A key's state.
 *
 * @property key The process key.
 * @property state Its state.
 */
@Serializable
data class KeyState(val key: String, val state: String)

/**
 * The answer of a retain.
 *
 * @property stopped How many processes it stopped.
 */
@Serializable
data class RetainResponse(val stopped: Int)

/**
 * The answer of a stop.
 *
 * @property stopped Whether the key was known.
 */
@Serializable
data class StopResponse(val stopped: Boolean)

/**
 * One key, for administrators.
 *
 * @property key The process key.
 * @property state Its state.
 * @property launcher Its launcher.
 * @property pkg Its package.
 * @property pid The running process.
 * @property startedAt When it started, epoch milliseconds.
 * @property lastUsedAt When it was last used.
 * @property inFlight Operations using it.
 * @property starts Successful starts.
 * @property failures Failures counted.
 * @property lastExitCode The last exit code.
 * @property lastFailure The last failure.
 * @property coldStartMillis The last start's duration.
 * @property rssBytes The tree's resident memory at the last reading.
 * @property backoffUntil The end of a backoff.
 * @property stderr The last stderr, secrets scrubbed.
 */
@Serializable
data class ProcessStatus(
    val key: String,
    val state: String,
    val launcher: String,
    @SerialName("package") val pkg: String,
    val pid: Long? = null,
    val startedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val inFlight: Int = 0,
    val starts: Int = 0,
    val failures: Int = 0,
    val lastExitCode: Int? = null,
    val lastFailure: String? = null,
    val coldStartMillis: Long? = null,
    val rssBytes: Long? = null,
    val backoffUntil: Long? = null,
    val stderr: String = "",
)

/**
 * The host's status.
 *
 * @property version The host version.
 * @property protocol The protocol version.
 * @property launchers The launchers allowed.
 * @property maxProcesses Processes at once.
 * @property draining Whether it is stopping.
 * @property processes Every key.
 */
@Serializable
data class HostStatus(
    val version: String,
    val protocol: String,
    val launchers: List<String>,
    val maxProcesses: Int,
    val draining: Boolean,
    val processes: List<ProcessStatus>,
)

/** The states of a key. */
object ProcessStates {
    /** Nothing runs. */
    const val IDLE: String = "idle"

    /** A start is in progress. */
    const val STARTING: String = "starting"

    /** A process runs. */
    const val READY: String = "ready"

    /** The last start failed. */
    const val FAILED: String = "failed"

    /** No start until the backoff ends. */
    const val BACKOFF: String = "backoff"
}

/**
 * A refused or failed operation, answered as [HostError]. Its message never holds a secret.
 *
 * @property code One of [HostErrors].
 */
class HostFailure(val code: String, override val message: String) : Exception(message)
