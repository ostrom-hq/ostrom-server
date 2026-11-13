// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.http.NullsKept
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.withNulls
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class NullsKeptTest {

    @OptIn(ExperimentalSerializationApi::class)
    @KeepGeneratedSerializer
    @Serializable(with = Token.Answer::class)
    data class Token(
        val id: String,
        val expiresAt: String?,
        val lastUsedAt: String?,
        val connectedVia: String? = null,
    ) {
        object Answer : NullsKept<Token>(generatedSerializer(), "expiresAt", "lastUsedAt")
    }

    @Serializable
    data class Created(val token: Token)

    @Test
    fun aRequiredAndNullableFieldIsWrittenNullAndAnOptionalOneStaysAbsent() {
        assertEquals(
            """{"id":"t1","expiresAt":null,"lastUsedAt":null}""",
            OstromJson.encodeToString(Token.serializer(), Token("t1", null, null)),
        )
    }

    @Test
    fun aValueIsWrittenAsItIsAndTheClassKeepsItsSerializerInsideAnother() {
        assertEquals(
            """{"token":{"id":"t1","expiresAt":"2026-11-01","connectedVia":"github","lastUsedAt":null}}""",
            OstromJson.encodeToString(Created.serializer(), Created(Token("t1", "2026-11-01", null, "github"))),
        )
    }

    @Test
    fun readingIsTheGeneratedSerializers() {
        val read = OstromJson.decodeFromString(Token.serializer(), """{"id":"t1","lastUsedAt":"2026-10-30"}""")
        assertEquals(Token("t1", null, "2026-10-30"), read)
    }

    @Test
    fun aPathReachesTheFieldsOfAnObjectFieldAndOfEachObjectOfAnArray() {
        val written = OstromJson.parseToJsonElement(
            """{"limits":{"sso":true},"edges":[{"from":"a"},{"from":"b","to":"c"},{"from":"d","to":null}]}""",
        ).withNulls("limits.maxPeople", "edges.*.to")
        assertEquals(
            OstromJson.parseToJsonElement(
                """{"limits":{"sso":true,"maxPeople":null},""" +
                    """"edges":[{"from":"a","to":null},{"from":"b","to":"c"},{"from":"d","to":null}]}""",
            ),
            written,
        )
    }

    @Test
    fun aPathThroughAnAbsentFieldOrAFieldOfAnotherKindWritesNothing() {
        val body = OstromJson.parseToJsonElement("""{"edges":"none","limits":[1],"count":2}""")
        assertEquals(body, body.withNulls("missing.to", "edges.*.to", "limits.maxPeople", "count.*.x"))
        assertEquals(JsonPrimitive(1), JsonPrimitive(1).withNulls("repo"))
    }
}
