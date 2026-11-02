// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleGate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A rule as JSON, in the contract's field names: whole, for its digest in a receipt, and reduced to what a decision
 * changes, for the decision's `before` and `after` (the prototype's `decisions.ts` `pick`, with the gate).
 */
object RuleDocuments {
    /**
     * The whole rule, as the API serves it.
     *
     * @param rule The rule.
     * @return Its JSON; absent fields are left out.
     */
    fun full(rule: Rule): JsonObject = buildJsonObject {
        put("id", rule.id)
        put("statement", rule.statement)
        put("output", rule.output)
        put("group", rule.group)
        put("scope", texts(rule.scope))
        put(
            "sources",
            JsonArray(
                rule.sources.map { source ->
                    buildJsonObject {
                        put("kind", source.kind)
                        put("ref", source.ref)
                        source.url?.let { put("url", it) }
                    }
                },
            ),
        )
        rule.why?.let { put("why", it) }
        put("strength", rule.strength.value)
        put("confidence", rule.confidence)
        put("status", rule.status.value)
        put("foundBy", rule.foundBy)
        put("foundAt", rule.foundAt.isoMillis())
        rule.reviewBy?.let { put("reviewBy", it) }
        rule.decidedBy?.let { put("decidedBy", it) }
        rule.decidedAt?.let { put("decidedAt", it.isoMillis()) }
        rule.origin?.let { put("origin", it) }
        rule.gate?.let { put("gate", gate(it)) }
    }

    /**
     * What a decision on the rule changes: status, statement, reason, strength, scope, group, output and gate.
     *
     * @param rule The rule.
     * @return Those fields.
     */
    fun picked(rule: Rule): JsonObject = buildJsonObject {
        put("status", rule.status.value)
        put("statement", rule.statement)
        rule.why?.let { put("why", it) }
        put("strength", rule.strength.value)
        put("scope", texts(rule.scope))
        put("group", rule.group)
        put("output", rule.output)
        rule.gate?.let { put("gate", gate(it)) }
    }

    /**
     * A gate as JSON (contract `RuleGate`).
     *
     * @param gate The gate.
     * @return Its JSON.
     */
    fun gate(gate: RuleGate): JsonObject = buildJsonObject {
        put(
            "actions",
            JsonArray(
                gate.actions.map { action ->
                    buildJsonObject {
                        put("class", action.cls)
                        put("label", action.label)
                        put("patterns", texts(action.patterns))
                        put("repos", texts(action.repos))
                        action.tools?.let { put("tools", texts(it)) }
                    }
                },
            ),
        )
        put("approvers", texts(gate.approvers))
        gate.reason?.let { put("reason", it) }
    }

    /**
     * The self-approval policy as a decision shows it.
     *
     * @param policy The setting.
     * @return `{selfApproval}`.
     */
    fun policy(policy: SelfApprovalPolicy): JsonObject = buildJsonObject {
        put("selfApproval", SelfApprovalValues.valueOf(policy))
    }

    private fun texts(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))
}

/** The contract's values of the self-approval policy (`GET` and `PUT /api/v1/gates/policy`). */
object SelfApprovalValues {
    /** Self-approval only for the gate's sole approver, marked in the receipt. */
    const val SOLE_APPROVER: String = "sole-approver"

    /** Never: someone else must say yes. */
    const val NEVER: String = "never"

    /**
     * The value of a setting.
     *
     * @param policy The setting.
     * @return Its contract value.
     */
    fun valueOf(policy: SelfApprovalPolicy): String = when (policy) {
        SelfApprovalPolicy.SOLE_APPROVER -> SOLE_APPROVER
        SelfApprovalPolicy.NEVER -> NEVER
    }

    /**
     * Reads a value.
     *
     * @param value The contract value.
     * @return The setting, or null when unknown.
     */
    fun of(value: String): SelfApprovalPolicy? = when (value) {
        SOLE_APPROVER -> SelfApprovalPolicy.SOLE_APPROVER
        NEVER -> SelfApprovalPolicy.NEVER
        else -> null
    }
}
