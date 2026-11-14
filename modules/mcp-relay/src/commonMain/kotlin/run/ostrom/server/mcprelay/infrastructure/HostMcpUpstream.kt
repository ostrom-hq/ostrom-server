// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.mcprelay.domain.HostStatus
import run.ostrom.server.mcprelay.domain.HostedProcessStates
import run.ostrom.server.mcprelay.domain.HostedProcessStatus
import run.ostrom.server.mcprelay.domain.McpHostControl
import run.ostrom.server.mcprelay.domain.McpHostRefusedException
import run.ostrom.server.mcprelay.domain.McpOutcome
import run.ostrom.server.mcprelay.domain.McpUpstream
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.UpstreamResult
import run.ostrom.server.mcprelay.domain.UpstreamTarget
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the MCP host is and how to reach it (`OSTROM_MCP_HOST_URL`, `OSTROM_MCP_HOST_TOKEN`).
 *
 * @property url The host's base URL, internal to the cluster.
 * @property token The shared token.
 */
data class McpHostConfig(val url: String, val token: String) {
    override fun toString(): String = "McpHostConfig(url=$url, token=***)"
}

/**
 * Runs stdio servers through the MCP host over host protocol v1: the same internal contract as TeamCode's host
 * (`nathanfallet/teamcode-mcp`, Apache-2.0), so that host image serves Ostrom until Ostrom ships its own. Each request
 * carries the process key, the whole definition with its secret variables, and the operation; the host keeps no
 * state the relay depends on. A listing is retried once after a host restart, a call never.
 *
 * @param client The HTTP client.
 * @param config The host.
 * @param startAllowance Added to deadlines for a cold process start.
 * @param maxResponseBytes The largest answer accepted.
 */
