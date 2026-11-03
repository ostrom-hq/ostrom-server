// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.services.GateReplay
import dev.ostrom.server.rules.domain.usecases.ReplayRulesUseCaseImpl
import dev.ostrom.server.rules.fakes.FakeAnalysisRepository
import dev.ostrom.server.rules.fakes.FakeGateHistory
import dev.ostrom.server.rules.fakes.FakeRulesRepository
import dev.ostrom.server.rules.fakes.RecordingLogger
import dev.ostrom.server.rules.fakes.ReplayFixtures.action
import dev.ostrom.server.rules.fakes.ReplayFixtures.gated
import dev.ostrom.server.rules.fakes.ReplayFixtures.push
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.TestClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

/** [ReplayRulesUseCaseImpl]: the organization's gated rules replayed on its last 30 days of boundary actions. */
class ReplayRulesUseCaseTest {
    private val org = RulesFixtures.orgA
    private val runs = FakeAnalysisRepository()
    private val rules = FakeRulesRepository()
    private val run = AnalysisRun(
        orgId = org,
        kind = AnalysisKind.REPLAY,
        id = "run_replay",
        phase = AnalysisRun.CONNECT,
        progress = 0.0,
        failure = null,
        startedBy = "lyes",
        startedAt = RulesFixtures.epoch,
        updatedAt = RulesFixtures.epoch,
    )
    private val history = FakeGateHistory(
        listOf(
            action(hoursAgo = 2),
            action(subject = push(cls = "deploy", command = "make deploy"), hoursAgo = 1),
            action(hoursAgo = 24 * 31),
            action(orgId = RulesFixtures.orgB, hoursAgo = 1),
        ),
    )
    private val useCase = ReplayRulesUseCaseImpl(runs, rules, history, RecordingLogger(), TestClock())

    private fun recorded() = runs.runs[org to AnalysisKind.REPLAY]

    private suspend fun replay() {
        runs.runs[org to AnalysisKind.REPLAY] = run
        useCase(run)
    }

    @Test
    fun theRulesAreReplayedOnTheOrganizationsLastThirtyDaysNewestFirst() = runTest {
        val edited = gated(id = "rule_edited", approvers = listOf("bob")).copy(status = RuleStatus.EDITED)
        rules.rules[org to edited.id] = edited
        rules.rules[org to "rule_other"] = gated(id = "rule_other", orgId = RulesFixtures.orgB, cls = "deploy")
        replay()

        val ready = recorded()
        assertEquals(AnalysisRun.READY, ready?.phase)
        assertEquals(1.0, ready?.progress)
        assertNull(ready?.failure)
        val outcomes = ready?.items.orEmpty().map { it.getValue("actionClass").jsonPrimitive.content to it.outcome() }
        assertEquals(listOf("deploy" to GateReplay.PASSED, "push-main" to GateReplay.ASKED), outcomes)
        assertEquals(listOf(Triple(org, RulesFixtures.epoch - 30.days, 1000)), history.reads, "Its own history only")
    }

    @Test
    fun theOrganizationsSelfApprovalPolicyDecidesWhetherTheAskerMaySayYes() = runTest {
        rules.rules[org to "rule_self"] = gated(id = "rule_self", approvers = listOf("lyes"))
        replay()
        assertEquals(GateReplay.BLOCKED, recorded()?.items?.last()?.outcome(), "Never, by default")
        rules.policies[org] = SelfApprovalPolicy.SOLE_APPROVER
        replay()
        assertEquals(GateReplay.ASKED, recorded()?.items?.last()?.outcome())
    }

    @Test
    fun withoutAGatedRuleTheReplayFailsVisiblyAndReadsNoHistory() = runTest {
        rules.rules[org to "rule_1"] = RulesFixtures.rule()
        rules.rules[org to "rule_no"] = gated(id = "rule_no").copy(status = RuleStatus.REJECTED)
        replay()

        val failed = recorded()
        assertEquals(AnalysisRun.FAILED, failed?.phase)
        assertEquals("engine", failed?.failure?.stage)
        assertEquals("/api/v1/analysis/replay", failed?.failure?.retryPath)
        assertEquals(
            "No rule of the organization gates a boundary action yet: run the analysis, or give a rule a gate, " +
                "then replay again.",
            failed?.failure?.reason,
        )
        assertEquals(emptyList(), history.reads)
    }

    private fun JsonObject.outcome() = getValue("outcome").jsonPrimitive.content
}
