// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Finds top-level fields of a JSON object without building its tree. A request body of an agent is often hundreds of
 * kilobytes of conversation; the gateway only needs `model`, `stream`, `metadata` and `store`, and must forward the
 * rest byte for byte. Scanning keeps the gateway's added latency under a millisecond where a full parse would not.
 *
 * The scan checks the structure (object, strings, brackets), not every primitive: the provider refuses what it cannot
 * read. A wanted key that appears twice, or any top-level key written with an escape, is refused, so the gateway never
 * checks one `model` while the provider reads another.
 */
object TopLevelJson {
    /**
     * Scans an object.
     *
     * @param text The JSON text.
     * @param keys The top-level keys wanted (plain ASCII names, matched as written).
     *
     * @return The raw value range of each wanted key found, with [Fields.open] the index of the opening brace; null
     *   when the text is not one well-formed JSON object, or names a wanted key twice.
     */
    fun scan(text: String, keys: Set<String>): Fields? = try {
        Scanner(text, keys).fields()
    } catch (_: MalformedJsonException) {
        null
    }

    /** A string value, or null when absent or not a string. */
    fun string(text: String, range: IntRange?): String? = range?.takeIf { text[it.first] == '"' }?.let {
        try {
            Json.decodeFromString<String>(text.substring(it))
        } catch (_: SerializationException) {
            null
        }
    }

    /** A boolean value, or null when absent or not a boolean. */
    fun boolean(text: String, range: IntRange?): Boolean? = range?.let { text.substring(it).toBooleanStrictOrNull() }

    /**
     * Rewrites a body: replaces value ranges, and inserts members right after the opening brace.
     *
     * @param text The body.
     * @param fields Its scan.
     * @param replacements Raw JSON values by range.
     * @param inserted Raw members (`"key":value`) to add.
     */
    fun rewrite(text: String, fields: Fields, replacements: Map<IntRange, String>, inserted: List<String>): String {
        val out = StringBuilder(text.length + inserted.sumOf { it.length + 1 })
        out.append(text, 0, fields.open + 1)
        out.append(inserted.joinToString(","))
        if (inserted.isNotEmpty() && fields.memberCount > 0) out.append(',')
        var cursor = fields.open + 1
        for ((range, value) in replacements.entries.sortedBy { it.key.first }) {
            out.append(text, cursor, range.first).append(value)
            cursor = range.last + 1
        }
        return out.append(text, cursor, text.length).toString()
    }

    /**
     * A scan.
     *
     * @property open Index of the opening brace.
     * @property ranges Raw value range of each wanted key.
     * @property memberCount Members of the object.
     */
    data class Fields(val open: Int, val ranges: Map<String, IntRange>, val memberCount: Int)
}

/** The text is not what [TopLevelJson.scan] accepts; never leaves the scan. */
private class MalformedJsonException : Exception("Malformed JSON.")

private fun malformed(): Nothing = throw MalformedJsonException()

/** One scan of [TopLevelJson.scan]: walks the text once, keeping only the ranges of [keys]. */
private class Scanner(private val text: String, private val keys: Set<String>) {
    private var index = 0
    private var count = 0
    private val found = mutableMapOf<String, IntRange>()

    fun fields(): TopLevelJson.Fields {
        skipSpace()
        val open = index
        expect('{')
        skipSpace()
        if (!take('}')) {
            member()
            while (take(',')) member()
            expect('}')
        }
        skipSpace()
        if (index != text.length) malformed()
        return TopLevelJson.Fields(open, found, count)
    }

    private fun member() {
        skipSpace()
        val keyEnd = string(index)
        val key = text.substring(index + 1, keyEnd - 1)
        // An escaped top-level key (`mod\u0065l`) is refused: the provider would decode it to a wanted key the scan
        // never saw. No API names a top-level field with an escape.
        if ('\\' in key) malformed()
        index = keyEnd
        skipSpace()
        expect(':')
        skipSpace()
        val start = index
        index = value(start)
        if (key in keys && found.put(key, start until index) != null) malformed()
        count++
        skipSpace()
    }

    private fun at(position: Int): Char = if (position < text.length) text[position] else END

    private fun take(char: Char): Boolean {
        val taken = at(index) == char
        if (taken) index++
        return taken
    }

    private fun expect(char: Char) {
        if (!take(char)) malformed()
    }

    private fun skipSpace() {
        while (at(index) in SPACE) index++
    }

    /** The index after the value starting at [from]. */
    private fun value(from: Int): Int = when (at(from)) {
        '"' -> string(from)
        '{', '[' -> nested(from)
        else -> primitive(from)
    }

    /**
     * The index after the closing quote of the string opening at [from]. Jumps from quote to quote (`indexOf` is the
     * fast path through long conversation text), skipping a quote escaped by an odd run of backslashes.
     */
    private fun string(from: Int): Int {
        if (at(from) != '"') malformed()
        var close = text.indexOf('"', from + 1)
        while (close > 0 && text.escapedAt(close)) close = text.indexOf('"', close + 1)
        if (close < 0) malformed()
        return close + 1
    }

    private fun nested(from: Int): Int {
        var depth = 0
        var position = from
        do {
            when (text[position]) {
                '"' -> position = string(position) - 1
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
            position++
        } while (depth > 0 && position < text.length)
        if (depth > 0) malformed()
        return position
    }

    private fun primitive(from: Int): Int {
        var position = from
        while (position < text.length && text[position] !in DELIMITERS) position++
        if (position == from) malformed()
        return position
    }

    private companion object {
        const val END = '\u0000'
        const val SPACE = " \n\r\t"
        const val DELIMITERS = ",}] \n\r\t"
    }
}

/** Whether the quote at [quote] is escaped; the string's opening quote stops the walk back. */
private fun String.escapedAt(quote: Int): Boolean {
    var backslashes = 0
    while (this[quote - 1 - backslashes] == '\\') backslashes++
    return backslashes % 2 == 1
}
