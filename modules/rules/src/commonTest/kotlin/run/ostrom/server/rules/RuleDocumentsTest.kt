// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import run.ostrom.server.gates.domain.models.SelfApprovalPolicy
import run.ostrom.server.rules.domain.models.RuleGate
import run.ostrom.server.rules.domain.models.RuleGateAction
import run.ostrom.server.rules.domain.models.RuleGateCheck
import run.ostrom.server.rules.domain.models.RuleSource
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.services.RuleDecisions
import run.ostrom.server.rules.domain.services.RuleDocuments
import run.ostrom.server.rules.domain.services.SelfApprovalValues
import run.ostrom.server.rules.fakes.RulesFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

class RuleDocumentsTest {
    private val gate = RuleGate(
        listOf(RuleGateAction("custom", "Deploy", listOf("deploy"), emptyList(), listOf("Bash"))),
        listOf("lyes"),
        "Owner of prod",
    )

    @Test
    fun aWholeRuleUsesTheContractsNamesAndLeavesAbsentFieldsOut() {
        val bare = RuleDocuments.full(RulesFixtures.rule())
        assertEquals(
            setOf(
                "id", "statement", "output", "group", "scope", "sources", "strength", "confidence", "status",
                "foundBy", "foundAt", "origin",
            ),
            bare.keys,
        )
        assertEquals("2026-10-30T12:00:00.000Z", bare.getValue("foundAt").jsonPrimitive.content)

        val full = RuleDocuments.full(
            RulesFixtures.rule(status = RuleStatus.ACCEPTED).copy(
                why = "Audit",
                reviewBy = "2027-01-28",
                decidedBy = "lyes",
                decidedAt = Instant.parse("2026-10-30T12:00:00Z"),
                origin = null,
                gate = gate,
                sources = listOf(RuleSource("pr", "#12", null), RuleSource("file", "CODEOWNERS", "https://x")),
            ),
        )
        assertEquals(
            Json.parseToJsonElement(
                """[{"kind":"pr","ref":"#12"},{"kind":"file","ref":"CODEOWNERS","url":"https://x"}]""",
            ),
            full.getValue("sources"),
        )
        assertEquals(setOf("why", "reviewBy", "decidedBy", "decidedAt", "gate"), full.keys - bare.keys)
        assertFalse("origin" in full)
    }

    @Test
    fun aGateKeepsItsToolsOnlyWhenSet() {
        assertEquals(
            Json.parseToJsonElement(
                """{"actions":[{"class":"custom","label":"Deploy","patterns":["deploy"],""" +
                    """"repos":[],"tools":["Bash"]}],""" +
                    """"approvers":["lyes"],"reason":"Owner of prod"}""",
            ),
            RuleDocuments.gate(gate),
        )
        val plain = RuleDocuments.gate(gate.copy(actions = gate.actions.map { it.copy(tools = null) }, reason = null))
        assertEquals(setOf("actions", "approvers"), plain.keys)
        assertFalse("tools" in plain.getValue("actions").jsonArray[0].jsonObject)
    }

    @Test
    fun aGateKeepsItsChecksOnlyWhenItNeedsSome() {
        val checked = RuleDocuments.gate(
            gate.copy(
                checks = listOf(RuleGateCheck("tests", attested = true), RuleGateCheck("lint", attested = false)),
            ),
        )
        assertEquals(
            Json.parseToJsonElement("""[{"check":"tests","attested":true},{"check":"lint","attested":false}]"""),
            checked.getValue("checks"),
        )
        assertFalse("checks" in RuleDocuments.gate(gate))
    }

    @Test
    fun aDecisionPicksWhatItChanges() {
        val picked = RuleDocuments.picked(RulesFixtures.rule().copy(why = "Audit", gate = gate))
        assertEquals(setOf("status", "statement", "why", "strength", "scope", "group", "output", "gate"), picked.keys)
        assertEquals(
            setOf("status", "statement", "strength", "scope", "group", "output"),
            RuleDocuments.picked(RulesFixtures.rule()).keys,
        )
    }

    @Test
    fun thePolicyValuesAreTheContracts() {
        assertEquals("sole-approver", SelfApprovalValues.valueOf(SelfApprovalPolicy.SOLE_APPROVER))
        assertEquals("never", SelfApprovalValues.valueOf(SelfApprovalPolicy.NEVER))
        SelfApprovalPolicy.entries.forEach { assertEquals(it, SelfApprovalValues.of(SelfApprovalValues.valueOf(it))) }
        assertNull(SelfApprovalValues.of("always"))
        assertEquals(
            Json.parseToJsonElement("""{"selfApproval":"never"}"""),
            RuleDocuments.policy(SelfApprovalPolicy.NEVER),
        )
    }

    @Test
    fun aDecidedRuleIsReviewedNinetyDaysLater() {
        assertEquals("2027-01-28", RuleDecisions.reviewByOf(RulesFixtures.epoch))
    }
}
