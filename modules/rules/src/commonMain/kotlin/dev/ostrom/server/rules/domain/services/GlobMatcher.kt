// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

/**
 * A CODEOWNERS pattern read once into steps and matched in a single pass over a path, keeping every step the path
 * can be at (an automaton, not a backtracking search): the time is the path's length times the pattern's, whatever
 * either holds. The same reading as GitHub's: a pattern without an inner `/` matches at any depth, `*` and `?` stay
 * inside a segment, a double star crosses them, a trailing `/` names a directory, and a pattern also covers what is
 * under it.
 */
internal class GlobMatcher private constructor(private val steps: List<Step>, private val chars: CharArray) {
    // What a step of the pattern accepts.
    private enum class Step {
        // One given character.
        LITERAL,

        // One character other than `/` (`?`).
        ONE,

        // Any run of characters other than `/` (`*`).
        SEGMENT,

        // Any run of characters (a double star).
        ANY,

        // Nothing, or any run ending with `/` (a double star then `/`, or the head of a pattern without an inner `/`).
        DIRECTORIES,

        // The end of the path, or `/` and anything under it (the tail of a pattern that does not end with `/`).
        REST,
    }

    /**
     * Whether the pattern matches a whole path.
     *
     * @param path The path, from the repository's root.
     * @return True when it does.
     */
    fun matches(path: String): Boolean {
        var at = closed(BooleanArray(steps.size + 1).also { it[0] = true })
        for (char in path) {
            val next = BooleanArray(steps.size + 1)
            for (step in steps.indices) {
                if (at[step]) advance(step, char, next)
            }
            if (next.none { it }) return false
            at = closed(next)
        }
        return at[steps.size]
    }

    private fun advance(step: Int, char: Char, next: BooleanArray) {
        val slash = char == '/'
        when (steps[step]) {
            Step.LITERAL -> if (chars[step] == char) next[step + 1] = true
            Step.ONE -> if (!slash) next[step + 1] = true
            Step.SEGMENT -> if (!slash) next[step] = true
            Step.ANY -> next[step] = true
            Step.DIRECTORIES -> {
                next[step] = true
                if (slash) next[step + 1] = true
            }
            Step.REST -> if (slash) next[step + 1] = true
        }
    }

    // The steps reached without reading a character: past a star, or to the end from REST.
    private fun closed(at: BooleanArray): BooleanArray {
        for (step in steps.indices) {
            if (!at[step]) continue
            when (steps[step]) {
                Step.SEGMENT, Step.ANY, Step.DIRECTORIES -> at[step + 1] = true
                Step.REST -> at[steps.size] = true
                Step.LITERAL, Step.ONE -> Unit
            }
        }
        return at
    }

    /** Reads patterns. */
    companion object {
        // The character of a step that reads none.
        private const val NONE = ' '

        /**
         * Reads a pattern.
         *
         * @param pattern The pattern (`/docs/`, `*.sql`, a double star for any depth).
         * @return Its matcher.
         */
        fun of(pattern: String): GlobMatcher {
            val steps = mutableListOf<Step>()
            val chars = StringBuilder()
            fun add(step: Step, char: Char) {
                steps += step
                chars.append(char)
            }
            if (!pattern.startsWith("/") && !pattern.trimEnd('/').contains('/')) add(Step.DIRECTORIES, NONE)
            val body = pattern.removePrefix("/").trimEnd('/')
            var i = 0
            while (i < body.length) {
                when {
                    body.startsWith("**/", i) -> add(Step.DIRECTORIES, NONE).also { i += 2 }
                    body.startsWith("**", i) -> add(Step.ANY, NONE).also { i++ }
                    body[i] == '*' -> add(Step.SEGMENT, NONE)
                    body[i] == '?' -> add(Step.ONE, NONE)
                    else -> add(Step.LITERAL, body[i])
                }
                i++
            }
            if (pattern.endsWith("/")) add(Step.LITERAL, '/') else add(Step.REST, NONE)
            add(Step.ANY, NONE)
            return GlobMatcher(steps, chars.toString().toCharArray())
        }
    }
}
