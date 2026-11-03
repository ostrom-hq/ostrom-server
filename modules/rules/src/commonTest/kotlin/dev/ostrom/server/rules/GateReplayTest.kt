// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.services.GateReplay
import dev.ostrom.server.rules.fakes.ReplayFixtures
import dev.ostrom.server.rules.fakes.ReplayFixtures.action
import dev.ostrom.server.rules.fakes.ReplayFixtures.gated
import dev.ostrom.server.rules.fakes.ReplayFixtures.push
import dev.ostrom.server.rules.fakes.RulesFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GateReplay]: what the rules would have done on each recorded boundary action, and why, without a model. */
class GateReplayTest {
    private fun replay(
        gate: Gate,
        rules: List<Rule>,
        policy: SelfApprovalPolicy = SelfApprovalPolicy.NEVER,
    ): JsonObject = GateReplay.replay(gate, GateReplay.prepare(rules), policy)

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

    private fun JsonObject.texts(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun anActionNoRuleGatesPassesAndSaysWhatHappened() {
        val gate = action(mode = GateMode.UNGATED)
        val item = replay(gate, listOf(RulesFixtures.rule(), gated(cls = "deploy")))
        assertEquals(GateReplay.PASSED, item.text("outcome"))
        assertEquals(gate.id.toString(), item.text("gateId"))
        assertEquals("2026-10-30T11:00:00Z", item.text("at"))
        assertEquals("acme/api", item.text("repo"))
        assertEquals("push-main", item.text("actionClass"))
        assertEquals("push-main", item.text("action"))
        assertEquals(listOf("ses_1"), item.texts("sessions"))
        val happened = item.getValue("happened").jsonObject
        assertEquals("ungated", happened.text("mode"))
        assertEquals("pending", happened.text("status"))
        assertEquals(JsonArray(emptyList()), item["rules"])
        assertEquals(emptyList(), item.texts("approvers"))
        assertEquals("No replayed rule gates this action: it would have run without asking anything.", item.text("why"))
        assertEquals("true", item.text("simulation"))
        assertNull(item["command"], "The command is never copied: it may carry a secret")
        assertNull(item["target"])
    }

    @Test
    fun aGatedActionWaitsForAnotherApproverAndNamesTheRule() {
        val item = replay(action(), listOf(gated(approvers = listOf("bob", "LYES", "Bob"))))
        assertEquals(GateReplay.ASKED, item.text("outcome"))
        assertEquals(listOf("bob"), item.texts("approvers"), "Neither the asker nor a name twice")
        val rule = item.getValue("rules").jsonArray.single().jsonObject
        assertEquals("rule_gate", rule.text("id"))
        assertEquals("proposed", rule.text("status"))
        assertEquals(listOf("bob", "LYES", "Bob"), rule.texts("approvers"))
        assertEquals(
            "It would have waited for a yes from bob (rule_gate: Every change to main goes through a pull request.).",
            item.text("why"),
        )
    }

    @Test
    fun nobodyToSayYesBlocksTheAction() {
        val alone = replay(action(), listOf(gated(approvers = listOf("Lyes"))))
        assertEquals(GateReplay.BLOCKED, alone.text("outcome"))
        assertTrue("only Lyes, who asked, may approve it" in alone.text("why").orEmpty(), alone.text("why"))
        val none = replay(action(), listOf(gated(approvers = emptyList())))
        assertEquals(GateReplay.BLOCKED, none.text("outcome"))
        assertTrue("the gate names no approver" in none.text("why").orEmpty(), none.text("why"))
    }

    @Test
    fun theSoleApproverMaySayYesOnlyWhenTheOrganizationAllowsIt() {
        val sole = replay(action(), listOf(gated(approvers = listOf("lyes"))), SelfApprovalPolicy.SOLE_APPROVER)
        assertEquals(GateReplay.ASKED, sole.text("outcome"))
        assertEquals(listOf("lyes"), sole.texts("approvers"))
        val two = replay(action(), listOf(gated(approvers = listOf("lyes", "bob"))), SelfApprovalPolicy.SOLE_APPROVER)
        assertEquals(listOf("bob"), two.texts("approvers"), "Not alone: someone else says yes")
    }

    @Test
    fun aPatternCoversACommandOfAnotherClassAndABrokenPatternCoversNothing() {
        val deploy = action(subject = push(cls = "shell", command = "kubectl apply -f prod.yaml"))
        fun outcome(pattern: String) = replay(deploy, listOf(gated(cls = "deploy", patterns = listOf(pattern))))
            .text("outcome")
        assertEquals(GateReplay.ASKED, outcome("KUBECTL apply"))
        assertEquals(GateReplay.PASSED, outcome("("))
    }

    @Test
    fun theRepositoriesOfTheActionAndTheScopeOfTheRuleNarrowIt() {
        val api = action()
        fun outcome(rule: Rule, gate: Gate = api) = replay(gate, listOf(rule)).text("outcome")
        assertEquals(GateReplay.ASKED, outcome(gated(repos = listOf("api"))), "The short name")
        assertEquals(GateReplay.ASKED, outcome(gated(repos = listOf("ACME/api"))), "The full name")
        assertEquals(GateReplay.PASSED, outcome(gated(repos = listOf("web"))))
        assertEquals(GateReplay.ASKED, outcome(gated(scope = listOf("acme/API"))))
        assertEquals(GateReplay.ASKED, outcome(gated(scope = listOf("api"))))
        assertEquals(GateReplay.ASKED, outcome(gated(scope = listOf("api/src/main"))), "A path of the repository")
        assertEquals(GateReplay.PASSED, outcome(gated(scope = listOf("web", "apis"))))
        val nowhere = action(subject = push(repo = null))
        assertEquals(GateReplay.PASSED, outcome(gated(scope = listOf("api")), nowhere), "No repository, no scoped rule")
        assertEquals(GateReplay.PASSED, outcome(gated(repos = listOf("api")), nowhere))
        assertEquals(GateReplay.ASKED, outcome(gated(), nowhere), "An unscoped rule covers it")
        assertNull(replay(nowhere, emptyList()).text("repo"))
    }

    @Test
    fun theTargetIsThePullRequestOrThePushedRefs() {
        val refs = listOf(PushRef("refs/heads/main", "a"), PushRef("refs/tags/v1", "b"))
        val pushed = action(subject = push().copy(push = GatePush("origin", refs, force = false)))
        assertEquals("refs/heads/main, refs/tags/v1", replay(pushed, emptyList()).text("target"))
        val merged = action(subject = push(cls = "merge").copy(pullRequest = GatePullRequest(6, "c", "main")))
        assertEquals("pull request #6 into main", replay(merged, emptyList()).text("target"))
    }

    @Test
    fun onlyRulesWithAGateAreReplayed() {
        val prepared = GateReplay.prepare(listOf(RulesFixtures.rule(), gated()))
        assertEquals(listOf("rule_gate"), prepared.map { it.rule.id })
        assertEquals(listOf("push-main"), prepared.single().actions.map { it.action.cls })
        assertFalse(prepared.single().gate.approvers.isEmpty())
        assertEquals(ReplayFixtures.gated().gate, prepared.single().gate)
    }
}
