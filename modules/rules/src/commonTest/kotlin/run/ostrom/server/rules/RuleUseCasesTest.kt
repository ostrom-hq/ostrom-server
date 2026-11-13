// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.rules.domain.models.GateChange
import run.ostrom.server.rules.domain.models.InvalidRuleException
import run.ostrom.server.rules.domain.models.NewRule
import run.ostrom.server.rules.domain.models.RuleCursor
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleNotFoundException
import run.ostrom.server.rules.domain.models.RulePatch
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.models.RuleStrength
import run.ostrom.server.rules.domain.services.RuleDecisions
import run.ostrom.server.rules.domain.services.RuleDecisions.record
import run.ostrom.server.rules.domain.usecases.BulkUpdateRulesUseCaseImpl
import run.ostrom.server.rules.domain.usecases.CreateRuleUseCaseImpl
import run.ostrom.server.rules.domain.usecases.DeleteRuleUseCaseImpl
import run.ostrom.server.rules.domain.usecases.GetRuleUseCaseImpl
import run.ostrom.server.rules.domain.usecases.ListRulesUseCase
import run.ostrom.server.rules.domain.usecases.ListRulesUseCaseImpl
import run.ostrom.server.rules.domain.usecases.UpdateRuleUseCaseImpl
import run.ostrom.server.rules.fakes.RulesBench
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.fakes.RulesFixtures.lyes
import run.ostrom.server.rules.fakes.RulesFixtures.orgA
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class RuleUseCasesTest {
    private val bench = RulesBench()
    private val create = CreateRuleUseCaseImpl(
        bench.repository,
        bench.signer,
        bench.signDecisions,
        bench.clock,
        bench.audit,
    )
    private val update = UpdateRuleUseCaseImpl(
        bench.repository,
        bench.signer,
        bench.signDecisions,
        bench.clock,
        bench.audit,
    )
    private val bulk = BulkUpdateRulesUseCaseImpl(
        bench.repository,
        bench.signer,
        bench.signDecisions,
        bench.clock,
        bench.audit,
    )
    private val delete = DeleteRuleUseCaseImpl(
        bench.repository,
        bench.signer,
        bench.signDecisions,
        bench.clock,
        bench.audit,
    )
    private val gate = RuleGate(
        listOf(RuleGateAction("push-main", "Push to main", emptyList(), emptyList(), null)),
        listOf("lyes"),
        null,
    )

    private fun stored(status: RuleStatus = RuleStatus.PROPOSED, id: String = "rule_1") =
        RulesFixtures.rule(id = id, status = status).also { bench.repository.rules[orgA to id] = it }

    @Test
    fun aWrittenRuleIsDecidedByItsAuthorSignedAndReviewedInNinetyDays() = runTest {
        val rule = create(lyes, NewRule(" No push on Friday ", "Releases", why = " Weekend "))
        assertTrue(rule.id.startsWith("rule_"))
        assertEquals("No push on Friday", rule.statement)
        assertEquals("rule", rule.output)
        assertEquals("written", rule.origin)
        assertEquals("lyes", rule.foundBy)
        assertEquals(1.0, rule.confidence)
        assertEquals(RuleStatus.ACCEPTED, rule.status)
        assertEquals("lyes", rule.decidedBy)
        assertEquals(RulesFixtures.epoch, rule.decidedAt)
        assertEquals("2027-01-28", rule.reviewBy)
        assertEquals("Weekend", rule.why)
        assertEquals(rule, bench.repository.rules[orgA to rule.id])

        val decision = bench.repository.decisions.single()
        assertEquals("accepted", decision.action)
        assertEquals("rule", decision.subject.kind)
        assertEquals(rule.id, decision.subject.id)
        assertEquals("No push on Friday", decision.subject.label)
        assertNull(decision.before)
        assertEquals("accepted", decision.after?.getValue("status")?.jsonPrimitive?.content)
        assertTrue(decision.signed, "Signed once committed")
        assertEquals("rcpt_${decision.id}", decision.receiptId)
        assertEquals(listOf(decision.id), bench.signer.signed.map { it.id })
    }

    @Test
    fun aRuleWithAGateOutputsAGateAndKeepsAGivenReviewDate() = runTest {
        val rule = create(lyes, NewRule("Push to main waits", "Shipping", gate = gate, reviewBy = "2027-03-01"))
        assertEquals("gate", rule.output)
        assertEquals(gate, rule.gate)
        assertEquals("2027-03-01", rule.reviewBy)
        assertEquals("check", create(lyes, NewRule("Lint", "CI", output = "check", gate = gate)).output)
    }

    @Test
    fun aProposedRuleHasNoDecisionStamp() = runTest {
        val rule = create(lyes, NewRule("Maybe", "Ideas", status = RuleStatus.PROPOSED, reviewBy = "2027-03-01"))
        assertNull(rule.decidedBy)
        assertNull(rule.decidedAt)
        assertNull(rule.reviewBy)
        assertEquals("proposed", bench.repository.decisions.single().action)
    }

    @Test
    fun aNewRuleIsAcceptedOrProposedAndChecked() = runTest {
        assertFailsWith<InvalidRuleException> { create(lyes, NewRule("x", "g", status = RuleStatus.EDITED)) }
        assertFailsWith<InvalidRuleException> { create(lyes, NewRule("x", "g", status = RuleStatus.REJECTED)) }
        assertFailsWith<InvalidRuleException> { create(lyes, NewRule("x", "g", output = "Bad Output")) }
        assertFailsWith<InvalidRuleException> {
            create(lyes, NewRule("x", "g", gate = gate.copy(actions = emptyList())))
        }
        assertTrue(bench.repository.rules.isEmpty())
        assertEquals(0, bench.repository.transactions, "Nothing is written before the checks pass")
    }

    @Test
    fun aReceiptThatFailsToSignLeavesTheDecisionToSignLater() = runTest {
        bench.signer.failing = true
        val rule = create(lyes, NewRule("Still written", "g"))
        assertEquals(rule, bench.repository.rules[orgA to rule.id])
        assertEquals(false, bench.repository.decisions.single().signed)
        assertEquals(listOf("decision receipt signing failed"), bench.logger.events.map { it.second })
    }

    @Test
    fun acceptingARuleSignsTheDecisionWithBeforeAndAfter() = runTest {
        stored()
        val patch = RulePatch(status = RuleStatus.ACCEPTED, strength = RuleStrength.RECOMMENDED)
        val accepted = update(lyes, "rule_1", patch)
        assertEquals(RuleStatus.ACCEPTED, accepted.status)
        assertEquals(RuleStrength.RECOMMENDED, accepted.strength)
        assertEquals("lyes", accepted.decidedBy)
        assertEquals("2027-01-28", accepted.reviewBy)
        val decision = bench.repository.decisions.single()
        assertEquals("accepted", decision.action)
        assertEquals("proposed", decision.before?.getValue("status")?.jsonPrimitive?.content)
        assertEquals("recommended", decision.after?.getValue("strength")?.jsonPrimitive?.content)
    }

    @Test
    fun acceptingARuleWithAReviewDateKeepsTheDateGiven() = runTest {
        stored()
        val accepted = update(lyes, "rule_1", RulePatch(status = RuleStatus.ACCEPTED, reviewBy = "2027-03-01"))
        assertEquals("lyes", accepted.decidedBy)
        assertEquals("2027-03-01", accepted.reviewBy, "Not the ninety days: the decider set the date")
        assertEquals("accepted", bench.repository.decisions.single().action)
    }

    @Test
    fun aNewStatementWithoutAStatusMakesTheRuleEdited() = runTest {
        stored(RuleStatus.ACCEPTED)
        val edited = update(lyes, "rule_1", RulePatch(statement = " Two reviewers on main "))
        assertEquals(RuleStatus.EDITED, edited.status)
        assertEquals("Two reviewers on main", edited.statement)
        assertEquals("edited", bench.repository.decisions.single().action)

        bench.clock.advance(1.hours)
        val again =
            update(lyes, "rule_1", RulePatch(group = "Reviews", scope = listOf("ostrom-hq/web"), output = "check"))
        assertEquals(RuleStatus.EDITED, again.status)
        assertEquals("Reviews", again.group)
        assertEquals(listOf("ostrom-hq/web"), again.scope)
        assertEquals("check", again.output)
        assertEquals(listOf("edited", "edited"), bench.repository.decisions.map { it.action }, "Edited again")
    }

    @Test
    fun theSameStatementKeepsTheStatus() = runTest {
        val rule = stored(RuleStatus.ACCEPTED)
        assertEquals(RuleStatus.ACCEPTED, update(lyes, "rule_1", RulePatch(statement = rule.statement)).status)
        assertTrue(bench.repository.decisions.isEmpty(), "Nothing a decision covers changed")
    }

    @Test
    fun aReasonIsSetOrRemovedAndAGateKeptRemovedOrReplaced() = runTest {
        stored(RuleStatus.ACCEPTED)
        val reasoned = update(lyes, "rule_1", RulePatch(why = " Audit ", gate = GateChange.Replace(gate)))
        assertEquals("Audit", reasoned.why)
        assertEquals(gate, reasoned.gate)
        val kept = update(lyes, "rule_1", RulePatch(strength = RuleStrength.RECOMMENDED))
        assertEquals("Audit", kept.why)
        assertEquals(gate, kept.gate)
        val removed = update(lyes, "rule_1", RulePatch(why = "  ", gate = GateChange.Remove))
        assertNull(removed.why)
        assertNull(removed.gate)
        assertEquals(3, bench.repository.decisions.size)
        assertFailsWith<InvalidRuleException> {
            update(lyes, "rule_1", RulePatch(gate = GateChange.Replace(gate.copy(approvers = listOf("not a login")))))
        }
    }

    @Test
    fun aReviewDateAloneIsNoDecision() = runTest {
        stored(RuleStatus.ACCEPTED)
        bench.clock.advance(2.hours)
        val rule = update(lyes, "rule_1", RulePatch(reviewBy = "2027-06-01"))
        assertEquals("2027-06-01", rule.reviewBy)
        assertEquals(RulesFixtures.epoch + 2.hours, rule.updatedAt)
        assertNull(rule.decidedBy, "Not stamped: nobody decided")
        assertTrue(bench.repository.decisions.isEmpty())
        assertEquals(rule, bench.repository.rules[orgA to "rule_1"])
    }

    @Test
    fun rejectingThenReopeningClearsTheDecision() = runTest {
        stored(RuleStatus.ACCEPTED)
        val rejected = update(lyes, "rule_1", RulePatch(status = RuleStatus.REJECTED))
        assertEquals("lyes", rejected.decidedBy)
        assertNull(rejected.reviewBy)
        val reopened = update(lyes, "rule_1", RulePatch(status = RuleStatus.PROPOSED))
        assertNull(reopened.decidedBy)
        assertNull(reopened.decidedAt)
        assertEquals(listOf("rejected", "proposed"), bench.repository.decisions.map { it.action })
    }

    @Test
    fun anUnknownRuleIsNotFoundAndAnotherOrganizationsToo() = runTest {
        stored()
        assertFailsWith<RuleNotFoundException> { update(lyes, "rule_2", RulePatch(status = RuleStatus.ACCEPTED)) }
        assertFailsWith<RuleNotFoundException> {
            update(RulesFixtures.bob, "rule_1", RulePatch(status = RuleStatus.ACCEPTED))
        }
        assertFailsWith<RuleNotFoundException> { delete(RulesFixtures.bob, "rule_1") }
        assertFailsWith<RuleNotFoundException> { GetRuleUseCaseImpl(bench.repository)(RulesFixtures.orgB, "rule_1") }
        assertEquals(RuleStatus.PROPOSED, bench.repository.rules.getValue(orgA to "rule_1").status)
        assertTrue(bench.repository.decisions.isEmpty())
    }

    @Test
    fun aBulkDecisionCountsTheRulesFoundOnceEach() = runTest {
        stored(id = "rule_1")
        stored(id = "rule_2")
        stored(RuleStatus.ACCEPTED, id = "rule_3")
        assertEquals(3, bulk(lyes, listOf("rule_1", "rule_2", "rule_1", "rule_3", "rule_x"), RuleStatus.ACCEPTED))
        assertEquals(2, bench.repository.decisions.size, "rule_3 already was accepted")
        assertTrue(bench.repository.decisions.all { it.signed })
        assertEquals(0, bulk(RulesFixtures.bob, listOf("rule_1"), RuleStatus.REJECTED), "Not another organization's")
    }

    @Test
    fun aBulkDecisionIsBoundedAndNeverAnEdit() = runTest {
        assertFailsWith<InvalidRuleException> { bulk(lyes, List(201) { "rule_$it" }, RuleStatus.ACCEPTED) }
        assertFailsWith<InvalidRuleException> { bulk(lyes, listOf("rule_1"), RuleStatus.EDITED) }
    }

    @Test
    fun deletingARuleRecordsWhatItWas() = runTest {
        stored(RuleStatus.ACCEPTED)
        delete(lyes, "rule_1")
        assertNull(bench.repository.rules[orgA to "rule_1"])
        val decision = bench.repository.decisions.single()
        assertEquals(RuleDecisions.DELETED, decision.action)
        assertEquals("accepted", decision.before?.getValue("status")?.jsonPrimitive?.content)
        assertNull(decision.after)
        assertTrue(decision.ruleDigest.orEmpty().startsWith("sha256:"))
    }

    @Test
    fun listingAndReadingStayInTheOrganization() = runTest {
        stored(id = "rule_1")
        bench.repository.rules[RulesFixtures.orgB to "rule_9"] = RulesFixtures.rule("rule_9", RulesFixtures.orgB)
        val listed = ListRulesUseCaseImpl(bench.repository)(orgA, RuleQuery(), ListRulesUseCase.MAX_RULES)
        assertEquals(listOf("rule_1"), listed.items.map { it.id })
        assertNull(listed.next)
        assertEquals(listOf(ListRulesUseCase.MAX_RULES + 1), bench.repository.limits, "One more tells a next page")
        assertEquals("rule_9", GetRuleUseCaseImpl(bench.repository)(RulesFixtures.orgB, "rule_9").id)
    }

    @Test
    fun aListComesAPageAtATime() = runTest {
        val groups = listOf("rule_1" to "B", "rule_2" to "A", "rule_3" to "B", "rule_4" to "A", "rule_5" to "C")
        groups.forEach { (id, group) -> bench.repository.rules[orgA to id] = RulesFixtures.rule(id, group = group) }
        val list = ListRulesUseCaseImpl(bench.repository)
        val first = list(orgA, RuleQuery(), 2)
        assertEquals(listOf("rule_2", "rule_4"), first.items.map { it.id }, "By group, then id")
        assertEquals(RuleCursor("A", "rule_4"), first.next)
        val second = list(orgA, RuleQuery(after = first.next), 2)
        assertEquals(listOf("rule_1", "rule_3"), second.items.map { it.id })
        val last = list(orgA, RuleQuery(after = second.next), 2)
        assertEquals(listOf("rule_5"), last.items.map { it.id })
        assertNull(last.next, "The last page")
        assertEquals(emptyList(), list(orgA, RuleQuery(after = RuleCursor("C", "rule_5")), 2).items, "After the last")
        // A rule the organization does not hold: forged, or another organization's.
        assertFailsWith<InvalidPageException> { list(orgA, RuleQuery(after = RuleCursor("Z", "rule_9")), 2) }
        val ofA = RuleQuery(after = RuleCursor("A", "rule_4"))
        assertFailsWith<InvalidPageException> { list(RulesFixtures.orgB, ofA, 2) }
        assertFailsWith<InvalidPageException> { list(orgA, RuleQuery(), 0) }
        list(orgA, RuleQuery(), 5_000)
        assertEquals(ListRulesUseCase.MAX_RULES + 1, bench.repository.limits.last(), "At most 1 000")
    }

    @Test
    fun noRuleMeansNoDecision() = runTest {
        val decision = bench.repository.transaction(orgA) {
            record(bench.signer, lyes, RulesFixtures.epoch, before = null, after = null)
        }
        assertNull(decision)
    }

    @Test
    fun everyDecisionWritesItsLineToTheAuditLogInTheDecidersNameAndNothingElseDoes() = runTest {
        val rule = create(lyes, NewRule("No push on Friday", "Releases"))
        update(lyes, rule.id, RulePatch(status = RuleStatus.REJECTED))
        update(lyes, rule.id, RulePatch(status = RuleStatus.PROPOSED))
        update(lyes, rule.id, RulePatch(statement = "No push on Friday evening"))
        update(lyes, rule.id, RulePatch(reviewBy = "2027-03-01"))
        assertEquals(1, bulk(lyes, listOf(rule.id, "rule_unknown"), RuleStatus.ACCEPTED))
        delete(lyes, rule.id)

        val lines = bench.audit.records
        val events = listOf("created", "rejected", "proposed", "edited", "accepted", "deleted").map { "rule.$it" }
        assertEquals(events, lines.map { it.event }, "A review date alone is no decision, an unknown id no line")
        assertTrue(lines.all { it.orgId == orgA && it.actor == lyes.actor && it.subjectType == "rule" })
        assertTrue(lines.all { it.subjectId == rule.id })
        assertEquals(bench.repository.decisions.map { it.id }, lines.map { it.details.getValue("decision") })
        assertEquals("accepted", lines.first().details["status"], "A new rule says its status")
        assertNull(lines.last().details["status"])
        assertTrue(lines.none { it.details.values.any { value -> "Friday" in value } }, "Never the statement")
    }
}
