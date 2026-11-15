// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A small contract, shaped as the server's generated OpenAPI document. */
object ContractFixtures {
    private const val R = "\$ref"

    /** The contract's text. */
    val text: String = """
        {
          "openapi": "3.1.0",
          "paths": {
            "/api/v1/things": {
              "parameters": [{"$R": "#/components/parameters/Org"}],
              "get": {
                "operationId": "listThings",
                "parameters": [
                  {"name": "limit", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 100}},
                  {"name": "after", "in": "query", "schema": {"$R": "#/components/schemas/Cursor"}},
                  {"name": "tag", "in": "query"}
                ],
                "responses": {
                  "200": {"content": {"application/json": {"schema": {"$R": "#/components/schemas/Things"}}}},
                  "401": {"$R": "#/components/responses/Error"}
                }
              },
              "post": {
                "operationId": "createThing",
                "responses": {"204": {"description": "Created"}, "default": {"$R": "#/components/responses/Error"}}
              }
            },
            "/api/v1/things/{id}": {
              "get": {
                "operationId": "getThing",
                "parameters": [{"name": "id", "in": "path", "required": true, "schema": {"type": "string"}}],
                "responses": {
                  "200": {"content": {"application/json": {"schema": {"$R": "#/components/schemas/Thing"}}}}
                }
              },
              "delete": {
                "responses": {
                  "200": {"content": {"application/json": {"schema": {"$R": "#/components/schemas/Thing"}}}}
                }
              }
            },
            "/api/v1/things/export": {
              "get": {
                "operationId": "exportThings",
                "responses": {
                  "200": {"content": {"text/csv": {"schema": {"type": "string"}}}},
                  "202": {"content": {"application/json": {}}},
                  "404": {
                    "content": {
                      "text/plain": {"schema": {"type": "string"}},
                      "application/json": {"schema": {"$R": "#/components/schemas/ApiError"}}
                    }
                  }
                }
              }
            },
            "/api/v1/errors": {
              "get": {"operationId": "failing", "responses": {"400": {"$R": "#/components/responses/Error"}}}
            },
            "/api/v1/bare": {"get": {"operationId": "bare"}}
          },
          "components": {
            "parameters": {"Org": {"name": "org", "in": "header", "schema": {"type": "string"}}},
            "schemas": {
              "Cursor": {"type": "string", "format": "uuid"},
              "Things": {
                "type": "object",
                "properties": {"items": {"type": "array", "items": {"$R": "#/components/schemas/Thing"}}},
                "required": ["items"]
              },
              "Thing": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]},
              "ApiError": {"type": "object", "properties": {"error": {"type": "object"}}, "required": ["error"]}
            },
            "responses": {
              "Error": {"content": {"application/json": {"schema": {"$R": "#/components/schemas/ApiError"}}}}
            }
          }
        }
    """

    /** The contract as JSON. */
    val document: JsonObject = Json.parseToJsonElement(text).jsonObject

    /** The contract, read as the observer reads it. */
    val contract: Contract = Contract.parse(text)
}
