// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.kernel.platform.getEnv
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
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

/**
 * A real answer of the server checked against a schema of the ostrom-core contract (`api/openapi.yaml`, as JSON), so
 * that the two cannot drift: every required property present, every property declared, every value of its type,
 * through `$ref`, `oneOf` (a `null` branch included), `allOf`, `enum`, arrays and maps.
 *
 * CI writes the contract of ostrom-core main as JSON and names it in `OSTROM_CONTRACT_OPENAPI` (`ci.yml`, job
 * `ci-jvm`), with `OSTROM_CONTRACT_REQUIRED=true`, so that a missing contract fails instead of skipping.
 *
 * @param schemas `components.schemas` of the contract.
 */
class ContractSchema(private val schemas: JsonObject) {
    /**
     * What in [value] the schema [name] does not allow.
     *
     * @param name A schema of `components.schemas`.
     * @param value The JSON to check.
     * @return One line per difference, with its path; empty when it matches.
     */
    fun violations(name: String, value: JsonElement): List<String> = against(named(name), value, "$")

    /**
     * Fails with every difference when [value] does not match the schema [name].
     *
     * @param name A schema of `components.schemas`.
     * @param value The JSON to check.
     */
    fun assertMatches(name: String, value: JsonElement) {
        val found = violations(name, value)
        if (found.isNotEmpty()) fail("The answer does not match the contract's $name:\n${found.joinToString("\n")}")
    }

    private fun against(schema: JsonObject, value: JsonElement, at: String): List<String> {
        val ref = schema["\$ref"]?.jsonPrimitive?.content
        val all = schema["allOf"]?.jsonArray
        val one = schema["oneOf"]?.jsonArray
        return when {
            ref != null -> against(named(ref.removePrefix(PREFIX)), value, at)
            all != null -> against(merged(all), value, at)
            one != null -> oneOf(one, value, at)
            else -> enumViolations(schema, value, at) + typed(schema, value, at)
        }
    }

    // The parts of an `allOf` as one schema: a property any part declares is declared, a property any requires is
    // required; otherwise the first part that says it wins (a closed part must not refuse its sibling's properties).
    private fun merged(parts: JsonArray): JsonObject {
        val schemas = parts.map { part ->
            val ref = part.jsonObject["\$ref"]?.jsonPrimitive?.content
            if (ref != null) named(ref.removePrefix(PREFIX)) else part.jsonObject
        }
        val properties = schemas.flatMap { it["properties"]?.jsonObject.orEmpty().entries }.associate { it.toPair() }
        val required = schemas.flatMap { it["required"]?.jsonArray.orEmpty() }.distinct()
        val rest = schemas.reversed().fold(emptyMap<String, JsonElement>()) { all, schema -> all + schema }
        return JsonObject(rest + mapOf("properties" to JsonObject(properties), "required" to JsonArray(required)))
    }

    private fun oneOf(branches: JsonArray, value: JsonElement, at: String): List<String> {
        val results = branches.map { against(it.jsonObject, value, at) }
        return when (results.count { it.isEmpty() }) {
            1 -> emptyList()
            0 -> listOf("$at: matches no branch of oneOf: ${results.flatten()}")
            else -> listOf("$at: matches more than one branch of oneOf")
        }
    }

    private fun enumViolations(schema: JsonObject, value: JsonElement, at: String): List<String> {
        val enum = schema["enum"]?.jsonArray
        return if (enum == null || value in enum) emptyList() else listOf("$at: $value is not one of $enum")
    }

    private fun typed(schema: JsonObject, value: JsonElement, at: String): List<String> {
        val types = when (val type = schema["type"]) {
            null -> emptyList()
            is JsonArray -> type.map { it.jsonPrimitive.content }
            else -> listOf(type.jsonPrimitive.content)
        }
        val type = types.firstOrNull { matches(it, value) }
        return when {
            types.isEmpty() -> emptyList()
            type == null -> listOf("$at: $value is not ${types.joinToString(" or ")}")
            type == "array" -> value.jsonArray.flatMapIndexed { index, item ->
                against(schema.getValue("items").jsonObject, item, "$at[$index]")
            }
            type == "object" -> objectViolations(schema, value.jsonObject, at)
            else -> emptyList()
        }
    }

    private fun objectViolations(schema: JsonObject, value: JsonObject, at: String): List<String> {
        val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val missing = required.filter { it !in value }
            .map { "$at.$it: required by the contract, absent from the answer" }
        return missing + value.flatMap { (key, item) -> property(schema, key, item, "$at.$key") }
    }

    private fun property(schema: JsonObject, key: String, item: JsonElement, at: String): List<String> {
        val declared = schema["properties"]?.jsonObject?.get(key)
        val additional = schema["additionalProperties"]
        return when {
            declared != null -> against(declared.jsonObject, item, at)
            additional is JsonObject -> against(additional, item, at)
            additional is JsonPrimitive && additional.booleanOrNull == true -> emptyList()
            else -> listOf("$at: sent by the server, not declared by the contract")
        }
    }

    private fun matches(type: String, value: JsonElement): Boolean {
        val scalar = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }
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

    private fun named(name: String): JsonObject =
        schemas[name]?.jsonObject ?: error("The contract has no schema $name")

    /** Where the contract is read from. */
    companion object {
        private const val PREFIX = "#/components/schemas/"

        /**
         * The contract CI wrote, or null where none is given (a local run, the native job): the check is then
         * skipped, unless `OSTROM_CONTRACT_REQUIRED` is `true`, where a missing contract fails.
         *
         * @return The contract's schemas, or null.
         */
        fun fromEnvironment(): ContractSchema? {
            val path = getEnv("OSTROM_CONTRACT_OPENAPI")?.takeIf { it.isNotBlank() }
            if (path == null && getEnv("OSTROM_CONTRACT_REQUIRED") == "true") {
                fail("OSTROM_CONTRACT_OPENAPI names no contract, and OSTROM_CONTRACT_REQUIRED is true")
            }
            return path?.let { file ->
                val text = SystemFileSystem.source(Path(file)).buffered().use { it.readString() }
                val components = Json.parseToJsonElement(text).jsonObject.getValue("components").jsonObject
                ContractSchema(components.getValue("schemas").jsonObject)
            }
        }
    }
}
