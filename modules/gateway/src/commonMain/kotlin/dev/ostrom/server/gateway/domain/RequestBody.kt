package dev.ostrom.server.gateway.domain

import kotlinx.serialization.json.JsonPrimitive

/**
 * An agent's request body, scanned once ([TopLevelJson]): the model it asks for (the org's default when it names
 * none), whether it streams, and where `metadata` and `store` sit. Every rewrite is applied to the original text in
 * one pass ([render]).
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

        /** The longest model name accepted. */
        const val MAX_MODEL = 200

        private val FIELDS = setOf(MODEL, STREAM, METADATA, STORE)

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
