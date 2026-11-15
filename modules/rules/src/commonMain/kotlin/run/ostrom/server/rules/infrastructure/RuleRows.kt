// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.infrastructure

import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.DecisionSubject
import run.ostrom.server.rules.domain.models.Rule
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleGateCheck
import run.ostrom.server.rules.domain.models.RuleSource
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.models.RuleStrength
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow

// The rows of the rules tables, read back into the domain: lists are the JSON the contract writes.

/**
 * A rule row.
 *
 * @return The rule.
 */
internal fun ResultRow.toRule(): Rule {
    val r = RulesRules
    return Rule(
        orgId = this[r.orgId],
        id = this[r.id],
        statement = this[r.statement],
        output = this[r.output],
        group = this[r.group],
        scope = strings(Json.parseToJsonElement(this[r.scope])),
        sources = Json.parseToJsonElement(this[r.sources]).jsonArray.map { source ->
            val fields = source.jsonObject
            RuleSource(text(fields, "kind"), text(fields, "ref"), optional(fields, "url"))
        },
        why = this[r.why],
        strength = RuleStrength.valueOf(enumName(this[r.strength])),
        confidence = this[r.confidence],
        status = RuleStatus.valueOf(enumName(this[r.status])),
        foundBy = this[r.foundBy],
        foundAt = this[r.foundAt],
        reviewBy = this[r.reviewBy],
        decidedBy = this[r.decidedBy],
        decidedAt = this[r.decidedAt],
        origin = this[r.origin],
        gate = this[r.gate]?.let { gateOf(Json.parseToJsonElement(it).jsonObject) },
        updatedAt = this[r.updatedAt],
    )
}

private fun gateOf(json: JsonObject) = RuleGate(
    actions = json.getValue("actions").jsonArray.map { element ->
        val action = element.jsonObject
        RuleGateAction(
            cls = text(action, "class"),
            label = text(action, "label"),
            patterns = strings(action.getValue("patterns")),
            repos = strings(action.getValue("repos")),
            tools = action["tools"]?.let(::strings),
        )
    },
    approvers = strings(json.getValue("approvers")),
    reason = optional(json, "reason"),
    checks = json["checks"]?.jsonArray.orEmpty().map { element ->
        val check = element.jsonObject
        RuleGateCheck(text(check, "check"), check.getValue("attested").jsonPrimitive.boolean)
    },
)

/**
 * A decision row.
 *
 * @return The decision.
 */
internal fun ResultRow.toDecision(): Decision {
    val d = RulesDecisions
    return Decision(
        orgId = this[d.orgId],
        id = this[d.id],
        at = this[d.decidedAt],
        by = this[d.decidedBy],
        subject = DecisionSubject(this[d.subjectKind], this[d.subjectId], this[d.subjectLabel]),
        action = this[d.action],
        before = this[d.beforeDoc]?.let { Json.parseToJsonElement(it).jsonObject },
        after = this[d.afterDoc]?.let { Json.parseToJsonElement(it).jsonObject },
        ruleDigest = this[d.ruleDigest],
        signed = this[d.signed],
    )
}

// The stored values are the contract's (`sole-approver`, `required`): the enum names are their upper case.
private fun enumName(value: String): String = value.uppercase().replace('-', '_')

private fun text(json: JsonObject, name: String): String = json.getValue(name).jsonPrimitive.content

private fun optional(json: JsonObject, name: String): String? = json[name]?.let { it.jsonPrimitive.content }

private fun strings(json: JsonElement): List<String> = json.jsonArray.map { it.jsonPrimitive.content }
