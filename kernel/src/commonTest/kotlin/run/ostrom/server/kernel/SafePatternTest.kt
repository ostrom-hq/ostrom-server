// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.text.SafePattern
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pattern an admin wrote, run on text an agent chooses: no catastrophic backtracking. */
class SafePatternTest {
    @Test
    fun patternsWithoutNestedRepetitionAreAccepted() {
        val safe = listOf(
            "^prod",
            "drop\\s+table",
            "(prod|staging)-db",
            "[a-z]+\\.[a-z]{2,}",
            "(a+)?",
            "(abc)+",
            "\\(a+\\)+",
            "[(a+)]+",
            "a{2,3}b*",
            "a*(b)c",
            "[^]a]+",
            "[\\]]+",
            "[abc",
            "a[",
            "a)b",
            "a\\",
            "\\d+",
            "^https://[a-z]+\\.example\\.com/.*$",
            "a{2,5}b{1,3}c{4}",
        )
        for (pattern in safe) assertTrue(SafePattern.isSafe(pattern), pattern)
    }

    @Test
    fun nestedRepetitionAndBackreferencesAreRefused() {
        val unsafe = listOf(
            "(a+)+$",
            "(a*)*",
            "((ab)*c)+",
            "(x+x+)+y",
            "(a{1,5}){2,}",
            "(?:a|b+)*",
            "(a)\\1",
            "(?<x>a)\\k<x>",
            // An alternation inside a repeated group: its branches overlap and the engine tries every split.
            "(a|aa)+$",
            "(?:x|y)*z",
            // More than two unbounded repeats: polynomial, but of degree three over 8 KiB of text.
            ".*.*.*x",
            "a+b*c{2,}",
        )
        for (pattern in unsafe) assertFalse(SafePattern.isSafe(pattern), pattern)
    }
}
