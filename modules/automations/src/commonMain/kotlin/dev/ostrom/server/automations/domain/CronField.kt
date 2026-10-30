// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

/** Reads one field of a cron: `*`, a value, a range, each with an optional step, separated by commas. */
internal object CronField {
    private val ITEM = Regex("(\\*|[A-Za-z0-9]+(?:-[A-Za-z0-9]+)?)(?:/([0-9]+))?")

    /**
     * The values of [field].
     *
     * @param range The values the field may take.
     * @param names The names of the values from the start of [range] (`JAN` is 1, `SUN` is 0), or none.
     *
     * @throws InvalidAutomationException When an item is malformed or out of [range].
     */
    fun read(field: String, range: IntRange, names: List<String>): Set<Int> =
        field.split(',').flatMap { item(it, range, names) }.toSet()

    private fun item(text: String, range: IntRange, names: List<String>): IntProgression {
        val match = ITEM.matchEntire(text) ?: throw InvalidAutomationException("Invalid cron field item: $text.")
        val (span, rawStep) = match.destructured
        val step = if (rawStep.isEmpty()) 1 else rawStep.toInt()
        requireAutomation(step in 1..range.last) { "Invalid cron step: $text." }
        val bounds = when {
            span == "*" -> range
            '-' in span -> span.split('-').let { (low, high) -> value(low, range, names)..value(high, range, names) }
            rawStep.isEmpty() -> value(span, range, names).let { it..it }
            else -> value(span, range, names)..range.last
        }
        requireAutomation(!bounds.isEmpty()) { "Invalid cron range: $text." }
        return bounds step step
    }

    private fun value(text: String, range: IntRange, names: List<String>): Int {
        val named = names.indexOf(text.uppercase()).takeIf { it >= 0 }?.let { it + range.first }
        val number = named ?: text.toIntOrNull()
        return number?.takeIf { it in range } ?: throw InvalidAutomationException("Invalid cron value: $text.")
    }
}
