package dev.ostrom.server.mcprelay.presentation

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ArgumentPolicyPayload
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.InvalidMcpServerException
import dev.ostrom.server.mcprelay.domain.McpCall
import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpUsage
import dev.ostrom.server.mcprelay.domain.PolicyEffect

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
    byTool = filter { it.tool != null }
        .groupBy { "${it.serverName}/${it.tool}" }
        .mapValues { (_, rows) -> rows.sumOf { it.calls } },
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
