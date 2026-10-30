// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.infrastructure

import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpUpstream
import dev.ostrom.server.mcprelay.domain.UpstreamResult
import dev.ostrom.server.mcprelay.domain.UpstreamTarget
import dev.ostrom.server.secrets.domain.SecretValue
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
) : McpUpstream {
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
            put("key", processKey(target.processKey, target.secretEnv))
            putJsonObject("spec") {
                put("launcher", spec.launcher.wire)
                put("package", spec.packageName)
                putJsonArray("args") { spec.args.forEach { add(JsonPrimitive(it)) } }
                putJsonObject("env") { spec.env.forEach { (name, value) -> put(name, value) } }
                putJsonObject("secrets") { target.secretEnv.forEach { (name, value) -> put(name, value.reveal()) } }
                put("declaredAt", target.server.createdAt.toEpochMilliseconds())
            }
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

    private fun failure(message: String) = UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, message)

    /** The protocol between the relay and its MCP host. */
    companion object {
        /** The protocol header, TeamCode's name kept for compatibility with its host image. */
        const val PROTOCOL_HEADER: String = "TeamCode-Host-Protocol"

        /** The protocol version. */
        const val PROTOCOL_VERSION: String = "1"

        private const val HOST_ENVELOPE = 64 * 1024L
        private const val MAX_ERROR = 1000

        /**
         * The host's process key: SHA-256 (64 hex characters) of the relay's key and the secret variables, so a
         * rotated secret starts a new process instead of reusing one that holds the old value.
         *
         * @param key `org:server:version`, plus `:member:assignedAt` for a per-member server.
         * @param secretEnv The secret variables the process starts with.
         */
        fun processKey(key: String, secretEnv: Map<String, SecretValue> = emptyMap()): String {
            val material = secretEnv.entries.sortedBy { it.key }
                .joinToString("") { (name, value) -> "\u0000$name=${value.reveal()}" }
            return CryptographyProvider.Default.get(SHA256).hasher()
                .hashBlocking((key + material).encodeToByteArray())
                .toHexString()
        }
    }
}
