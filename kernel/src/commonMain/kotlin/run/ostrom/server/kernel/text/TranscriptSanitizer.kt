// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.text

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Bounds, masks and strips reasoning from the data of a session's event before it is stored (TeamCode's
 * `CloudTranscriptSanitizer`). Ostrom has no transcript yet: the component is ready for the first one.
 */
object TranscriptSanitizer {
    /** Nesting kept. */
    const val MAX_DEPTH: Int = 16

    /** Entries of an object or an array kept. */
    const val MAX_ENTRIES: Int = 200

    /** Characters of a key kept. */
    const val MAX_KEY_CHARS: Int = 64

    /** The keys (and the `type` values) of a model's reasoning, never stored. */
    val DROPPED_KEYS: Set<String> = setOf("thinking", "signature", "reasoning", "redacted_thinking")

    private const val IDENTITY_LENGTH = 128
    private const val NAME_LENGTH = 200

    /** The identity fields a cut event keeps, with their longest kept length. */
    private val IDENTITY_STRINGS: List<Pair<String, Int>> =
        listOf("id", "parentToolUseId", "toolUseId", "requestId", "messageId", "inputId")
            .map { it to IDENTITY_LENGTH } + ("name" to NAME_LENGTH)

    /**
     * Encodes an event's data for storage.
     *
     * @param data The data as sent.
     *
     * @return The sanitized JSON; an event above [TranscriptMask.MAX_EVENT_BYTES] becomes `{truncated, originalBytes}`
     *   with the short fields that link events together (a tool result to its call, a subagent's events to their
     *   parent), taken from the sanitized event, never any other field.
     */
    fun encode(data: JsonObject): String {
        val clean = sanitize(data, 0) as JsonObject
        val encoded = clean.toString()
        val size = encoded.encodeToByteArray().size
        if (size <= TranscriptMask.MAX_EVENT_BYTES) return encoded
        return buildJsonObject {
            put("truncated", true)
            put("originalBytes", size)
            for ((key, limit) in IDENTITY_STRINGS) {
                (clean[key] as? JsonPrimitive)
                    ?.takeIf { it.isString && it.content.length <= limit }
                    ?.let { put(key, it) }
            }
            (clean["isError"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { put("isError", it) }
            (clean["index"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
                ?.let { put("index", it) }
        }.toString()
    }

    private fun dropped(element: JsonElement): Boolean =
        element is JsonObject && (element["type"] as? JsonPrimitive)?.contentOrNull in DROPPED_KEYS

    private fun sanitize(element: JsonElement, depth: Int): JsonElement = when (element) {
        is JsonObject -> if (depth >= MAX_DEPTH) {
            JsonPrimitive(TranscriptMask.TRUNCATION_MARK)
        } else {
            JsonObject(
                element.entries.asSequence()
                    .filter { (key, value) -> key !in DROPPED_KEYS && !dropped(value) }
                    .take(MAX_ENTRIES)
                    .associate { (key, value) -> key.take(MAX_KEY_CHARS) to sanitize(value, depth + 1) },
            )
        }
        is JsonArray -> if (depth >= MAX_DEPTH) {
            JsonPrimitive(TranscriptMask.TRUNCATION_MARK)
        } else {
            JsonArray(element.filterNot { dropped(it) }.take(MAX_ENTRIES).map { sanitize(it, depth + 1) })
        }
        is JsonPrimitive -> if (element.isString) JsonPrimitive(TranscriptMask.bound(element.content)) else element
    }
}
