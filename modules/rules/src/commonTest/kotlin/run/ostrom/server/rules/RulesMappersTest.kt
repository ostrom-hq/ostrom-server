// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.rules.domain.models.Decision
import run.ostrom.server.rules.domain.models.DecisionSubject
import run.ostrom.server.rules.domain.models.GateChange
import run.ostrom.server.rules.domain.models.InvalidRuleException
import run.ostrom.server.rules.domain.models.RuleCursor
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleGateCheck
import run.ostrom.server.rules.domain.models.RuleSource
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.models.RuleStrength
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.presentation.GateActionDto
import run.ostrom.server.rules.presentation.GatePolicyDto
import run.ostrom.server.rules.presentation.NewRuleRequest
import run.ostrom.server.rules.presentation.RuleGateCheckDto
import run.ostrom.server.rules.presentation.RuleGateDto
import run.ostrom.server.rules.presentation.RulesApi
import run.ostrom.server.rules.presentation.toDto
import run.ostrom.server.rules.presentation.toGate
import run.ostrom.server.rules.presentation.toNewRule
import run.ostrom.server.rules.presentation.toPolicy
import run.ostrom.server.rules.presentation.toQuery
import run.ostrom.server.rules.presentation.toRulePatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RulesMappersTest {
    private val gate = RuleGate(
        listOf(RuleGateAction("push-main", "Push", listOf("p"), listOf("r"), null)),
        listOf("lyes"),
        "x",
        listOf(RuleGateCheck("tests", attested = true)),
    )

    private fun patch(json: String) = Json.parseToJsonElement(json).jsonObject.toRulePatch()

    @Test
    fun aRuleIsServedWithTheContractsValues() {
        val dto = RulesFixtures.rule(status = RuleStatus.ACCEPTED).copy(
            decidedAt = RulesFixtures.epoch,
            sources = listOf(RuleSource("pr", "#1", "https://x")),
            gate = gate,
        ).toDto()
        assertEquals("accepted", dto.status)
        assertEquals("required", dto.strength)
        assertEquals("2026-10-30T12:00:00.000Z", dto.foundAt)
        assertEquals("2026-10-30T12:00:00.000Z", dto.decidedAt)
        assertEquals("https://x", dto.sources.single().url)
        assertEquals(gate, dto.gate?.toGate())
        assertNull(RulesFixtures.rule().toDto().decidedAt)
        assertNull(RulesFixtures.rule().toDto().gate)
    }

    @Test
    fun aDecisionAndThePolicyAreServedWithTheContractsValues() {
        val dto = Decision(
            RulesFixtures.orgA,
            "dec_1",
            RulesFixtures.epoch,
            "lyes",
            DecisionSubject("policy", "self-approval", "Self-approval"),
            "edited",
            null,
            null,
        ).toDto()
        assertEquals("rcpt_dec_1", dto.receiptId)
        assertEquals("2026-10-30T12:00:00.000Z", dto.at)
        assertEquals("Self-approval", dto.subject.label)
        assertEquals(GatePolicyDto("never"), SelfApprovalPolicy.NEVER.toDto())
        assertEquals(SelfApprovalPolicy.SOLE_APPROVER, GatePolicyDto("sole-approver").toPolicy())
        assertFailsWith<InvalidRuleException> { GatePolicyDto("always").toPolicy() }
    }

    @Test
    fun theFiltersOfAListAreRead() {
        val query = RulesApi(repo = "  ", status = "accepted", group = "Reviews").toQuery()
        assertNull(query.repo)
        assertEquals(RuleStatus.ACCEPTED, query.status)
        assertEquals("Reviews", query.group)
        assertEquals("ostrom-hq/web", RulesApi(repo = " ostrom-hq/web ").toQuery().repo)
        assertNull(RulesApi().toQuery().status)
        assertFailsWith<InvalidRuleException> { RulesApi(status = "maybe").toQuery() }
        assertNull(RulesApi().toQuery().after)
        assertEquals(RuleCursor("Reviews", "rule_1"), RulesApi(after = "6~rule_1Reviews").toQuery().after)
    }

    @Test
    fun aCursorReadsBackWhatAPageGave() {
        // Whatever the group and the id hold, separator and digits included.
        val odd = RuleCursor(group = "3~a~b 9", id = "rule_~1")
        assertEquals(odd, RuleCursor.of(odd.wire))
        assertEquals("6~rule_1Reviews", RuleCursor("Reviews", "rule_1").wire)
        assertEquals(RuleCursor("Shipping", "rule_1"), RuleCursor.after(RulesFixtures.rule("rule_1")))
        listOf("", "nope", "~rule_1G", "x~rule_1G", "0~G", "-1~G", "6~rule_1", "9~rule_1").forEach { wire ->
            assertFailsWith<InvalidRuleException>(wire) { RuleCursor.of(wire) }
        }
    }

    @Test
    fun aNewRuleTakesItsDefaults() {
        val rule = NewRuleRequest("Ship", "Releases").toNewRule()
        assertEquals(RuleStatus.ACCEPTED, rule.status)
        assertEquals(RuleStrength.REQUIRED, rule.strength)
        assertNull(rule.gate)
        val gated = NewRuleRequest(
            "Ship",
            "Releases",
            strength = "recommended",
            status = "proposed",
            gate = RuleGateDto(
                listOf(GateActionDto("push-main", "Push", listOf("p"), listOf("r"))),
                listOf("lyes"),
                "x",
                listOf(RuleGateCheckDto("tests", attested = true)),
            ),
        ).toNewRule()
        assertEquals(RuleStrength.RECOMMENDED, gated.strength)
        assertEquals(RuleStatus.PROPOSED, gated.status)
        assertEquals(gate, gated.gate)
        assertFailsWith<InvalidRuleException> { NewRuleRequest("s", "g", strength = "must").toNewRule() }
    }

    @Test
    fun aPatchChangesOnlyWhatItSends() {
        val empty = patch("{}")
        assertNull(empty.statement)
        assertNull(empty.status)
        assertEquals(GateChange.Keep, empty.gate)
        val full = patch(
            """{"statement":"s","status":"edited","strength":"recommended","reviewBy":"2027-01-01","scope":["a"],""" +
                """"group":"g","why":"w","output":"check","gate":{"actions":[{"class":"push-main","label":"Push",""" +
                """"patterns":["p"],"repos":["r"]}],"approvers":["lyes"],"reason":"x",""" +
                """"checks":[{"check":"tests","attested":true}]}}""",
        )
        assertEquals("s", full.statement)
        assertEquals(RuleStatus.EDITED, full.status)
        assertEquals(RuleStrength.RECOMMENDED, full.strength)
        assertEquals("2027-01-01", full.reviewBy)
        assertEquals(listOf("a"), full.scope)
        assertEquals("g", full.group)
        assertEquals("w", full.why)
        assertEquals("check", full.output)
        assertEquals(GateChange.Replace(gate), full.gate)
        assertEquals(GateChange.Remove, patch("""{"gate":null}""").gate, "null removes the gate")
        assertNull(patch("""{"statement":null}""").statement, "null elsewhere changes nothing")
    }

    @Test
    fun aMalformedPatchIsABadRequest() {
        assertFailsWith<InvalidRuleException> { patch("""{"scope":"one"}""") }
        assertFailsWith<InvalidRuleException> { patch("""{"status":"maybe"}""") }
        assertFailsWith<InvalidRuleException> { patch("""{"strength":"must"}""") }
        assertFailsWith<InvalidRuleException> { patch("""{"gate":{"approvers":[]}}""") }
    }
}