class HostMcpUpstream(
    private val client: HttpClient,
    private val config: McpHostConfig,
    private val startAllowance: Duration = 45.seconds,
    private val maxResponseBytes: Int = HttpMcpUpstream.DEFAULT_MAX_RESPONSE_BYTES,
) : McpUpstream, McpHostControl {
    override suspend fun listTools(target: UpstreamTarget, deadline: Duration): UpstreamResult<List<JsonObject>> {
        val first = operate(target, "list", null, null, deadline)
        val answer = if (first is HostAnswer.Retryable) operate(target, "list", null, null, deadline) else first
        return when (answer) {
            is HostAnswer.Result -> UpstreamResult.Ok(
                (answer.value["tools"] as? JsonArray)?.mapNotNull {
                    it as? JsonObject
                }.orEmpty(),
                answer.bytes,
            )
            is HostAnswer.Failed -> answer.failure
            is HostAnswer.Retryable -> answer.failure
        }
    }

    override suspend fun callTool(
        target: UpstreamTarget,
        tool: String,
        arguments: JsonObject,
        deadline: Duration,
    ): UpstreamResult<JsonObject> = when (val answer = operate(target, "call", tool, arguments, deadline)) {
        is HostAnswer.Result -> UpstreamResult.Ok(answer.value, answer.bytes)
        is HostAnswer.Failed -> answer.failure
        is HostAnswer.Retryable -> answer.failure
    }

    private sealed interface HostAnswer {
        data class Result(val value: JsonObject, val bytes: Long) : HostAnswer
        data class Failed(val failure: UpstreamResult.Failure) : HostAnswer
        data class Retryable(val failure: UpstreamResult.Failure) : HostAnswer
    }

    private suspend fun operate(
        target: UpstreamTarget,
        op: String,
        tool: String?,
        arguments: JsonObject?,
        deadline: Duration,
    ): HostAnswer {
        val spec = target.server.stdio
            ?: return HostAnswer.Failed(
                UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "The MCP server has no command."),
            )
        val body = buildJsonObject {
            put("key", processKey(target.processKey))
            put("spec", spec(target, spec))
            put("op", op)
            tool?.let { put("tool", it) }
            arguments?.let { put("arguments", it) }
            put("deadlineMs", deadline.inWholeMilliseconds)
            put("maxResponseBytes", maxResponseBytes)
        }
        return try {
            withTimeoutOrNull(deadline + startAllowance) {
                client.preparePost(config.url.trimEnd('/') + "/v1/ops") {
                    bearerAuth(config.token)
                    header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }.execute { response ->
                    val bytes = response.bodyAsChannel().readRemaining(maxResponseBytes.toLong() + HOST_ENVELOPE)
                        .readByteArray()
                    answer(response.status, bytes)
                }
            } ?: HostAnswer.Failed(UpstreamResult.Failure(McpOutcome.TIMEOUT, "The MCP server did not answer in time."))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            HostAnswer.Retryable(failure("The MCP host is unreachable."))
        }
    }

    override suspend fun warm(target: UpstreamTarget) {
        val spec = target.server.stdio ?: return
        val body = buildJsonObject {
            put("key", processKey(target.processKey))
            put("spec", spec(target, spec))
        }
        val (status, _) = control("/v1/warm", body)
        check(status.isSuccess()) { "The MCP host refused the warm-up." }
    }

    override suspend fun retain(keys: Collection<String>): Int {
        val body = buildJsonObject { putJsonArray("keys") { keys.forEach { add(JsonPrimitive(processKey(it))) } } }
        val (status, answer) = control("/v1/retain", body)
        val stopped = (answer?.get("stopped") as? JsonPrimitive)?.intOrNull
        refusedUnless(status)
        check(status == HttpStatusCode.OK && stopped != null) { "The MCP host refused the retain sweep." }
        return stopped
    }

    override suspend fun status(): HostStatus {
        val (status, answer) = control(STATUS, null, STATUS_ENVELOPE)
        val version = (answer?.get("version") as? JsonPrimitive)?.contentOrNull
        val processes = answer?.get("processes") as? JsonArray
        refusedUnless(status)
        check(status == HttpStatusCode.OK && version != null && processes != null) {
            "The MCP host refused the status."
        }
        val byKey = processes.mapNotNull { it as? JsonObject }.mapNotNull { process ->
            process.text("key")?.let { key -> key to process.toStatus() }
        }.toMap()
        return HostStatus(version) { byKey[processKey(it)] }
    }

    override suspend fun stop(key: String): Boolean {
        val (status, answer) = control("/v1/stop", buildJsonObject { put("key", processKey(key)) })
        val stopped = (answer?.get("stopped") as? JsonPrimitive)?.booleanOrNull
        check(status == HttpStatusCode.OK && stopped != null) { "The MCP host refused the stop." }
        return stopped
    }

    private suspend fun control(
        path: String,
        body: JsonObject?,
        envelope: Long = HOST_ENVELOPE,
    ): Pair<HttpStatusCode, JsonObject?> =
        withTimeout(CONTROL_TIMEOUT) {
            client.prepareRequest(config.url.trimEnd('/') + path) {
                method = if (body == null) HttpMethod.Get else HttpMethod.Post
                bearerAuth(config.token)
                header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }.execute { response ->
                val bytes = response.bodyAsChannel().readRemaining(envelope).readByteArray()
                val json = try {
                    Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                } catch (_: SerializationException) {
                    null
                }
                response.status to json
            }
        }

    private fun answer(status: HttpStatusCode, bytes: ByteArray): HostAnswer {
        val json = try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val error = json?.get("error") as? JsonObject
        val result = json?.get("result") as? JsonObject
        return when {
            error != null -> hostError(
                (error["code"] as? JsonPrimitive)?.content,
                (error["message"] as? JsonPrimitive)?.content,
            )
            status != HttpStatusCode.OK -> HostAnswer.Retryable(
                failure("The MCP host is unreachable (HTTP ${status.value})."),
            )
            result != null -> HostAnswer.Result(result, bytes.size.toLong())
            else -> HostAnswer.Failed(failure("The MCP host sent an invalid answer."))
        }
    }

    private fun hostError(code: String?, message: String?): HostAnswer {
        val text = if (message == null) "unknown error" else message.take(MAX_ERROR)
        return when (code) {
            "timeout" -> HostAnswer.Failed(UpstreamResult.Failure(McpOutcome.TIMEOUT, text))
            "too_large" -> HostAnswer.Failed(
                UpstreamResult.Failure(McpOutcome.TOO_LARGE, "The MCP server's response exceeds the relay limit."),
            )
            "tool_error" -> HostAnswer.Failed(
                UpstreamResult.Failure(McpOutcome.TOOL_ERROR, "The MCP server returned an error: $text"),
            )
            "crashed" -> HostAnswer.Retryable(failure(text))
            "shutting_down" -> HostAnswer.Retryable(failure("The MCP host is restarting. Retry shortly."))
            "unauthorized", "unsupported_protocol" -> HostAnswer.Failed(
                failure(
                    "The MCP host refused the relay: deploy the server and its MCP host with the same token and " +
                        "protocol.",
                ),
            )
            else -> HostAnswer.Failed(failure(text))
        }
    }


    /** The protocol between the relay and its MCP host. */
    companion object {
        /** The protocol header, TeamCode's name kept for compatibility with its host image. */
        const val PROTOCOL_HEADER: String = "TeamCode-Host-Protocol"

        /** The protocol version. */
        const val PROTOCOL_VERSION: String = "1"

        private const val HOST_ENVELOPE = 64 * 1024L

        // Up to 16 processes with 64 KiB of stderr each, escaped up to six times (\u00XX): past it, no parse.
        private const val STATUS_ENVELOPE = 8 * 1024 * 1024L
        private const val STATUS = "/v1/status"
        private val CONTROL_TIMEOUT = 30.seconds
        private const val MAX_ERROR = 1000

        /**
         * The host's process key: SHA-256 (64 hex characters) of the relay's key (`StdioProcessKey`), as TeamCode's.
         * No secret is part of it: the host restarts a process whose definition,
         * secret values included, changed under the same key, so the retain sweep decrypts nothing.
         *
         * @param key `org:server:version`, plus `:member:assignedAt` for a per-member server.
         */
        fun processKey(key: String): String =
            CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(key.encodeToByteArray()).toHexString()
    }
}

