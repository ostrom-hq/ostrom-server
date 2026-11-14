// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import run.ostrom.server.kernel.text.TranscriptSanitizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The marker that replaces an event too large to store keeps the short fields a client links events with. */
class TranscriptSanitizerTest {
    private fun encode(data: JsonObject) = Json.parseToJsonElement(TranscriptSanitizer.encode(data)).jsonObject

    @Test
    fun aCutEventKeepsItsIdentity() {
        val marker = encode(
            buildJsonObject {
                put("id", "toolu_01")
                put("parentToolUseId", "toolu_parent")
                put("isError", true)
                put("exitCode", 1)
                put("name", "Bash")
                put("toolUseId", "toolu_02")
                put("requestId", "req-1")
                put("messageId", "msg_01")
                put("inputId", "in-1")
                put("index", 2)
                repeat(5) { put("output$it", "z".repeat(15_000)) }
            },
        )

        assertEquals(true, marker["truncated"]?.jsonPrimitive?.boolean)
        assertTrue(marker.getValue("originalBytes").jsonPrimitive.int > 60_000)
        val kept = listOf(
            "id" to "toolu_01", "parentToolUseId" to "toolu_parent", "name" to "Bash", "toolUseId" to "toolu_02",
            "requestId" to "req-1", "messageId" to "msg_01", "inputId" to "in-1",
        )
        for ((key, value) in kept) assertEquals(value, marker[key]?.jsonPrimitive?.content, key)
        assertEquals(true, marker["isError"]?.jsonPrimitive?.boolean)
        assertEquals(2, marker["index"]?.jsonPrimitive?.int)
        assertEquals(
            setOf(
                "truncated", "originalBytes", "id", "parentToolUseId", "name", "toolUseId", "requestId", "messageId",
                "inputId", "isError", "index",
            ),
            marker.keys,
            "Never another field: the exit code and the output stay out",
        )
        assertEquals(listOf("truncated", "originalBytes"), marker.keys.take(2))
    }

    @Test
    fun onlyShortWellTypedIdentityFieldsAreKept() {
        val marker = encode(
            buildJsonObject {
                put("id", "x".repeat(129))
                put("name", "n".repeat(201))
                put("isError", "true")
                put("requestId", 7)
                put("messageId", "m".repeat(128))
                put("index", "2")
                putJsonObject("parentToolUseId") { put("id", "nested") }
                repeat(5) { put("output$it", "z".repeat(15_000)) }
            },
        )

        assertEquals(setOf("truncated", "originalBytes", "messageId"), marker.keys)
    }

    @Test
    fun aNegativeIndexAndAFractionalOneAreLeftOut() {
        for (index in listOf(-1, 2.5)) {
            val marker = encode(
                buildJsonObject {
                    put("index", JsonPrimitive(index))
                    repeat(5) { put("output$it", "z".repeat(15_000)) }
                },
            )
            assertEquals(setOf("truncated", "originalBytes"), marker.keys, index.toString())
        }
    }

    @Test
    fun identityIsMaskedLikeTheRestOfTheTranscript() {
        val secret = "sk-ant-1" + "x".repeat(40)
        val marker = encode(
            buildJsonObject {
                put("id", secret.take(100))
                repeat(5) { put("output$it", "z".repeat(15_000)) }
            },
        )

        assertFalse(marker["id"]?.jsonPrimitive?.content.orEmpty().contains("x".repeat(40)))
    }

    @Test
    fun anEventUnderTheLimitIsUnchanged() {
        val data = buildJsonObject {
            put("id", "toolu_01")
            put("output", "short")
            put("count", 3)
            put("none", JsonNull)
        }

        assertEquals(data, encode(data))
    }

    @Test
    fun reasoningIsDroppedAndTextIsMaskedAndBounded() {
        val data = buildJsonObject {
            put("thinking", "private")
            put("text", "token ghp_" + "A1b2".repeat(6) + " " + "y".repeat(20_000))
            val reasoning = buildJsonObject { put("type", "redacted_thinking") }
            put("content", JsonArray(listOf(reasoning, JsonPrimitive("kept"))))
            putJsonObject("block") {
                put("type", "thinking")
                put("signature", "s")
            }
        }

        val clean = encode(data)

        assertEquals(setOf("text", "content"), clean.keys)
        assertEquals(listOf(JsonPrimitive("kept")), clean.getValue("content").let { (it as JsonArray).toList() })
        val text = clean.getValue("text").jsonPrimitive.content
        assertFalse("ghp_" in text)
        assertEquals(16_000, text.length)
    }

    @Test
    fun nestingKeysAndEntriesAreBounded() {
        var deep: kotlinx.serialization.json.JsonElement = JsonPrimitive("leaf")
        repeat(20) { deep = buildJsonObject { put("n", deep) } }
        val data = buildJsonObject {
            put("deep", deep)
            put("k".repeat(100), 1)
            put("list", JsonArray((1..300).map { JsonPrimitive(it) }))
        }

        val clean = encode(data)

        assertTrue(TranscriptSanitizer.encode(data).contains("…[truncated]"))
        assertTrue(clean.keys.any { it.length == 64 })
        assertEquals(200, (clean.getValue("list") as JsonArray).size)
        var deepArray: kotlinx.serialization.json.JsonElement = JsonPrimitive("leaf")
        repeat(20) { deepArray = JsonArray(listOf(deepArray)) }
        assertTrue(TranscriptSanitizer.encode(buildJsonObject { put("a", deepArray) }).contains("…[truncated]"))
        assertEquals(200, encode(JsonObject((1..300).associate { "k$it" to JsonPrimitive(it) })).size)
    }
}
