// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ContractOperations] reads the operations of the contract and finds the one a request calls. */
class ContractOperationsTest {
    private val operations = ContractFixtures.contract.operations

    @Test
    fun everyOperationIsReadWithItsStatusAndResponses() {
        assertEquals(
            listOf(
                "listThings",
                "createThing",
                "getThing",
                "DELETE /api/v1/things/{id}",
                "exportThings",
                "failing",
                "bare",
            ),
            operations.all.map { it.id },
            "The path's parameters are not an operation; an operation without an id is named by its route",
        )
        assertEquals(
            listOf("listThings", "createThing", "getThing", "exportThings", "bare"),
            operations.served.map { it.id },
        )
        val list = operations.all.first { it.id == "listThings" }
        assertEquals("GET", list.method)
        assertEquals("/api/v1/things", list.path)
        assertNotNull(list.response(200)?.json, "Its schema, by reference")
        assertNotNull(list.response(401)?.json, "A shared response, by reference")
        assertNull(list.response(500), "Not declared")
        val create = operations.all.first { it.id == "createThing" }
        assertNull(create.response(204)?.json, "No body")
        assertNotNull(create.response(500)?.json, "The default response")
        val export = operations.all.first { it.id == "exportThings" }
        assertNull(export.response(200)?.json, "Not JSON")
        assertNull(export.response(202)?.json, "JSON without a schema")
        assertNotNull(export.response(404)?.json, "JSON, or plain text")
        assertTrue(export.response(404)?.other == true, "Plain text too")
        assertFalse(list.response(200)?.other == true, "JSON only")
    }

    @Test
    fun anOperationKnowsItsQueryParametersAndTheirSchemas() {
        val list = operations.all.first { it.id == "listThings" }
        assertEquals(listOf("limit", "after", "tag"), list.query.keys.toList(), "The path's header is not a query")
        assertEquals("100", list.query.getValue("limit")["maximum"].toString())
        assertEquals("\"uuid\"", list.query.getValue("after")["format"].toString(), "Its schema, by reference")
        assertEquals(JsonObject(emptyMap()), list.query.getValue("tag"), "No schema declared")
        assertEquals(emptyMap(), operations.all.first { it.id == "getThing" }.query, "A path parameter only")
        assertEquals(emptyMap(), operations.all.first { it.id == "createThing" }.query, "None of its own")
    }

    @Test
    fun onlyASuccessWithAJsonBodyMustBeObserved() {
        val answering = operations.all.filter { it.answersJson }.map { it.id }
        assertEquals(listOf("listThings", "getThing", "DELETE /api/v1/things/{id}"), answering)
        assertFalse(operations.all.first { it.id == "failing" }.answersJson, "An error's JSON is not a success")
    }

    @Test
    fun aRequestFindsItsOperationTheMostLiteralFirst() {
        assertEquals("exportThings", operations.match("GET", "/api/v1/things/export")?.id)
        assertEquals("getThing", operations.match("GET", "/api/v1/things/t-1")?.id)
        assertEquals("createThing", operations.match("post", "/api/v1/things/")?.id)
        assertNull(operations.match("PUT", "/api/v1/things"), "No such method")
        assertNull(operations.match("GET", "/api/v1/things/t-1/more"), "No such length")
        assertNull(operations.match("GET", "/api/v1/stuff"), "No such literal")
    }

    @Test
    fun aContractWithoutPathsHasNoOperation() {
        val empty = JsonObject(emptyMap())
        assertTrue(ContractOperations(ContractSchema(empty), empty).all.isEmpty())
    }
}
