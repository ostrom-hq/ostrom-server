// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks the structure of an OpenAPI 3.x document as its consumers read it (hey-api in the console, the site's API
 * reference): what a generator refuses or silently drops. No dependency, as TeamCode's ApiDocsTest checks its own
 * document's references:
 * - the root has `openapi` 3.x, `info.title`, `info.version` and `paths`;
 * - every `$ref` is a local pointer to something the document holds (no dangling reference);
 * - every path starts with `/`; each `{name}` of a path is declared by each of its operations as a required `path`
 *   parameter, and each declared `path` parameter is in the path;
 * - every parameter has a `name`, an `in` among `query`, `header`, `path` and `cookie`, and a `schema`;
 * - every operation has a unique `operationId`, at least one response, each with a `description`, tags the
 *   document declares, and security requirements naming its schemes.
 */
object OpenApiStructure {
    /**
     * What in [document] breaks the structure.
     *
     * @param document The document, as JSON.
     * @return One line per problem; empty when the document is sound.
     */
    fun problems(document: JsonObject): List<String> = buildList {
        val version = document.text("openapi")
        if (version == null || !version.startsWith("3.")) add("openapi: not an OpenAPI 3.x document ($version)")
        val info = document.objectAt("info")
        for (field in listOf("title", "version")) {
            if (info.text(field) == null) add("info.$field: missing")
        }
        addAll(references(document, document, "#"))
        if (document["paths"] !is JsonObject) add("paths: missing")
        val tags = document.list("tags").map { it.jsonObject.text("name") }.toSet()
        val schemes = document.objectAt("components").objectAt("securitySchemes").keys
        val ids = mutableMapOf<String, String>()
        for ((path, item) in document.objectAt("paths")) {
            if (!path.startsWith("/")) add("$path: does not start with /")
            val shared = item.jsonObject.list("parameters")
            for ((method, operation) in item.jsonObject.filterKeys { it in METHODS }) {
                val at = "${method.uppercase()} $path"
                val fields = operation.jsonObject
                val parameters = (shared + fields.list("parameters")).map { resolve(document, it.jsonObject) }
                addAll(parameters(at, path, parameters))
                addAll(operation(at, fields, tags, schemes))
                val id = fields.text("operationId")
                val other = id?.let { ids.put(it, at) }
                if (other != null) add("$at: operationId $id is also $other's")
            }
        }
    }

    // Every `$ref` under [node], resolved against [document].
    private fun references(document: JsonObject, node: JsonElement, at: String): List<String> = when (node) {
        is JsonObject -> node.flatMap { (key, value) ->
            val ref = if (key == REF) node.text(REF) else null
            when {
                ref != null && target(document, ref) == null -> listOf("$at: \$ref $ref resolves to nothing")
                else -> references(document, value, "$at/$key")
            }
        }
        is JsonArray -> node.withIndex().flatMap { (index, value) -> references(document, value, "$at/$index") }
        else -> emptyList()
    }

    // The object a local pointer names, or null (an external or a dangling reference).
    private fun target(document: JsonObject, ref: String): JsonObject? = if (ref.startsWith("#/")) {
        ref.removePrefix("#/").split("/").map { it.replace("~1", "/").replace("~0", "~") }
            .fold<String, JsonElement?>(document) { node, part -> (node as? JsonObject)?.get(part) } as? JsonObject
    } else {
        null
    }

    // A parameter, through its reference (left as is when dangling: [references] reports it).
    private fun resolve(document: JsonObject, parameter: JsonObject): JsonObject =
        parameter.text(REF)?.let { target(document, it) } ?: parameter

    private fun parameters(at: String, path: String, parameters: List<JsonObject>): List<String> = buildList {
        for (parameter in parameters) {
            val name = parameter.text("name")
            val location = parameter.text("in")
            if (name == null) add("$at: a parameter without a name")
            if (location !in LOCATIONS) add("$at: parameter $name is in $location")
            if (parameter["schema"] !is JsonObject) add("$at: parameter $name has no schema")
        }
        val declared = parameters.filter { it.text("in") == "path" }.associateBy { it.text("name") }
        val names = TEMPLATE.findAll(path).map { it.groupValues[1] }.toSet()
        for (name in names) {
            val parameter = declared[name]
            when {
                parameter == null -> add("$at: path parameter $name is not declared")
                parameter.text("required") != "true" -> add("$at: path parameter $name is not required")
            }
        }
        (declared.keys - names).forEach { add("$at: path parameter $it is not in the path") }
    }

    private fun operation(at: String, operation: JsonObject, tags: Set<String?>, schemes: Set<String>) = buildList {
        if (operation.text("operationId") == null) add("$at: no operationId")
        val responses = operation.objectAt("responses")
        if (responses.isEmpty()) add("$at: no response")
        responses.filter { (_, response) -> response.jsonObject.text(REF) == null }
            .filter { (_, response) -> response.jsonObject.text("description") == null }
            .forEach { (status, _) -> add("$at: response $status has no description") }
        operation.list("tags").map { it.jsonPrimitive.content }.filter { it !in tags }
            .forEach { add("$at: tag $it is not declared") }
        operation.list("security").flatMap { it.jsonObject.keys }.filter { it !in schemes }
            .forEach { add("$at: security scheme $it is not declared") }
    }
}

// A field's text, or null when it is absent or not a primitive.
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

// A field's object, empty when it is absent or not an object.
private fun JsonObject.objectAt(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

// A field's array, empty when it is absent.
private fun JsonObject.list(key: String): List<JsonElement> = this[key]?.jsonArray.orEmpty()

private const val REF = "\$ref"
private val METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options", "trace")
private val LOCATIONS = setOf("query", "header", "path", "cookie")
private val TEMPLATE = Regex("""\{([^}]+)}""")
