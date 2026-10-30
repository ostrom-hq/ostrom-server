package dev.ostrom.server.gateway.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Finds top-level fields of a JSON object without building its tree. A request body of an agent is often hundreds of
 * kilobytes of conversation; the gateway only needs `model`, `stream`, `metadata` and `store`, and must forward the
 * rest byte for byte. Scanning keeps the gateway's added latency under a millisecond where a full parse would not.
 */
object TopLevelJson {
    /**
     * Scans an object.
     *
     * @param text The JSON text.
     * @param keys The top-level keys wanted (plain ASCII names, matched as written).
     *
     * @return The raw value range of each wanted key found (the last one when repeated), with [Fields.open] the index
     *   of the opening brace; null when the text is not one well-formed JSON object.
     */
    fun scan(text: String, keys: Set<String>): Fields? {
        var index = skipSpace(text, 0)
        if (index >= text.length || text[index] != '{') return null
        val open = index
        val found = mutableMapOf<String, IntRange>()
        var count = 0
        index = skipSpace(text, index + 1)
        if (index < text.length && text[index] == '}') return if (skipSpace(text, index + 1) == text.length) Fields(open, found, 0) else null
        while (true) {
            if (index >= text.length || text[index] != '"') return null
            val keyEnd = skipString(text, index)
            if (keyEnd < 0) return null
            val key = text.substring(index + 1, keyEnd - 1)
            index = skipSpace(text, keyEnd)
            if (index >= text.length || text[index] != ':') return null
            index = skipSpace(text, index + 1)
            val valueEnd = skipValue(text, index)
            if (valueEnd < 0) return null
            if (key in keys) found[key] = index until valueEnd
            count++
            index = skipSpace(text, valueEnd)
            if (index >= text.length) return null
            when (text[index]) {
                ',' -> index = skipSpace(text, index + 1)
                '}' -> return if (skipSpace(text, index + 1) == text.length) Fields(open, found, count) else null
                else -> return null
            }
        }
    }

    /** A string value, or null when absent or not a string. */
    fun string(text: String, range: IntRange?): String? {
        if (range == null || text[range.first] != '"') return null
        return try {
            Json.decodeFromString<String>(text.substring(range.first, range.last + 1))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** A boolean value, or null when absent or not a boolean. */
    fun boolean(text: String, range: IntRange?): Boolean? = when (range?.let { text.substring(it.first, it.last + 1) }) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /**
     * Rewrites a body: replaces value ranges, and inserts members right after the opening brace.
     *
     * @param text The body.
     * @param fields Its scan.
     * @param replacements Raw JSON values by range.
     * @param inserted Raw members (`"key":value`) to add.
     */
    fun rewrite(text: String, fields: Fields, replacements: Map<IntRange, String>, inserted: List<String> = emptyList()): String {
        if (replacements.isEmpty() && inserted.isEmpty()) return text
        val out = StringBuilder(text.length + 64)
        out.append(text, 0, fields.open + 1)
        if (inserted.isNotEmpty()) {
            out.append(inserted.joinToString(","))
            if (fields.memberCount > 0) out.append(',')
        }
        var cursor = fields.open + 1
        for ((range, value) in replacements.entries.sortedBy { it.key.first }) {
            out.append(text, cursor, range.first)
            out.append(value)
            cursor = range.last + 1
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /**
     * A scan.
     *
     * @property open Index of the opening brace.
     * @property ranges Raw value range of each wanted key.
     * @property memberCount Members of the object.
     */
    data class Fields(val open: Int, val ranges: Map<String, IntRange>, val memberCount: Int)

    private fun skipSpace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && (text[index] == ' ' || text[index] == '\n' || text[index] == '\r' || text[index] == '\t')) index++
        return index
    }

    /** The index after the closing quote of the string opening at [from], or -1. */
    private fun skipString(text: String, from: Int): Int {
        var index = from + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                '"' -> return index + 1
                else -> index++
            }
        }
        return -1
    }

    /** The index after the value starting at [from], or -1 when it is malformed. */
    private fun skipValue(text: String, from: Int): Int {
        if (from >= text.length) return -1
        return when (text[from]) {
            '"' -> skipString(text, from)
            '{', '[' -> skipNested(text, from)
            else -> {
                var index = from
                while (index < text.length && text[index] !in ",}] \n\r\t") index++
                if (index == from) -1 else index
            }
        }
    }

    private fun skipNested(text: String, from: Int): Int {
        var depth = 0
        var index = from
        while (index < text.length) {
            when (text[index]) {
                '"' -> {
                    index = skipString(text, index)
                    if (index < 0) return -1
                    continue
                }
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
            index++
        }
        return -1
    }
}
