package dev.ostrom.server.gateway.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads token usage from a provider's answer as it streams through, without holding it: bytes are split into lines,
 * and only a line that can carry usage (a `data:` line naming `usage`) is parsed. Prompt and output text are never
 * kept beyond the current line, and a line longer than 256 KiB is dropped. Works for Anthropic Messages events,
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
        for (index in 0 until count) {
            val byte = bytes[index]
            when {
                byte == NEWLINE -> endLine()
                dropping -> Unit
                length == MAX_LINE -> dropping = true
                else -> append(byte)
            }
        }
    }

    /** Reads a whole non-streamed JSON answer. */
    fun acceptBody(body: ByteArray) {
        parse(body.decodeToString())?.let(::read)
    }

    private fun append(byte: Byte) {
        if (length == line.size) line = line.copyOf(minOf(line.size * 2, MAX_LINE))
        line[length++] = byte
    }

    private fun endLine() {
        if (!dropping && length > DATA.length && line[0] == DATA_START) {
            val text = line.decodeToString(0, length)
            if (text.startsWith(DATA) && "usage" in text) parse(text.substring(DATA.length).trim())?.let(::read)
        }
        length = 0
        dropping = false
    }

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

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.count(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..MAX_TOKENS }

    private companion object {
        const val NEWLINE: Byte = 10
        const val DATA = "data:"
        const val DATA_START: Byte = 100
        const val INITIAL = 1024
        const val MAX_LINE = 256 * 1024
        const val MAX_TOKENS = 100_000_000L
    }
}
