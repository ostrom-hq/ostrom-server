package dev.ostrom.server.mcprelay.presentation

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ArgumentPolicyPayload
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.InvalidMcpServerException
import dev.ostrom.server.mcprelay.domain.McpAssignment
import dev.ostrom.server.mcprelay.domain.McpCall
import dev.ostrom.server.mcprelay.domain.McpServer
import dev.ostrom.server.mcprelay.domain.McpServerPayload
import dev.ostrom.server.mcprelay.domain.McpSharing
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.McpUsage
import dev.ostrom.server.mcprelay.domain.MemberServer
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.mcprelay.domain.StdioLauncher
import dev.ostrom.server.mcprelay.domain.StdioSpec
import kotlin.uuid.Uuid

/** Maps a request to the domain, refusing unknown enum values and malformed ids as a 400. */
fun McpServerRequest.toPayload() = McpServerPayload(
    name = name,
    transport = enumOf<McpTransport>(transport, "transport"),
    url = url,
    credentialSecretId = credentialSecretId?.let { uuidOf(it, "credentialSecretId") },
    credentialHeader = credentialHeader,
    stdio = stdio?.let {
        StdioSpec(
            launcher = enumOf<StdioLauncher>(it.launcher, "launcher"),
            packageName = it.packageName,
            args = it.args,
            env = it.env,
            secretEnv = it.secretEnv.mapValues { (_, id) -> uuidOf(id, "secretEnv") },
        )
    },
    sharing = enumOf<McpSharing>(sharing, "sharing"),
)

/** Maps a server to the API. */
fun McpServer.toResponse() = McpServerResponse(
    id = id.toString(),
    name = name,
    transport = transport.name,
    url = url,
    credentialSecretId = credentialSecretId?.toString(),
    credentialHeader = credentialHeader,
    stdio = stdio?.let { StdioSpecDto(it.launcher.name, it.packageName, it.args, it.env, it.secretEnv.mapValues { (_, id) -> id.toString() }) },
    sharing = sharing.name,
    version = version,
    createdAt = createdAt.toString(),
)

/** Maps an assignment to the API. */
fun McpAssignment.toResponse() = McpAssignmentResponse(
    serverId = serverId.toString(),
    memberId = memberId?.toString(),
    tools = policy.tools?.sorted(),
    credentialSecretId = credentialSecretId?.toString(),
    assignedAt = assignedAt.toString(),
)

/** Maps a policy request to the domain. */
fun ArgumentPolicyRequest.toPayload() = ArgumentPolicyPayload(
    tool = tool,
    conditions = conditions.map {
        val operator = enumOf<ConditionOperator>(it.operator, "operator")
        if (operator == ConditionOperator.MATCHES) {
            try {
                Regex(it.value.orEmpty())
            } catch (_: IllegalArgumentException) {
                throw InvalidMcpServerException("The regular expression of ${it.path} does not compile.")
            }
        }
        ArgumentCondition(it.path, operator, it.value)
    },
    effect = enumOf<PolicyEffect>(effect, "effect"),
    message = message,
)

/** Maps a policy to the API. */
fun ArgumentPolicy.toResponse() = ArgumentPolicyResponse(
    id = id.toString(),
    tool = tool,
    conditions = conditions.map { ArgumentConditionDto(it.path, it.operator.name, it.value) },
    effect = effect.name,
    message = message,
    createdAt = createdAt.toString(),
)

/** Maps a journal row to the API. */
fun McpCall.toResponse() = McpCallResponse(
    id = id.toString(),
    memberId = memberId.toString(),
    server = serverName,
    operation = operation.name,
    tool = tool,
    outcome = outcome.name,
    startedAt = startedAt.toString(),
    durationMs = durationMillis,
    responseBytes = responseBytes,
    policyId = policyId?.toString(),
)

/** Maps usage to the API. */
fun McpUsage.toResponse() = McpUsageResponse(memberId.toString(), serverName, calls, lists, errors, denied, bytes, durationMillis)

/** Maps a member's server to the API. */
fun MemberServer.toResponse() = MemberMcpServerResponse(name, agentName, "/v1/mcp/$name", transport.name.lowercase(), tools?.sorted())

private inline fun <reified T : Enum<T>> enumOf(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw InvalidMcpServerException("$field must be one of ${enumValues<T>().joinToString { it.name }}.")

private fun uuidOf(value: String, field: String): Uuid = Uuid.parseOrNull(value) ?: throw InvalidMcpServerException("$field is not an id.")
