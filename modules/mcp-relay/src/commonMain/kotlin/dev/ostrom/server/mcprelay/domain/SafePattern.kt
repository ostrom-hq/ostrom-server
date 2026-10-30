// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

/**
 * Whether a `matches` pattern is safe to run on an agent's arguments. The regular expressions of Kotlin/Native
 * backtrack, and an admin's pattern runs on text the agent chooses, on every call: a repeated group that itself
 * repeats (`(a+)+`), a repeated group with overlapping branches (`(a|aa)+`) or a back-reference can take exponential
 * time. Those are refused when the policy is saved, and so is a pattern with more than two unbounded repeats
 * (`.*.*.*`, cubic): what remains is at most quadratic, and the text is bounded by
 * [ArgumentCondition.MAX_MATCHED_LENGTH].
 */
object SafePattern {
    private const val MAX_UNBOUNDED = 2
    private val quantifiers = setOf('*', '+', '{')

    /**
     * Checks a pattern.
     *
     * @param pattern The regular expression.
     *
     * @return False when it repeats a group that repeats or alternates, refers back to a group, or repeats without
     *   bound more than twice.
     */
    fun isSafe(pattern: String): Boolean {
        // For each open group, whether something before it, in its parent, repeats (an alternation counts: its
        // branches can overlap).
        val groups = ArrayDeque<Boolean>()
        var repeats = false
        var safe = true
        var index = 0
        while (safe && index < pattern.length) {
            val char = pattern[index]
            when {
                char == '\\' -> {
                    safe = !pattern.getOrNull(index + 1).isBackReference()
                    index++
                }
                char == '[' -> index = pattern.endOfClass(index)
                char == '(' -> {
                    groups.addLast(repeats)
                    repeats = false
                }
                char == ')' && groups.isNotEmpty() -> {
                    val repeatedGroup = pattern.getOrNull(index + 1) in quantifiers
                    safe = !(repeatedGroup && repeats)
                    repeats = groups.removeLast() || repeats || repeatedGroup
                }
                char in quantifiers || char == '|' -> repeats = true
            }
            index++
        }
        return safe && pattern.unboundedRepeats() <= MAX_UNBOUNDED
    }

    /** How many `*`, `+` and `{n,}` the pattern has outside classes and escapes. */
    private fun String.unboundedRepeats(): Int {
        var count = 0
        var index = 0
        while (index < length) {
            when (this[index]) {
                '\\' -> index++
                '[' -> index = endOfClass(index)
                '*', '+' -> count++
                '{' -> if (substring(index).substringBefore('}').endsWith(',')) count++
            }
            index++
        }
        return count
    }

    private fun Char?.isBackReference(): Boolean = this == 'k' || (this != null && this in '1'..'9')

    /** The index of the `]` closing the class opened at [start], or the end of the pattern. */
    private fun String.endOfClass(start: Int): Int {
        var index = start + 1
        if (getOrNull(index) == '^') index++
        if (getOrNull(index) == ']') index++
        while (index < length && this[index] != ']') {
            if (this[index] == '\\') index++
            index++
        }
        return index
    }
}
