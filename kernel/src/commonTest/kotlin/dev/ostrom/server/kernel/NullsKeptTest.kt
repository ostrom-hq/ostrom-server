// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.http.NullsKept
import dev.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
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
}
