// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.fail

/** How an answer differs from the contract. */
enum class DriftKind {
    /** A property the contract requires is absent. */
    MISSING,

    /** A property the contract does not declare is present. */
    UNDECLARED,

    /** A value is not of its type, outside its enum, or in no branch of its `oneOf`. */
    TYPE,

    /** The status is not one the operation declares. */
    STATUS,

    /** The body is absent, not JSON, or JSON where the contract declares none. */
    BODY,
}

/**
 * One difference between an answer and the contract.
 *
 * @property path Where, `$` being the body (`$.actions[0].repos`).
 * @property kind What kind.
 * @property detail What, in words.
 */
data class Violation(val path: String, val kind: DriftKind, val detail: String) {
    /** The same difference whatever the item of a list: `$.actions[*].repos MISSING`. */
    val key: String get() = "${path.replace(INDEX, "[*]")} $kind"

    override fun toString(): String = "$path: $detail"
}

private val INDEX = Regex("""\[\d+]""")

/**
 * Checks JSON against the schemas of the ostrom-core contract (`api/openapi.yaml`, as JSON), strictly: every required
 * property present, every property declared, every value of its type, through `$ref` (any local pointer), `oneOf` (a
 * `null` branch included), `anyOf`, `allOf`, `enum`, arrays and maps.
 *
 * @param document The whole contract, for its references.
 */
class ContractSchema(private val document: JsonObject) {
    /**
     * What in [value] the schema [name] of `components.schemas` does not allow.
     *
     * @param name The schema.
     * @param value The JSON.
     * @return One violation per difference; empty when it matches.
     */
    fun violations(name: String, value: JsonElement): List<Violation> = violations(reference("$SCHEMAS$name"), value)

    /**
     * What in [value] [schema] does not allow.
     *
     * @param schema A schema of the contract (a response's, say).
     * @param value The JSON.
     * @return One violation per difference; empty when it matches.
     */
    fun violations(schema: JsonObject, value: JsonElement): List<Violation> = against(schema, value, "$")

    /**
     * Fails with every difference when [value] does not match the schema [name] of `components.schemas`.
     *
     * @param name The schema.
     * @param value The JSON.
     */
    fun assertMatches(name: String, value: JsonElement) {
        val found = violations(name, value)
        if (found.isNotEmpty()) fail("The answer does not match the contract's $name:\n${found.joinToString("\n")}")
    }

    /**
     * The object a local reference points to.
     *
     * @param ref `#/components/schemas/GateRef`, `#/components/responses/Error`…
     * @return The object.
     */
    fun resolve(ref: String): JsonObject {
        val target = ref.removePrefix("#/").split("/").fold<String, JsonElement?>(document) { node, part ->
            (node as? JsonObject)?.get(part)
        }
        return target as? JsonObject ?: error("The contract has nothing at $ref")
    }

    private fun against(schema: JsonObject, value: JsonElement, at: String): List<Violation> {
        val ref = schema[REF]?.let { it.jsonPrimitive.content }
        val all = schema["allOf"]?.jsonArray
        val one = schema["oneOf"]?.jsonArray
        val any = schema["anyOf"]?.jsonArray
        return when {
            ref != null -> against(resolve(ref), value, at)
            all != null -> against(merged(all), value, at)
            one != null -> branches(one, value, at, exclusive = true)
            any != null -> branches(any, value, at, exclusive = false)
            else -> enumViolations(schema, value, at) + typed(schema, value, at)
        }
    }

    // The parts of an `allOf` as one schema: a property any part declares is declared, a property any requires is
    // required; otherwise the first part that says it wins (a closed part must not refuse its sibling's properties).
    private fun merged(parts: JsonArray): JsonObject {
        val schemas = parts.map { part ->
            part.jsonObject[REF].let { if (it == null) part.jsonObject else resolve(it.jsonPrimitive.content) }
        }
        val properties = schemas.flatMap { it["properties"]?.jsonObject.orEmpty().entries }.associate { it.toPair() }
        val required = schemas.flatMap { it["required"]?.jsonArray.orEmpty() }.distinct()
        val rest = schemas.reversed().fold(emptyMap<String, JsonElement>()) { all, schema -> all + schema }
        return JsonObject(rest + mapOf("properties" to JsonObject(properties), "required" to JsonArray(required)))
    }

    // `oneOf` (exclusive) takes exactly one branch, `anyOf` at least one.
    private fun branches(options: JsonArray, value: JsonElement, at: String, exclusive: Boolean): List<Violation> {
        val results = options.map { against(it.jsonObject, value, at) }
        val keyword = if (exclusive) "oneOf" else "anyOf"
        val matched = results.count { it.isEmpty() }
        return when {
            matched == 0 -> listOf(Violation(at, DriftKind.TYPE, "matches no branch of $keyword: ${results.flatten()}"))
            matched > 1 && exclusive -> listOf(Violation(at, DriftKind.TYPE, "matches more than one branch of oneOf"))
            else -> emptyList()
        }
    }

    private fun typed(schema: JsonObject, value: JsonElement, at: String): List<Violation> {
        val types = when (val type = schema["type"]) {
            null -> emptyList()
            is JsonArray -> type.map { it.jsonPrimitive.content }
            else -> listOf(type.jsonPrimitive.content)
        }
        val type = types.firstOrNull { matches(it, value) }
        return when {
            types.isEmpty() -> emptyList()
            type == null -> listOf(Violation(at, DriftKind.TYPE, "$value is not ${types.joinToString(" or ")}"))
            type == "array" -> value.jsonArray.withIndex().flatMap { (index, item) ->
                against(schema.getValue("items").jsonObject, item, "$at[$index]")
            }
            type == "object" -> objectViolations(schema, value.jsonObject, at)
            else -> emptyList()
        }
    }

    private fun objectViolations(schema: JsonObject, value: JsonObject, at: String): List<Violation> {
        val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val missing = required.filter { it !in value }
            .map { Violation("$at.$it", DriftKind.MISSING, "required by the contract, absent from the answer") }
        return missing + value.flatMap { (key, item) -> property(schema, key, item, "$at.$key") }
    }

    private fun property(schema: JsonObject, key: String, item: JsonElement, at: String): List<Violation> {
        val declared = schema["properties"]?.let { it.jsonObject[key] }
        val additional = schema["additionalProperties"]
        return when {
            declared != null -> against(declared.jsonObject, item, at)
            additional is JsonObject -> against(additional, item, at)
            additional is JsonPrimitive && additional.booleanOrNull == true -> emptyList()
            else -> listOf(Violation(at, DriftKind.UNDECLARED, "sent by the server, not declared by the contract"))
        }
    }
}

private const val REF = "\$ref"
private const val SCHEMAS = "#/components/schemas/"

private fun reference(ref: String) = JsonObject(mapOf(REF to JsonPrimitive(ref)))

private fun enumViolations(schema: JsonObject, value: JsonElement, at: String): List<Violation> {
    val enum = schema["enum"]?.jsonArray
    return if (enum == null || value in enum) {
        emptyList()
    } else {
        listOf(Violation(at, DriftKind.TYPE, "$value is not one of $enum"))
    }
}

private fun matches(type: String, value: JsonElement): Boolean {
    // JsonNull is a primitive that is not a string, and none of these reads `null`.
    val scalar = (value as? JsonPrimitive)?.takeIf { !it.isString }
    return when (type) {
        "null" -> value is JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> scalar?.booleanOrNull != null
        "integer" -> scalar?.longOrNull != null
        "number" -> scalar?.doubleOrNull != null
        else -> false
    }
}
