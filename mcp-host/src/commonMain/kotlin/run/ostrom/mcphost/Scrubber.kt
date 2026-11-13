// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Replaces every secret value of a process by [REDACTED], in text and in decoded JSON strings (keys included).
 *
 * @param secrets The process's secret values.
 */
class Scrubber(secrets: Collection<String>) {
    // Longest first, so a secret that contains another is replaced whole.
    private val secrets = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    /**
     * Scrubs text.
     *
     * @param text The text.
     */
    fun scrub(text: String): String = secrets.fold(text) { current, secret -> current.replace(secret, REDACTED) }

    /**
     * Scrubs decoded values, so the result stays valid JSON whatever characters a secret holds.
     *
     * @param element The JSON.
     */
    fun scrub(element: JsonElement): JsonElement = if (secrets.isEmpty()) {
        element
    } else {
        when (element) {
            is JsonObject -> JsonObject(element.entries.associate { (key, value) -> scrub(key) to scrub(value) })
            is JsonArray -> JsonArray(element.map { scrub(it) })
            // A number or boolean equal to a secret becomes the redacted string too.
            is JsonPrimitive -> if (element is JsonNull || secrets.none { it in element.content }) {
                element
            } else {
                JsonPrimitive(scrub(element.content))
            }
        }
    }

    /** The replacement. */
    companion object {
        /** What a secret becomes. */
        const val REDACTED: String = "[REDACTED]"
    }
}

/**
 * The last characters a process wrote to stderr (and its log notifications), in memory only. Raw text is kept and
 * scrubbed when read, so a secret split across two reads is still caught; a truncated first line is dropped for the
 * same reason.
 *
 * @param capacity The characters kept.
 * @param scrubber The process's scrubber.
 */
class StderrBuffer(private val capacity: Int, private val scrubber: Scrubber) {
    private data class Kept(val text: String, val truncated: Boolean)

    private val lock = Mutex()
    private var kept = Kept("", false)

    /**
     * Appends a chunk.
     *
     * @param chunk The text.
     */
    suspend fun append(chunk: String) = lock.withLock {
        val text = kept.text + chunk
        kept = if (text.length > capacity) Kept(text.takeLast(capacity), true) else Kept(text, kept.truncated)
    }

    /** The kept text, scrubbed. */
    suspend fun snapshot(): String {
        val current = lock.withLock { kept }
        val text = if (!current.truncated) {
            current.text
        } else {
            current.text.indexOf('\n').let { if (it < 0) "" else current.text.substring(it + 1) }
        }
        return scrubber.scrub(text)
    }
}
