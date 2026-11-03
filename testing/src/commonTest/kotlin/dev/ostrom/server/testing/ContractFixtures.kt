// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

/** A small contract, shaped as ostrom-core's `api/openapi.yaml` read as JSON. */
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
                "operationId": "listThings", "x-ostrom-status": "served",
                "responses": {
                  "200": {"content": {"application/json": {"schema": {"$R": "#/components/schemas/Things"}}}},
                  "401": {"$R": "#/components/responses/Error"}
                }
              },
              "post": {
                "operationId": "createThing", "x-ostrom-status": "served",
                "responses": {"204": {"description": "Created"}, "default": {"$R": "#/components/responses/Error"}}
              }
            },
            "/api/v1/things/{id}": {
              "get": {
                "operationId": "getThing", "x-ostrom-status": "served",
                "responses": {
                  "200": {"content": {"application/json": {"schema": {"$R": "#/components/schemas/Thing"}}}}
                }
              },
              "delete": {
                "x-ostrom-status": "planned",
                "responses": {
                  "200": {"content": {"application/json": {"schema": {"$R": "#/components/schemas/Thing"}}}}
                }
              }
            },
            "/api/v1/things/export": {
              "get": {
                "operationId": "exportThings", "x-ostrom-status": "served",
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
            "/api/v1/things/stream": {
              "get": {
                "operationId": "streamThings", "x-ostrom-status": "served",
                "responses": {
                  "200": {
                    "content": {
                      "text/event-stream": {
                        "schema": {"type": "string"},
                        "x-ostrom-event-data": {"$R": "#/components/schemas/Thing"},
                        "x-ostrom-event-done": "[DONE]"
                      }
                    }
                  }
                }
              }
            },
            "/api/v1/errors": {
              "get": {"operationId": "failing", "responses": {"400": {"$R": "#/components/responses/Error"}}}
            },
            "/api/v1/bare": {"get": {"operationId": "bare", "x-ostrom-status": "served"}}
          },
          "components": {
            "schemas": {
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
    val contract: Contract = ContractSchema(document).let { Contract(it, ContractOperations(it, document)) }

    /**
     * A fresh directory under the system's temporary one.
     *
     * @return Its path.
     */
    fun directory(): Path = Path(SystemTemporaryDirectory, "ostrom-contract-${Random.nextLong().toULong()}").also {
        SystemFileSystem.createDirectories(it)
    }

    /**
     * The contract written to a file, as CI writes ostrom-core main's.
     *
     * @return The file's path.
     */
    fun file(): String = Path(directory(), "openapi.json").also { path ->
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }.toString()
}
