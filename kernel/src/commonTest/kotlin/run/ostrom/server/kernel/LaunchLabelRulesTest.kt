// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.launches.InvalidLabelException
import run.ostrom.server.kernel.launches.LaunchLabelRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** TeamCode's label rules (docs/agent-sessions.md, "Labels and the run identifier"). */
class LaunchLabelRulesTest {
    @Test
    fun sixteenWellFormedPairsPassAndASeventeenthIsRefused() {
        val seventeen = (1..17).associate { "k$it" to "v$it" }
        assertFailsWith<InvalidLabelException> { LaunchLabelRules.check(seventeen) }
        val sixteen = (1..16).associate { "a.b_c/d-$it" to "x=y z".repeat(10) }
        assertEquals(sixteen, LaunchLabelRules.check(sixteen))
        assertEquals(emptyMap(), LaunchLabelRules.check(emptyMap()))
    }

    @Test
    fun aKeyIsLowercaseAndAtMostSixtyThreeCharacters() {
        listOf("", "Task", "-a", "a b", "a".repeat(64), "é").forEach { key ->
            val refused = assertFailsWith<InvalidLabelException>(key) { LaunchLabelRules.check(mapOf(key to "1")) }
            assertEquals("invalid_label", refused.type)
            assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
        }
        LaunchLabelRules.check(mapOf("a".repeat(63) to "1", "0" to "1"))
    }

    @Test
    fun aValueIsAtMostTwoHundredFiftySixCharactersWithoutControlCharacters() {
        LaunchLabelRules.check(mapOf("k" to "v".repeat(256)))
        assertFailsWith<InvalidLabelException> { LaunchLabelRules.check(mapOf("k" to "v".repeat(257))) }
        assertFailsWith<InvalidLabelException> { LaunchLabelRules.check(mapOf("k" to "a\nb")) }
    }

    @Test
    fun aFilterSplitsAtTheFirstEqualsAndTheLastValueOfARepeatedKeyWins() {
        assertEquals("task" to "a=b", LaunchLabelRules.parse("task=a=b"))
        assertEquals(mapOf("task" to "2", "team" to "x"), LaunchLabelRules.filter(listOf("task=1", "team=x", "task=2")))
        assertEquals(emptyMap(), LaunchLabelRules.filter(emptyList()))
        assertFailsWith<InvalidLabelException> { LaunchLabelRules.parse("task") }
        assertFailsWith<InvalidLabelException> { LaunchLabelRules.parse("Task=1") }
        assertFailsWith<InvalidLabelException> { LaunchLabelRules.filter((1..17).map { "k$it=1" }) }
    }
}
