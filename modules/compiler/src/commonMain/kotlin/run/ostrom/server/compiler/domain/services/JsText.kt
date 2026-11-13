// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.receipts.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * The few JavaScript behaviours the package text depends on, byte for byte: `JSON.stringify` (compact and with two
 * spaces), the prototype's `slug`, `toISOString`, and `localeCompare` on file paths.
 */
object JsText {
    private const val SLUG_MAX = 40
    private const val MILLIS_DIGITS = 3
    private const val ISO_SECONDS = 19

    /**
     * `JSON.stringify(text)`: the string quoted and escaped as JavaScript does.
     *
     * @param text The text.
     * @return The JSON string literal.
     */
    fun quote(text: String): String = CanonicalJson.encode(JsonPrimitive(text))

    /**
     * `JSON.stringify(value, null, 2)`, keys in insertion order.
     *
     * @param value The value.
     * @return The indented JSON.
     */
    fun pretty(value: JsonElement): String = StringBuilder().also { write(value, it, "") }.toString()

    private fun write(value: JsonElement, out: StringBuilder, indent: String) {
        val inner = "$indent  "
        when (value) {
            is JsonObject -> if (value.isEmpty()) {
                out.append("{}")
            } else {
                out.append("{\n")
                value.entries.forEachIndexed { index, (key, item) ->
                    out.append(inner).append(quote(key)).append(": ")
                    write(item, out, inner)
                    out.append(if (index < value.size - 1) ",\n" else "\n")
                }
                out.append(indent).append('}')
            }
            is JsonArray -> if (value.isEmpty()) {
                out.append("[]")
            } else {
                out.append("[\n")
                value.forEachIndexed { index, item ->
                    out.append(inner)
                    write(item, out, inner)
                    out.append(if (index < value.size - 1) ",\n" else "\n")
                }
                out.append(indent).append(']')
            }
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(if (value.isString) quote(value.content) else value.content)
        }
    }

    /**
     * The prototype's `slug`: lower case, accents taken apart (NFKD) then every run of other characters turned into
     * one `-`, at most 40 characters, [fallback] when nothing is left.
     *
     * @param text The text.
     * @param fallback What an empty slug becomes.
     * @return The slug.
     */
    fun slug(text: String, fallback: String): String {
        val decomposed = buildString { text.lowercase().forEach { append(decompose(it)) } }
        val slug = decomposed.replace(NOT_SLUG, "-").trim('-').take(SLUG_MAX)
        return slug.ifEmpty { fallback }
    }

    // NFKD of the Latin letters a team writes: the base letter, then a combining mark (not a slug character).
    private fun decompose(c: Char): String {
        val base = ACCENTED.entries.firstOrNull { c in it.value }?.key
        return if (base == null) c.toString() else "$basé"
    }

    private val ACCENTED = mapOf(
        'a' to "àáâãäå",
        'c' to "ç",
        'e' to "èéêë",
        'i' to "ìíîï",
        'n' to "ñ",
        'o' to "òóôõö",
        'u' to "ùúûü",
        'y' to "ýÿ",
    )
    private val NOT_SLUG = Regex("[^a-z0-9]+")

    /**
     * `Date.prototype.toISOString`: UTC, milliseconds, `Z`.
     *
     * @param instant The time.
     * @return The ISO-8601 text.
     */
    fun iso(instant: Instant): String {
        val text = instant.toString()
        val seconds = text.take(ISO_SECONDS)
        val fraction = text.drop(ISO_SECONDS).removeSuffix("Z").removePrefix(".")
        return "$seconds.${fraction.padEnd(MILLIS_DIGITS, '0').take(MILLIS_DIGITS)}Z"
    }

    /**
     * `a.localeCompare(b)` on ASCII paths, as ICU's root collation orders them: punctuation first in its own order,
     * then digits, then letters regardless of case; at equal letters, lower case first.
     */
    val localeOrder: Comparator<String> = Comparator { a, b ->
        val primary = compareKeys(a.map(::weight), b.map(::weight))
        if (primary != 0) primary else compareKeys(a.map(::case), b.map(::case))
    }

    private const val PUNCTUATION = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$0123456789abcdefghijklmnopqrstuvwxyz"

    private fun case(c: Char): Int = if (c.isUpperCase()) 1 else 0

    private fun weight(c: Char): Int {
        val index = PUNCTUATION.indexOf(c.lowercaseChar())
        return if (index >= 0) index else PUNCTUATION.length + c.code
    }

    private fun compareKeys(a: List<Int>, b: List<Int>): Int {
        val differing = a.zip(b).firstOrNull { (x, y) -> x != y }
        return differing?.let { (x, y) -> x.compareTo(y) } ?: a.size.compareTo(b.size)
    }
}
