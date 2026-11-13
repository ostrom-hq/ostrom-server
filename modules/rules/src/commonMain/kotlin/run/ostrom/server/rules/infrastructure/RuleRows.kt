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
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The rows of the rules tables, read back into the domain: lists are the JSON the contract writes.

/**
 * A rule row.
 *
 * @return The rule.
 */
internal fun ResultSet.Row.toRule(): Rule = Rule(
    orgId = get("org_id").asUuid(),
    id = get("id").asString(),
    statement = get("statement").asString(),
    output = get("output").asString(),
    group = get("rule_group").asString(),
    scope = strings(Json.parseToJsonElement(get("scope").asString())),
    sources = Json.parseToJsonElement(get("sources").asString()).jsonArray.map { source ->
        val fields = source.jsonObject
        RuleSource(text(fields, "kind"), text(fields, "ref"), optional(fields, "url"))
    },
    why = get("why").asStringOrNull(),
    strength = RuleStrength.valueOf(enumName(get("strength").asString())),
    confidence = get("confidence").asDouble(),
    status = RuleStatus.valueOf(enumName(get("status").asString())),
    foundBy = get("found_by").asString(),
    foundAt = get("found_at").asInstant(),
    reviewBy = get("review_by").asStringOrNull(),
    decidedBy = get("decided_by").asStringOrNull(),
    decidedAt = get("decided_at").asInstantOrNull(),
    origin = get("origin").asStringOrNull(),
    gate = get("gate").asStringOrNull()?.let { gateOf(Json.parseToJsonElement(it).jsonObject) },
    updatedAt = get("updated_at").asInstant(),
)

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
internal fun ResultSet.Row.toDecision(): Decision = Decision(
    orgId = get("org_id").asUuid(),
    id = get("id").asString(),
    at = get("decided_at").asInstant(),
    by = get("decided_by").asString(),
    subject = DecisionSubject(
        get("subject_kind").asString(),
        get("subject_id").asString(),
        get("subject_label").asStringOrNull(),
    ),
    action = get("action").asString(),
    before = get("before_doc").asStringOrNull()?.let { Json.parseToJsonElement(it).jsonObject },
    after = get("after_doc").asStringOrNull()?.let { Json.parseToJsonElement(it).jsonObject },
    ruleDigest = get("rule_digest").asStringOrNull(),
    signed = get("signed").asBoolean(),
)

// The stored values are the contract's (`sole-approver`, `required`): the enum names are their upper case.
private fun enumName(value: String): String = value.uppercase().replace('-', '_')

private fun text(json: JsonObject, name: String): String = json.getValue(name).jsonPrimitive.content

private fun optional(json: JsonObject, name: String): String? = json[name]?.let { it.jsonPrimitive.content }

private fun strings(json: JsonElement): List<String> = json.jsonArray.map { it.jsonPrimitive.content }
