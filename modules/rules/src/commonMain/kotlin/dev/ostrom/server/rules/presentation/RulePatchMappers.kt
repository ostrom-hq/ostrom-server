// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.presentation

import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.rules.domain.models.GateChange
import dev.ostrom.server.rules.domain.models.InvalidRuleException
import dev.ostrom.server.rules.domain.models.RulePatch
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.models.RuleStrength
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The change a `PATCH` sends: each field present is changed, and `"gate": null` removes the gate (the JSON settings
 * read an absent field and a null one alike, so the body is read as an object).
 *
 * @return The patch, not checked yet.
 * @throws InvalidRuleException When a field has the wrong type or an unknown value.
 */
fun JsonObject.toRulePatch(): RulePatch = RulePatch(
    statement = field("statement", String.serializer()),
    status = field("status", String.serializer())?.let(::ruleStatusOf),
    strength = field("strength", String.serializer())?.let(::ruleStrengthOf),
    reviewBy = field("reviewBy", String.serializer()),
    scope = field("scope", ListSerializer(String.serializer())),
    group = field("group", String.serializer()),
    why = field("why", String.serializer()),
    output = field("output", String.serializer()),
    gate = when {
        "gate" !in this -> GateChange.Keep
        get("gate") is JsonNull -> GateChange.Remove
        else -> GateChange.Replace(gate(getValue("gate")).toGate())
    },
)

private fun gate(element: JsonElement): RuleGateDto = decode("gate", RuleGateDto.serializer(), element)

private fun <T> JsonObject.field(name: String, serializer: KSerializer<T>): T? {
    val element = get(name)
    return if (element == null || element is JsonNull) null else decode(name, serializer, element)
}

private fun <T> decode(name: String, serializer: KSerializer<T>, element: JsonElement): T = try {
    OstromJson.decodeFromJsonElement(serializer, element)
} catch (_: IllegalArgumentException) {
    // kotlinx.serialization's SerializationException is an IllegalArgumentException.
    throw InvalidRuleException("$name has the wrong type.")
}

/**
 * A status the console sends.
 *
 * @param value The contract value.
 * @return The status.
 * @throws InvalidRuleException When unknown.
 */
internal fun ruleStatusOf(value: String): RuleStatus =
    RuleStatus.of(value) ?: throw InvalidRuleException("status is proposed, accepted, edited or rejected.")

/**
 * A strength the console sends.
 *
 * @param value The contract value.
 * @return The strength.
 * @throws InvalidRuleException When unknown.
 */
internal fun ruleStrengthOf(value: String): RuleStrength =
    RuleStrength.of(value) ?: throw InvalidRuleException("strength is required or recommended.")
