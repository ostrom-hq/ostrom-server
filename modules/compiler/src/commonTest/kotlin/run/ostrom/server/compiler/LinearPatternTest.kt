// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.server.compiler.domain.services.LinearPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.TimeSource

class LinearPatternTest {
    private fun matches(pattern: String, vararg texts: String): List<Boolean> =
        LinearPattern.compile(pattern).let { compiled -> texts.map { compiled.find(it) } }

    private fun refused(pattern: String): String? =
        assertFailsWith<IllegalArgumentException> { LinearPattern.compile(pattern) }.message

    @Test
    fun aPatternMatchesAPartOfTheTextUnlessAnchoredToTheWholeOfIt() {
        assertEquals(listOf(true, true, false, true), matches("ok", "ok", "is ok?", "o k", "okok"))
        assertEquals(listOf(true, false), matches("^ok", "ok then", "not ok"))
        assertEquals(listOf(true, false), matches("ok$", "it is ok", "ok then"))
        assertEquals(listOf(true, false, false), matches("^ok$", "ok", "okk", "ook"))
        // The empty pattern, an empty alternative and an empty group match the empty text.
        assertEquals(listOf(true, true), matches("", "", "anything"))
        assertEquals(listOf(true, true), matches("^(a|)$", "a", ""))
        assertEquals(listOf(true), matches("^()$", ""))
    }

    @Test
    fun repetitionsAlternationsAndGroupsMatchAsTheirSyntaxSays() {
        assertEquals(listOf(true, true, true, false), matches("^ab*c$", "ac", "abc", "abbbc", "abd"))
        assertEquals(listOf(false, true, true), matches("^ab+c$", "ac", "abc", "abbbc"))
        assertEquals(listOf(true, true, false), matches("^ab?c$", "ac", "abc", "abbc"))
        assertEquals(listOf(true, true, false), matches("^(cat|dog)s?$", "cats", "dog", "cow"))
        assertEquals(listOf(true, true, false), matches("^(ab)+$", "ab", "abab", "aba"))
        assertEquals(listOf(true, false), matches("^a.c$", "abc", "a\nc"))
        // A repeated empty group, a repeated anchor and a repeated repetition end, as they match nothing more.
        assertEquals(listOf(true, true), matches("^(a*)*$", "", "aaa"))
        assertEquals(listOf(true), matches("^*a", "a"))
        assertEquals(listOf(true, true), matches("^a*?$", "", "aa"))
    }

    @Test
    fun classesAndEscapesReadTheirCharacters() {
        assertEquals(listOf(true, true, false), matches("^[abc]+$", "cab", "a", "abd"))
        assertEquals(listOf(true, false, false), matches("^[a-c]$", "b", "d", "`"))
        assertEquals(listOf(false, true), matches("^[^0-9]$", "7", "x"))
        // A `-` first or last is itself, as a range cut by the end of the class.
        assertEquals(listOf(true, true, true), matches("^[a-]+$", "a", "-", "a-"))
        assertEquals(listOf(true, true), matches("^[\\]\\d]+$", "]", "4"))
        assertEquals(listOf(true, false, false), matches("^\\d$", "5", "/", ":"))
        assertEquals(
            listOf(true, true, true, true, false, false, false, false),
            matches("^\\w$", "a", "A", "0", "_", "{", " ", "[", ":"),
        )
        assertEquals(listOf(true, true, true, false, false), matches("^\\s$", " ", "\t", "\r", "\u0001", "a"))
        assertEquals(listOf(true, false), matches("^a\\.b$", "a.b", "axb"))
        assertEquals(listOf(true), matches("^\\\\\\^\\$\\|\\?\\*\\+\\(\\)\\[\\]\\{\\}\\-\\/$", "\\^$|?*+()[]{}-/"))
    }

    @Test
    fun aPatternOutOfItsSyntaxIsRefusedWithItsReason() {
        assertEquals("the pattern is at most 200 characters.", refused("a".repeat(LinearPattern.MAX_PATTERN + 1)))
        assertEquals("a ) at position 2 closes no group.", refused("a)"))
        assertEquals("a ( is never closed.", refused("(a"))
        assertEquals("(? at position 1: no lookaround nor special group.", refused("(?=a)"))
        assertEquals("'*' at position 1 repeats nothing or is not allowed (no counted repetition).", refused("*a"))
        assertEquals("'{' at position 2 repeats nothing or is not allowed (no counted repetition).", refused("a{2}"))
        assertEquals("the pattern ends with a \\.", refused("a\\"))
        assertEquals("\\1 at position 6 is not allowed (no backreference).", refused("(a)b\\1"))
        assertEquals("a [ is never closed.", refused("[a-"))
        assertEquals("a class [] reads no character.", refused("[]"))
        assertEquals("the range z-a is reversed.", refused("[z-a]"))
        val longest = "a".repeat(LinearPattern.MAX_PATTERN)
        assertEquals(listOf(true), matches(longest, longest))
    }

    @Test
    fun aPatternThatWouldMakeABacktrackingEngineExplodeMatchesInLinearTime() {
        // (a*)*b on a long run of a's without b: exponential for a backtracking engine, linear here.
        val text = "a".repeat(LinearPattern.MAX_TEXT)
        val started = TimeSource.Monotonic.markNow()

        val found = matches("^(a|a)*(a*)*b$", text).single()

        assertEquals(false, found)
        val took = started.elapsedNow()
        println("-- (a|a)*(a*)*b on ${text.length} a's: $took")
        assertEquals(true, took.inWholeSeconds < MAX_SECONDS, "took $took")
    }

    private companion object {
        const val MAX_SECONDS = 5
    }
}
