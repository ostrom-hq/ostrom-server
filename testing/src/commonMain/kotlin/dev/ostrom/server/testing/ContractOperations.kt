// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an operation of the contract answers with one status.
 *
 * @property json The schema of its `application/json` body, or null when it declares none (no body, or another type).
 * @property other True when it declares a body of another type too (`text/plain`…): a body that is not JSON is then
 * one of its forms, not a drift.
 */
data class ContractResponse(val json: JsonObject?, val other: Boolean = false)

/**
 * An operation of the contract.
 *
 * @property id Its `operationId`.
 * @property method `GET`, `POST`…
 * @property path Its template, `/api/v1/rules/{id}`.
 * @property served True when `x-ostrom-status` is `served`: the server must answer it as the contract says.
 * @property responses By status (`200`, `404`, `default`).
 */
data class ContractOperation(
    val id: String,
    val method: String,
    val path: String,
    val served: Boolean,
    val responses: Map<String, ContractResponse>,
) {
    /** True when a success (2xx) answers a JSON body: an operation the tests must observe at least once. */
    val answersJson: Boolean
        get() = responses.any { (status, response) -> status.startsWith("2") && response.json != null }

    /**
     * What it answers with [status]: that status, or `default`.
     *
     * @param status The HTTP status.
     * @return The response, or null when the operation does not declare it.
     */
    fun response(status: Int): ContractResponse? = responses[status.toString()] ?: responses["default"]
}

/**
 * The operations of the ostrom-core contract (`api/openapi.yaml`, as JSON), and the one a request calls. Shared by the
 * tests that must cover every operation the server serves (the strict answers of [ContractObserver], the isolation
 * matrix).
 *
 * @param schema The contract, for the responses it references.
 * @param document The whole contract.
 */
class ContractOperations(schema: ContractSchema, document: JsonObject) {
    /** Every operation, in the contract's order. */
    val all: List<ContractOperation> = document["paths"]?.jsonObject.orEmpty().flatMap { (path, item) ->
        item.jsonObject.filterKeys { it in METHODS }.map { (method, operation) ->
            operation(schema, path, method, operation.jsonObject)
        }
    }

    /** The operations marked `served`. */
    val served: List<ContractOperation> get() = all.filter { it.served }

    /**
     * The operation a request calls: same method, same number of segments, each literal segment equal; the one with the
     * most literal segments wins (`/gates/catalog` over `/gates/{id}`).
     *
     * @param method `GET`, `POST`…
     * @param path The request's path, without its query.
     * @return The operation, or null when no operation of the contract has this path.
     */
    fun match(method: String, path: String): ContractOperation? {
        val segments = segments(path)
        return all.filter { it.method == method.uppercase() }
            .mapNotNull { operation -> literals(segments(operation.path), segments)?.let { operation to it } }
            .maxByOrNull { (_, literals) -> literals }
            ?.first
    }

    private fun operation(schema: ContractSchema, path: String, method: String, operation: JsonObject) =
        ContractOperation(
            id = operation["operationId"].let { id ->
                if (id == null) "${method.uppercase()} $path" else id.jsonPrimitive.content
            },
            method = method.uppercase(),
            path = path,
            served = operation["x-ostrom-status"]?.let { it.jsonPrimitive.content } == "served",
            responses = operation["responses"]?.jsonObject.orEmpty().mapValues { (_, value) ->
                val reference = value.jsonObject[REF]?.let { schema.resolve(it.jsonPrimitive.content) }
                val response = reference ?: value.jsonObject
                val content = response["content"]?.jsonObject.orEmpty()
                val json = content[JSON]?.let { it.jsonObject["schema"] }
                ContractResponse(json?.let { it.jsonObject }, other = content.keys.any { it != JSON })
            },
        )

    private fun segments(path: String) = path.trim('/').split('/')

    // The number of literal segments when the template matches the request's segments, else null.
    private fun literals(template: List<String>, request: List<String>): Int? {
        val matched = template.size == request.size &&
            template.zip(request).all { (expected, actual) -> expected.startsWith("{") || expected == actual }
        return if (matched) template.count { !it.startsWith("{") } else null
    }
}

private const val REF = "\$ref"
private const val JSON = "application/json"
private val METHODS = setOf("get", "put", "post", "delete", "patch")
