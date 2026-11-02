// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionSubject
import dev.ostrom.server.rules.domain.models.GateChange
import dev.ostrom.server.rules.domain.models.InvalidRuleException
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import dev.ostrom.server.rules.domain.models.RuleGateCheck
import dev.ostrom.server.rules.domain.models.RuleSource
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.models.RuleStrength
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.presentation.GateActionDto
import dev.ostrom.server.rules.presentation.GatePolicyDto
import dev.ostrom.server.rules.presentation.NewRuleRequest
import dev.ostrom.server.rules.presentation.RuleGateCheckDto
import dev.ostrom.server.rules.presentation.RuleGateDto
import dev.ostrom.server.rules.presentation.RulesApi
import dev.ostrom.server.rules.presentation.toDto
import dev.ostrom.server.rules.presentation.toGate
import dev.ostrom.server.rules.presentation.toNewRule
import dev.ostrom.server.rules.presentation.toPolicy
import dev.ostrom.server.rules.presentation.toQuery
import dev.ostrom.server.rules.presentation.toRulePatch
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
