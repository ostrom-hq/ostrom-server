// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.server.rules.domain.models.InvalidRuleException
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleGateCheck
import run.ostrom.server.rules.domain.services.RuleChecks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuleChecksTest {
    private val push =
        RuleGateAction("push-main", "Push to main", listOf("git push .* main"), listOf("ostrom-hq/web"), null)

    @Test
    fun textsAreTrimmedAndBounded() {
        assertEquals("Ship on Fridays", RuleChecks.statement("  Ship on Fridays "))
        assertEquals("Reviews", RuleChecks.group(" Reviews"))
        assertFailsWith<InvalidRuleException> { RuleChecks.statement("   ") }
        assertFailsWith<InvalidRuleException> { RuleChecks.statement("x".repeat(2_001)) }
        assertFailsWith<InvalidRuleException> { RuleChecks.group("g".repeat(81)) }
    }

    @Test
    fun aBlankReasonIsNoneAndALongOneIsRefused() {
        assertNull(RuleChecks.why("  "))
        assertEquals("Because", RuleChecks.why(" Because "))
        assertFailsWith<InvalidRuleException> { RuleChecks.why("w".repeat(2_001)) }
    }

    @Test
    fun anOutputIsALowerCaseWord() {
        assertEquals("gate", RuleChecks.output("gate"))
        assertFailsWith<InvalidRuleException> { RuleChecks.output("Gate") }
        assertFailsWith<InvalidRuleException> { RuleChecks.output("") }
    }

    @Test
    fun aScopeIsTrimmedEachEntryOnceAndBounded() {
        assertEquals(listOf("ostrom-hq/web"), RuleChecks.scope(listOf(" ostrom-hq/web", "ostrom-hq/web ")))
        assertFailsWith<InvalidRuleException> { RuleChecks.scope(List(51) { "repo-$it" }) }
        assertFailsWith<InvalidRuleException> { RuleChecks.scope(listOf(" ")) }
    }

    @Test
    fun aReviewDateIsWrittenYearMonthDay() {
        assertEquals("2027-01-28", RuleChecks.reviewBy("2027-01-28"))
        assertFailsWith<InvalidRuleException> { RuleChecks.reviewBy("28/01/2027") }
        assertFailsWith<InvalidRuleException> { RuleChecks.reviewBy("2027-13-01") }
        assertFailsWith<InvalidRuleException> { RuleChecks.reviewBy("2026-02-31") }
        assertEquals("2028-02-29", RuleChecks.reviewBy("2028-02-29"))
    }

    @Test
    fun aGateIsCleanedApproversOnceCaseIgnored() {
        val checked = RuleChecks.gate(
            RuleGate(
                actions = listOf(
                    push.copy(label = " ", patterns = listOf(" git push .* main ", " "), repos = listOf("a", "a")),
                    push.copy(cls = "custom", tools = listOf("Bash", "Bash", "mcp")),
                ),
                approvers = listOf(" lyes", "Lyes", "rev-b"),
                reason = "  ",
            ),
        )
        assertEquals(listOf("lyes", "rev-b"), checked.approvers)
        assertNull(checked.reason)
        assertEquals("push-main", checked.actions[0].label, "An empty label is the class")
        assertEquals(listOf("git push .* main"), checked.actions[0].patterns)
        assertEquals(listOf("a"), checked.actions[0].repos)
        assertNull(checked.actions[0].tools)
        assertEquals(listOf("Bash", "mcp"), checked.actions[1].tools)
        assertEquals("Owner", RuleChecks.gate(RuleGate(listOf(push), emptyList(), " Owner ")).reason)
    }

    @Test
    fun aGateNeedsEachCheckOnceAttestedWhenAnyAskSaysSo() {
        val checked = RuleChecks.gate(
            RuleGate(
                actions = listOf(push),
                approvers = emptyList(),
                reason = null,
                checks = listOf(
                    RuleGateCheck(" tests ", attested = false),
                    RuleGateCheck("lint", attested = false),
                    RuleGateCheck("tests", attested = true),
                ),
            ),
        )
        assertEquals(listOf(RuleGateCheck("tests", true), RuleGateCheck("lint", false)), checked.checks)
        assertEquals(emptyList<RuleGateCheck>(), RuleChecks.gate(RuleGate(listOf(push), emptyList(), null)).checks)
    }

    @Test
    fun aGateOutOfBoundsIsRefused() {
        val refused = listOf(
            RuleGate(emptyList(), emptyList(), null),
            RuleGate(List(21) { push }, emptyList(), null),
            RuleGate(listOf(push), List(21) { "p$it" }, null),
            RuleGate(listOf(push), listOf("not a login"), null),
            RuleGate(listOf(push), emptyList(), "r".repeat(501)),
            RuleGate(listOf(push.copy(cls = "Push Main")), emptyList(), null),
            RuleGate(listOf(push.copy(label = "l".repeat(201))), emptyList(), null),
            RuleGate(listOf(push.copy(patterns = List(21) { "p" })), emptyList(), null),
            RuleGate(listOf(push.copy(patterns = listOf("(unclosed"))), emptyList(), null),
            RuleGate(listOf(push.copy(patterns = listOf("p".repeat(501)))), emptyList(), null),
            // AUDIT-SECU-ANALYSE R5: a pattern that could backtrack for minutes on the server's replay or in a hook.
            RuleGate(listOf(push.copy(patterns = listOf("(a+)+$"))), emptyList(), null),
            RuleGate(listOf(push.copy(patterns = listOf(".*.*.*x"))), emptyList(), null),
            RuleGate(listOf(push.copy(repos = List(51) { "r$it" })), emptyList(), null),
            RuleGate(listOf(push.copy(tools = List(11) { "T$it" })), emptyList(), null),
            RuleGate(listOf(push.copy(tools = listOf("not a tool"))), emptyList(), null),
            RuleGate(listOf(push), emptyList(), null, List(21) { RuleGateCheck("c$it", attested = true) }),
            RuleGate(listOf(push), emptyList(), null, listOf(RuleGateCheck("not a check", attested = true))),
            RuleGate(listOf(push), emptyList(), null, listOf(RuleGateCheck(" ", attested = false))),
        )
        refused.forEach { gate -> assertFailsWith<InvalidRuleException>("$gate") { RuleChecks.gate(gate) } }
    }
}
