// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ScrubberTest {
    private val scrubber = Scrubber(listOf("secret-one", "secret-one-longer", "", "12345678"))

    @Test
    fun theLongestSecretIsReplacedWhole() {
        assertEquals("a [REDACTED] b [REDACTED]", scrubber.scrub("a secret-one-longer b secret-one"))
    }

    @Test
    fun jsonIsScrubbedKeysIncluded() {
        val json = JsonObject(
            mapOf(
                "secret-one" to JsonArray(
                    listOf(JsonPrimitive("x secret-one"), JsonPrimitive(12345678), JsonNull, JsonPrimitive(true)),
                ),
            ),
        )
        assertEquals(
            JsonObject(
                mapOf(
                    "[REDACTED]" to JsonArray(
                        listOf(
                            JsonPrimitive("x [REDACTED]"),
                            JsonPrimitive("[REDACTED]"),
                            JsonNull,
                            JsonPrimitive(true),
                        ),
                    ),
                ),
            ),
            scrubber.scrub(json),
        )
        val nothing = JsonPrimitive("x")
        assertSame(nothing, Scrubber(emptyList()).scrub(nothing))
    }

    @Test
    fun stderrKeepsItsTailAndDropsATruncatedLine() = kotlinx.coroutines.test.runTest {
        val buffer = StderrBuffer(16, scrubber)
        buffer.append("first secret")
        assertEquals("first secret", buffer.snapshot())
        buffer.append("-one\nsecond\n")
        assertEquals("second\n", buffer.snapshot())
        buffer.append("0123456789abcdefgh")
        assertEquals("", buffer.snapshot())
    }
}