private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(name: String) = (get(name) as? JsonPrimitive)?.longOrNull

// One process of `GET /v1/status`; a field the host leaves out reads as nothing known.
private fun JsonObject.toStatus() = HostedProcessStatus(
    state = text("state") ?: HostedProcessStates.UNKNOWN,
    launcher = text("launcher").orEmpty(),
    packageName = text("package").orEmpty(),
    starts = number("starts")?.toInt() ?: 0,
    failures = number("failures")?.toInt() ?: 0,
    lastExitCode = number("lastExitCode")?.toInt(),
    lastFailure = text("lastFailure"),
    coldStartMillis = number("coldStartMillis"),
    rssBytes = number("rssBytes"),
    stderr = text("stderr").orEmpty(),
)

private fun failure(message: String) = UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, message)

// The definition the host launches, its secret values included.
private fun spec(target: UpstreamTarget, spec: StdioSpec) = buildJsonObject {
    put("launcher", spec.launcher.wire)
    put("package", spec.packageName)
    putJsonArray("args") { spec.args.forEach { add(JsonPrimitive(it)) } }
    putJsonObject("env") { spec.env.forEach { (name, value) -> put(name, value) } }
    putJsonObject("secrets") { target.secretEnv.forEach { (name, value) -> put(name, value.reveal()) } }
    put("declaredAt", target.server.createdAt.toEpochMilliseconds())
}

// Another token (401) or protocol version (400): the host answers, but refuses this relay.
private fun refusedUnless(status: HttpStatusCode) {
    if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.BadRequest) {
        throw McpHostRefusedException("The MCP host refused the relay: another token or protocol version.")
    }
}
