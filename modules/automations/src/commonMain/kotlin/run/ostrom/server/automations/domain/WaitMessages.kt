// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The message an answer wakes its session with, the shape the agent's instructions describe (TeamCode's
 * `[TeamCode wait answered]`, `docs/waits.md` « Waking the session »):
 *
 * ```
 * [Ostrom wait answered] <title>
 * wait: <wait id>
 * action: <action label> (<action id>)
 * answered by: <username>
 * <field label>: <value>
 * ```
 *
 * One line per filled field, a list joined by `, `, a multi-line value indented by two spaces on its next lines; cut
 * at 32 000 characters, the most a message holds.
 */
internal object WaitMessages {
    /** The first words of every such message. */
    const val PREFIX = "[Ostrom wait answered]"

    const val MAX_LENGTH = 32_000
    private const val CUT = "\n…"

    /**
     * The message of an answered wait.
     *
     * @param wait The wait, resolved.
     * @param author The username of who answered, when known.
     *
     * @return The message.
     */
    fun answered(wait: Wait, author: String?): String {
        val lines = buildList {
            add("$PREFIX ${wait.question.title}")
            add("wait: ${wait.id}")
            wait.answer?.let { answer ->
                val action = wait.question.actions.firstOrNull { it.id == answer.action }
                val label = when (action) {
                    null -> answer.action
                    else -> action.label
                }
                add("action: $label (${answer.action})")
                add("answered by: ${author ?: answer.author}")
                wait.question.fields.forEach { field ->
                    text(answer.values[field.id])?.let { add("${field.label}: ${indented(it)}") }
                }
            }
        }
        val text = lines.joinToString("\n")
        if (text.length <= MAX_LENGTH) return text
        // Never half of a character written in two (an emoji): the hook prints it as JSON.
        return text.take(MAX_LENGTH - CUT.length).dropLastWhile { it.isHighSurrogate() } + CUT
    }

    /** A value as one line of text; null when it fills nothing. */
    private fun text(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { it.isNotBlank() }
        is JsonArray -> value.mapNotNull(::text).joinToString(", ").takeIf { it.isNotEmpty() }
        else -> value.toString()
    }

    private fun indented(text: String): String = text.lines().joinToString("\n  ")
}
