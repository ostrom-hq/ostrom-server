package dev.ostrom.server.gateway.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads token usage from a provider's answer as it streams through, without holding it: bytes are split into lines,
 * and only a line that can carry usage (it names `usage`) is parsed. Prompt and output text are never kept beyond the
 * current line, and a line longer than 256 KiB is dropped. Works for Anthropic Messages events, OpenAI Responses
 * events and Chat Completions chunks, streamed (server-sent events) or not (one JSON body, [acceptBody]).
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

    /** Feeds streamed bytes. */
    fun accept(bytes: ByteArray, count: Int = bytes.size) {
        for (index in 0 until count) {
            val byte = bytes[index]
            if (byte == NEWLINE) {
                if (!dropping) consumeLine()
                length = 0
                dropping = false
            } else if (!dropping) {
                if (length == MAX_LINE) {
                    dropping = true
                    length = 0
                } else {
                    if (length == line.size) line = line.copyOf(minOf(line.size * 2, MAX_LINE))
                    line[length++] = byte
                }
            }
        }
    }

    /** Reads a whole non-streamed JSON answer. */
    fun acceptBody(body: ByteArray) {
        parse(body.decodeToString())?.let(::read)
    }

    private fun consumeLine() {
        if (length < 6 || line[0] != 'd'.code.toByte()) return
        val text = line.decodeToString(0, length)
        if (!text.startsWith("data:") || "usage" !in text) return
        parse(text.removePrefix("data:").trim())?.let(::read)
    }

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun read(json: JsonObject) {
        when (protocol) {
            WireProtocol.MESSAGES -> {
                // message_start carries message.usage, message_delta carries usage; a non-streamed message carries usage.
                ((json["message"] as? JsonObject)?.get("usage") as? JsonObject)?.let(::anthropic)
                (json["usage"] as? JsonObject)?.let(::anthropic)
            }
            WireProtocol.RESPONSES -> {
                val usage = ((json["response"] as? JsonObject)?.get("usage") ?: json["usage"]) as? JsonObject ?: return
                val cached = ((usage["input_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.longOrNull ?: 0
                val total = usage.long("input_tokens") ?: return
                seen = true
                input = (total - cached).coerceAtLeast(0)
                cacheRead = cached.coerceIn(0, total)
                output = usage.long("output_tokens") ?: output
            }
            WireProtocol.CHAT -> {
                val usage = json["usage"] as? JsonObject ?: return
                val cached = ((usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.longOrNull ?: 0
                val total = usage.long("prompt_tokens") ?: return
                seen = true
                input = (total - cached).coerceAtLeast(0)
                cacheRead = cached.coerceIn(0, total)
                output = usage.long("completion_tokens") ?: output
            }
        }
    }

    private fun anthropic(usage: JsonObject) {
        // Counts only grow along a stream: keep the largest seen.
        usage.long("input_tokens")?.let { seen = true; input = maxOf(input, it) }
        usage.long("output_tokens")?.let { seen = true; output = maxOf(output, it) }
        usage.long("cache_read_input_tokens")?.let { cacheRead = maxOf(cacheRead, it) }
        usage.long("cache_creation_input_tokens")?.let { cacheWrite = maxOf(cacheWrite, it) }
    }

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..MAX_TOKENS }

    private companion object {
        const val NEWLINE: Byte = 10
        const val INITIAL = 1024
        const val MAX_LINE = 256 * 1024
        const val MAX_TOKENS = 100_000_000L
    }
}
