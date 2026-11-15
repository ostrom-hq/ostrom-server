// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http.docs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiDocNormalizationTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun aDocumentWithoutComponentsOrSchemasIsKeptAsItIs() {
        val bare = json("""{"paths":{}}""")
        assertEquals(bare, bare.withSingleSchemaPerType())
        val noSchemas = json("""{"components":{"securitySchemes":{}}}""")
        assertEquals(noSchemas, noSchemas.withSingleSchemaPerType())
    }

    @Test
    fun aNullableTwinBecomesTheTypeItselfAndANullableUseAnAnyOfWithNull() {
        val document = json(
            """
            {"components":{"schemas":{
              "Link":{"type":"object","title":"Link","properties":{"a":{"type":"string"}}},
              "Link2":{"type":["object","null"],"title":"Link2","properties":{"a":{"type":"string"}}},
              "Open":{"type":["object","null"]},
              "Pair":{"type":["string","integer","null"]},
              "Both":{"type":["string","integer"]},
              "Plain":{"type":"string"},
              "Other1":{"type":"object","properties":{"b":{"type":"string"}}},
              "Other":{"type":"object"},
              "Alone2":{"type":"object"},
              "Flag":true,
              "Flag2":true,
              "Same3":{"type":"object"},
              "Same":false,
              "Text":{"type":["string","null"]}
            }},
            "paths":{"/x":{"get":{"responses":{
              "200":{"${'$'}ref":"#/components/schemas/Link2"},
              "201":{"${'$'}ref":"#/components/schemas/Open"},
              "202":{"${'$'}ref":"#/components/schemas/Plain"},
              "203":{"${'$'}ref":"#/components/schemas/Link","description":"kept as it is"},
              "204":[{"${'$'}ref":"#/components/schemas/Link"}],
              "205":{"${'$'}ref":null}
            }}}}}
            """,
        )
        val expected = json(
            """
            {"components":{"schemas":{
              "Link":{"type":"object","title":"Link","properties":{"a":{"type":"string"}}},
              "Open":{"type":"object"},
              "Pair":{"type":["string","integer"]},
              "Both":{"type":["string","integer"]},
              "Plain":{"type":"string"},
              "Other1":{"type":"object","properties":{"b":{"type":"string"}}},
              "Other":{"type":"object"},
              "Alone2":{"type":"object"},
              "Flag":true,
              "Flag2":true,
              "Same3":{"type":"object"},
              "Same":false,
              "Text":{"type":"string"}
            }},
            "paths":{"/x":{"get":{"responses":{
              "200":{"anyOf":[{"${'$'}ref":"#/components/schemas/Link"},{"type":"null"}]},
              "201":{"anyOf":[{"${'$'}ref":"#/components/schemas/Open"},{"type":"null"}]},
              "202":{"${'$'}ref":"#/components/schemas/Plain"},
              "203":{"${'$'}ref":"#/components/schemas/Link","description":"kept as it is"},
              "204":[{"${'$'}ref":"#/components/schemas/Link"}],
              "205":{"${'$'}ref":null}
            }}}}}
            """,
        )
        assertEquals(expected, document.withSingleSchemaPerType())
    }

    @Test
    fun anOperationKeepsOnlyTheQueryParametersItDescribes() {
        val document = json(
            """
            {"paths":{
              "/a":{"parameters":[{"in":"query","name":"x"}],"get":{"parameters":[
                {"in":"query","name":"inherited"},
                {"in":"query","name":"limit","description":"The page size."},
                {"in":"path","name":"id"},
                {"${'$'}ref":"#/components/parameters/P"},
                7
              ]},"post":{"parameters":[{"in":"query","name":"inherited"}]},"put":{"summary":"none"}},
              "/b":true
            }}
            """,
        )
        val expected = json(
            """
            {"paths":{
              "/a":{"parameters":[{"in":"query","name":"x"}],"get":{"parameters":[
                {"in":"query","name":"limit","description":"The page size."},
                {"in":"path","name":"id"},
                {"${'$'}ref":"#/components/parameters/P"},
                7
              ]},"post":{},"put":{"summary":"none"}},
              "/b":true
            }}
            """,
        )
        assertEquals(expected, document.withoutUndescribedQueryParameters())
        val bare = json("""{"info":{}}""")
        assertEquals(bare, bare.withoutUndescribedQueryParameters())
    }
}
