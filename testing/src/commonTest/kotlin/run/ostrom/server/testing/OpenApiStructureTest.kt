// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** [OpenApiStructure] finds what a generator would refuse or drop in a document. */
class OpenApiStructureTest {
    private fun document(text: String): JsonObject = Json.parseToJsonElement(text.replace("REF", "\$ref")).jsonObject

    @Test
    fun aSoundDocumentHasNoProblem() {
        val sound = document(
            """
            {
              "openapi": "3.1.1", "info": {"title": "API", "version": "1"},
              "tags": [{"name": "things"}],
              "paths": {
                "/api/v1/things/{id}": {
                  "parameters": [{"REF": "#/components/parameters/Id"}],
                  "get": {
                    "operationId": "getThing", "tags": ["things"], "security": [{"bearer": []}],
                    "parameters": [{"name": "Idempotency-Key", "in": "header", "required": true, "schema": {}}],
                    "responses": {
                      "200": {"description": "The thing.", "content": {"application/json": {"schema": {
                        "REF": "#/components/schemas/a~1b"}}}},
                      "default": {"REF": "#/components/responses/Error"}
                    }
                  },
                  "x-other": {}
                }
              },
              "components": {
                "parameters": {"Id": {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}}},
                "schemas": {"a/b": {"type": "object", "properties": {"REF": {"type": "string"}}}},
                "responses": {"Error": {"description": "An error."}},
                "securitySchemes": {"bearer": {"type": "http", "scheme": "bearer"}}
              }
            }
            """,
        )
        assertEquals(emptyList(), OpenApiStructure.problems(sound))
    }

    @Test
    fun eachBrokenPartIsNamed() {
        val broken = document(
            """
            {
              "openapi": "2.0", "info": {"title": "API"},
              "paths": {
                "things/{id}": {
                  "get": {
                    "operationId": "getThing", "tags": ["ghosts"], "security": [{"cookie": []}],
                    "parameters": [
                      {"in": "body", "schema": {}},
                      {"name": "id", "in": "path"},
                      {"name": "other", "in": "path", "schema": {}},
                      {"REF": "#/components/parameters/Gone"}
                    ],
                    "responses": {"200": {"content": {}}, "404": {"REF": "#/elsewhere"}}
                  },
                  "post": {"operationId": "getThing", "responses": {}}
                },
                "/b/{id}": {"put": {"operationId": "putB", "responses": {"204": {"description": "Done."}}}},
                "/c": {"delete": {"responses": {"204": {"description": "Done."}, "x": {"REF": "other.json#/x"}}}},
                "/d": {"get": {"operationId": "getD"}}
              },
              "x-refs": [{"REF": "#/openapi"}, {"REF": "#/openapi/x"}]
            }
            """,
        )
        assertEquals(
            listOf(
                "openapi: not an OpenAPI 3.x document (2.0)",
                "info.version: missing",
                "#/paths/things/{id}/get/parameters/3: \$ref #/components/parameters/Gone resolves to nothing",
                "#/paths/things/{id}/get/responses/404: \$ref #/elsewhere resolves to nothing",
                "#/paths//c/delete/responses/x: \$ref other.json#/x resolves to nothing",
                "#/x-refs/0: \$ref #/openapi resolves to nothing",
                "#/x-refs/1: \$ref #/openapi/x resolves to nothing",
                "things/{id}: does not start with /",
                "GET things/{id}: a parameter without a name",
                "GET things/{id}: parameter null is in body",
                "GET things/{id}: parameter id has no schema",
                "GET things/{id}: a parameter without a name",
                "GET things/{id}: parameter null is in null",
                "GET things/{id}: parameter null has no schema",
                "GET things/{id}: path parameter id is not required",
                "GET things/{id}: path parameter other is not in the path",
                "GET things/{id}: response 200 has no description",
                "GET things/{id}: tag ghosts is not declared",
                "GET things/{id}: security scheme cookie is not declared",
                "POST things/{id}: path parameter id is not declared",
                "POST things/{id}: no response",
                "POST things/{id}: operationId getThing is also GET things/{id}'s",
                "PUT /b/{id}: path parameter id is not declared",
                "DELETE /c: no operationId",
                "GET /d: no response",
            ),
            OpenApiStructure.problems(broken),
        )
    }

    @Test
    fun aDocumentWithoutItsRootFieldsSaysSo() {
        assertEquals(
            listOf(
                "openapi: not an OpenAPI 3.x document (null)",
                "info.title: missing",
                "info.version: missing",
                "paths: missing",
            ),
            OpenApiStructure.problems(document("""{"components": {}}""")),
        )
    }
}
