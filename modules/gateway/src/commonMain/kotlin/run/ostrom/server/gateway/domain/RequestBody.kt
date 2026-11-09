// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.domain

import kotlinx.serialization.json.JsonPrimitive

/**
 * An agent's request body, scanned once ([TopLevelJson]): the model it asks for (the org's default when it names
 * none), whether it streams, the most output tokens it allows, and where `metadata`, `store` and `stream_options` sit.
 * Every rewrite is applied to the original text in one pass ([render]).
 *
 * @property text The body as the agent sent it.
 * @property fields Its scan.
 * @property model The model.
 * @property stream Whether the agent asked for a stream.
 */
class RequestBody private constructor(
    val text: String,
    val fields: TopLevelJson.Fields,
    val model: String,
    val stream: Boolean,
    private val inserted: List<String>,
) {
    /**
     * The most output tokens the body allows: the largest of `max_tokens` (Messages, Chat Completions),
     * `max_output_tokens` (Responses) and `max_completion_tokens` (Chat Completions) written as a whole number, or null
     * when it names none. A field named twice makes the body invalid ([TopLevelJson.scan]), so the provider reads the
     * value read here.
     */
    val maxOutputTokens: Long?
        get() = OUTPUT_LIMITS.mapNotNull { name ->
            fields.ranges[name]?.let { text.substring(it).toLongOrNull()?.takeIf { tokens -> tokens >= 0 } }
        }.maxOrNull()

    /**
     * The body to send.
     *
     * @param replacements Raw JSON values by range of [fields].
     * @param members Raw members (`"key":value`) to add.
     */
    fun render(replacements: Map<IntRange, String> = emptyMap(), members: List<String> = emptyList()): String =
        TopLevelJson.rewrite(text, fields, replacements, inserted + members)

    override fun toString(): String = "RequestBody($model, ${text.length} chars)"

    /** Reading a body. */
    companion object {
        /** The top-level fields the gateway reads. */
        const val MODEL = "model"

        /** Whether the answer streams. */
        const val STREAM = "stream"

        /** Claude Code's account and session. */
        const val METADATA = "metadata"

        /** Whether OpenAI keeps the response. */
        const val STORE = "store"

        /** Chat Completions streams carry usage only when asked here. */
        const val STREAM_OPTIONS = "stream_options"

        /** The longest model name accepted. */
        const val MAX_MODEL = 200

        /** The fields that bound the output tokens, by API. */
        private val OUTPUT_LIMITS: List<String> = listOf("max_tokens", "max_output_tokens", "max_completion_tokens")

        private val FIELDS = setOf(MODEL, STREAM, METADATA, STORE, STREAM_OPTIONS) + OUTPUT_LIMITS

        /**
         * Reads a body.
         *
         * @param text The body.
         * @param default The org's default model, used when the body names none.
         *
         * @return The body, or null when it is not one JSON object naming a valid model.
         */
        fun read(text: String, default: String?): RequestBody? = TopLevelJson.scan(text, FIELDS)?.let { fields ->
            val named = fields.ranges[MODEL]
            val model = if (named == null) default else TopLevelJson.string(text, named)
            model?.takeIf(::valid)?.let {
                val inserted = if (named == null) listOf("\"$MODEL\":" + JsonPrimitive(it)) else emptyList()
                RequestBody(text, fields, it, TopLevelJson.boolean(text, fields.ranges[STREAM]) == true, inserted)
            }
        }

        private fun valid(model: String): Boolean =
            model.length in 1..MAX_MODEL && model.none { it.isWhitespace() || it.isISOControl() }
    }
}
