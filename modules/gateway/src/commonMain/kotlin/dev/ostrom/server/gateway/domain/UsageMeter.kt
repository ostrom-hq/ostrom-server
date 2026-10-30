// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads token usage from a provider's answer as it streams through, without holding it: bytes are split into lines,
 * and only a line that can carry usage (a `data:` line naming `usage`) is parsed. Prompt and output text are never
 * kept beyond the current line, and a line longer than 8 MiB is dropped (a Responses `response.completed` event carries
 * the whole response on one line). Works for Anthropic Messages events,
 * OpenAI Responses events and Chat Completions chunks, streamed (server-sent events) or not (one JSON body,
 * [acceptBody]).
 *
 * @param protocol The API shape of the answer.
 */
class UsageMeter(private val protocol: WireProtocol) {
    private var line = ByteArray(INITIAL)
    private var length = 0
    private var dropping = false
    private var input = 0L
    private var output = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L
    private var seen = false

    /** The usage seen so far, or null when the answer reported none. */
    val usage: TokenUsage? get() = if (seen) TokenUsage(input, output, cacheRead, cacheWrite) else null

    /** Feeds streamed bytes: the first [count] of [bytes]. */
    fun accept(bytes: ByteArray, count: Int) {
        var start = 0
        while (start < count) {
            val newline = newlineIn(bytes, start, count)
            take(bytes, start, newline)
            if (newline < count) endLine()
            start = newline + 1
        }
    }

    /** Reads a whole non-streamed JSON answer. */
    fun acceptBody(body: ByteArray) {
        parseJsonObject(body.decodeToString())?.let(::read)
    }

    /** Adds `bytes[from until to]` to the current line, or drops the line once it outgrows [MAX_LINE]. */
    private fun take(bytes: ByteArray, from: Int, to: Int) {
        val size = to - from
        when {
            dropping || size == 0 -> Unit
            length + size > MAX_LINE -> dropping = true
            else -> {
                // INITIAL and MAX_LINE are powers of two: doubling never passes MAX_LINE.
                while (line.size < length + size) line = line.copyOf(line.size * 2)
                bytes.copyInto(line, length, from, to)
                length += size
            }
        }
    }

    /** Parses the line only when it is a `data:` line naming `usage`: most lines are text and never decoded. */
    private fun endLine() {
        if (!dropping && isUsageLine()) {
            parseJsonObject(line.decodeToString(DATA.size, length).trim())?.let(::read)
        }
        length = 0
        dropping = false
    }

    private fun isUsageLine() = length > DATA.size && line.matches(DATA, 0) && line.has(USAGE, DATA.size, length)

    private fun read(json: JsonObject) {
        when (protocol) {
            WireProtocol.MESSAGES -> {
                // message_start carries message.usage, message_delta carries usage; a whole message carries usage.
                json.child("message")?.child("usage")?.let(::anthropic)
                json.child("usage")?.let(::anthropic)
            }
            WireProtocol.RESPONSES -> (json.child("response")?.child("usage") ?: json.child("usage"))
                ?.let { openAi(it, "input_tokens", "input_tokens_details", "output_tokens") }
            WireProtocol.CHAT -> json.child("usage")
                ?.let { openAi(it, "prompt_tokens", "prompt_tokens_details", "completion_tokens") }
        }
    }

    private fun anthropic(usage: JsonObject) {
        // Counts only grow along a stream: keep the largest seen.
        usage.count("input_tokens")?.let {
            seen = true
            input = maxOf(input, it)
        }
        usage.count("output_tokens")?.let {
            seen = true
            output = maxOf(output, it)
        }
        usage.count("cache_read_input_tokens")?.let { cacheRead = maxOf(cacheRead, it) }
        usage.count("cache_creation_input_tokens")?.let { cacheWrite = maxOf(cacheWrite, it) }
    }

    /** OpenAI counts cached tokens inside the input; the ledger keeps them apart. */
    private fun openAi(usage: JsonObject, inputName: String, detailsName: String, outputName: String) {
        usage.count(inputName)?.let { total ->
            val cached = usage.child(detailsName)?.count("cached_tokens")?.coerceAtMost(total) ?: 0
            seen = true
            input = total - cached
            cacheRead = cached
            output = usage.count(outputName) ?: output
        }
    }
}

private const val NEWLINE: Byte = 10
private const val INITIAL = 1024
private const val MAX_LINE = 8 * 1024 * 1024
private const val MAX_TOKENS = 100_000_000L
private val DATA = "data:".encodeToByteArray()
private val USAGE = "\"usage\"".encodeToByteArray()

/** The index of the first newline of `bytes[from until to]`, or [to]. */
private fun newlineIn(bytes: ByteArray, from: Int, to: Int): Int {
    var index = from
    while (index < to && bytes[index] != NEWLINE) index++
    return index
}

/** Whether [pattern] occurs in `this[from until to]`. */
private fun ByteArray.has(pattern: ByteArray, from: Int, to: Int): Boolean {
    var index = from
    while (index + pattern.size <= to && !matches(pattern, index)) index++
    return index + pattern.size <= to
}

private fun ByteArray.matches(pattern: ByteArray, at: Int): Boolean {
    var index = 0
    while (index < pattern.size && this[at + index] == pattern[index]) index++
    return index == pattern.size
}

/** [text] as a JSON object, or null when it is not one. */
internal fun parseJsonObject(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: SerializationException) {
    null
}

private fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.count(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..MAX_TOKENS }
