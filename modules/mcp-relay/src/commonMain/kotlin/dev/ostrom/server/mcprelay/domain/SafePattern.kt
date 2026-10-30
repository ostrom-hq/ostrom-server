// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

/**
 * Whether a `matches` pattern is safe to run on an agent's arguments. The regular expressions of Kotlin/Native
 * backtrack, and an admin's pattern runs on text the agent chooses, on every call: a repeated group that itself
 * repeats (`(a+)+`) or a back-reference can take exponential time. Those are refused when the policy is saved; what
 * remains is at most polynomial, and the text is bounded by [ArgumentCondition.MAX_MATCHED_LENGTH].
 */
object SafePattern {
    private val quantifiers = setOf('*', '+', '{')

    /**
     * Checks a pattern.
     *
     * @param pattern The regular expression.
     *
     * @return False when it repeats a group that repeats, or refers back to a group.
     */
    fun isSafe(pattern: String): Boolean {
        // For each open group, whether something before it, in its parent, repeats.
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
                char in quantifiers -> repeats = true
            }
            index++
        }
        return safe
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
