// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.testing.Contract
import run.ostrom.server.testing.ContractOperation
import run.ostrom.server.testing.ContractSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A list operation of the contract: its page size, when it declares `limit`, and its cursors (`after`, `before`).
 *
 * @property operation The operation.
 * @property maximum The `maximum` of its `limit`, or null.
 * @property paged True when it declares `limit`.
 * @property cursors The cursors it declares.
 */
internal class ListOperation(
    val operation: ContractOperation,
    val maximum: Int?,
    val paged: Boolean,
    val cursors: List<String>,
)

/** Every list operation of the contract, as [ListBoundsPostgresTest] walks them. */
internal object ListOperations {
    /** The query parameters that page a list. */
    val CURSORS = listOf("after", "before")

    /**
     * The `served` GET operations that answer a list: named `list…`, paged (`limit`, `after`, `before`), or answering
     * an array or an object with `items`. A single resource (the path ends with a parameter) is not a list.
     *
     * @param contract The contract.
     * @return The list operations, in the contract's order.
     */
    fun of(contract: Contract): List<ListOperation> = contract.operations.served
        .filter { it.method == "GET" && !it.path.endsWith("}") && it.isList(contract.schema) }
        .map { operation ->
            val limit = operation.query["limit"]
            ListOperation(
                operation,
                limit?.get("maximum")?.jsonPrimitive?.intOrNull,
                limit != null,
                CURSORS.filter { it in operation.query },
            )
        }

    /**
     * The items of a list's answer: the body itself when it is an array, else its `items`, else its first array.
     *
     * @param body The answer.
     * @return The items, or null when the body holds no array.
     */
    fun items(body: JsonElement): JsonArray? = body as? JsonArray ?: (body as? JsonObject)?.let { fields ->
        fields["items"] as? JsonArray ?: fields.values.firstNotNullOfOrNull { it as? JsonArray }
    }

    private fun ContractOperation.isList(schema: ContractSchema): Boolean {
        val declared = response(200)?.json
        val body = declared?.get("\$ref")?.jsonPrimitive?.contentOrNull?.let(schema::resolve) ?: declared
        val array = body?.get("type")?.jsonPrimitive?.contentOrNull == "array"
        val items = body?.get("properties")?.jsonObject?.containsKey("items") == true
        return id.startsWith("list") || query.keys.any { it == "limit" || it in CURSORS } || array || items
    }
}
