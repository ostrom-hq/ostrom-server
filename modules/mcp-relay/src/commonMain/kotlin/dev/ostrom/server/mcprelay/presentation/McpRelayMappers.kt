package dev.ostrom.server.mcprelay.presentation

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ArgumentPolicyPayload
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.InvalidMcpServerException
import dev.ostrom.server.mcprelay.domain.McpAssignment
import dev.ostrom.server.mcprelay.domain.McpCall
import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpSecretNotFoundException
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

/**
 * Maps a server to the domain.
 *
 * @param secretIds The ids of the org secrets it names, by name.
 */
fun McpServerDto.toPayload(secretIds: Map<String, Uuid>) = McpServerPayload(
    name = name,
    transport = enumOf<McpTransport>(transport, "transport"),
    url = url.takeIf { enumOf<McpTransport>(transport, "transport") == McpTransport.HTTP && it.isNotEmpty() },
    credentialSecretId = credentialName()?.let { secretIds[it] ?: throw McpSecretNotFoundException() },
    credentialHeader = credentialHeader,
    stdio = stdio?.let {
        StdioSpec(
            launcher = enumOf<StdioLauncher>(it.launcher, "launcher"),
            packageName = it.packageName,
            args = it.args,
            env = it.env,
            secretEnv = it.secretEnv.mapValues { (_, name) -> secretIds[name] ?: throw McpSecretNotFoundException() },
        )
    },
    sharing = when (scope) {
        "shared" -> McpSharing.SHARED
        "per-member" -> McpSharing.PER_MEMBER
        else -> throw InvalidMcpServerException("scope must be shared or per-member.")
    },
)

/** The credential of an HTTP server: its first secret name. */
fun McpServerDto.credentialName(): String? = if (transport.equals("http", ignoreCase = true)) secretNames.firstOrNull() else null

/** Every secret name a server uses. */
fun McpServerDto.secretNamesUsed(): List<String> = listOfNotNull(credentialName()) + stdio?.secretEnv?.values.orEmpty()

/** Every secret id a server uses. */
fun McpServer.secretIds(): List<Uuid> = listOfNotNull(credentialSecretId) + stdio?.secretEnv?.values.orEmpty()

/**
 * Maps a server to the API.
 *
 * @param names Secret names by id.
 */
fun McpServer.toDto(names: Map<Uuid, String>) = McpServerDto(
    name = name,
    url = url ?: stdio?.let { "stdio:${it.launcher.wire}:${it.packageName}" }.orEmpty(),
    transport = transport.name.lowercase(),
    secretNames = secretIds().mapNotNull { names[it] }.distinct(),
    scope = if (sharing == McpSharing.SHARED) "shared" else "per-member",
    credentialHeader = credentialHeader,
    stdio = stdio?.let { spec ->
        StdioSpecDto(spec.launcher.name.lowercase(), spec.packageName, spec.args, spec.env, spec.secretEnv.mapNotNull { (key, id) -> names[id]?.let { key to it } }.toMap())
    },
    version = version,
    createdAt = createdAt.toString(),
)

/** Maps an assignment to the API. */
fun McpAssignment.toResponse(server: String, credentialName: String?) = McpAssignmentResponse(
    server = server,
    member = memberId?.toString(),
    tools = policy.tools?.sorted(),
    credentialSecret = credentialName,
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
fun McpCall.toDto() = McpCallDto(
    id = id.toString(),
    at = startedAt.toString(),
    server = serverName,
    tool = tool ?: "tools/list",
    dev = memberId.toString(),
    durationMs = durationMillis,
    ok = outcome == McpOutcome.OK,
    outcome = outcome.name,
    responseBytes = responseBytes,
    policyId = policyId?.toString(),
)

/** Totals usage rows by server and by tool, with details by member and server. */
fun List<McpUsage>.toResponse() = McpUsageResponse(
    byServer = groupBy { it.serverName }.mapValues { (_, rows) -> rows.sumOf { it.calls } },
    byTool = filter { it.tool != null }.groupBy { "${it.serverName}/${it.tool}" }.mapValues { (_, rows) -> rows.sumOf { it.calls } },
    rows = groupBy { it.memberId to it.serverName }.map { (key, rows) ->
        McpUsageRow(
            dev = key.first.toString(),
            server = key.second,
            calls = rows.sumOf { it.calls },
            lists = rows.sumOf { it.lists },
            errors = rows.sumOf { it.errors },
            denied = rows.sumOf { it.denied },
            bytes = rows.sumOf { it.bytes },
            durationMs = rows.sumOf { it.durationMillis },
        )
    },
)

/** Maps a member's server to the API. */
fun MemberServer.toDto() = McpServerDto(
    name = name,
    url = "/api/v1/mcp/$name",
    transport = transport.name.lowercase(),
    tools = tools?.sorted(),
    agentName = agentName,
    path = "/api/v1/mcp/$name",
)

private inline fun <reified T : Enum<T>> enumOf(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw InvalidMcpServerException("$field must be one of ${enumValues<T>().joinToString { it.name.lowercase() }}.")
