// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.repositories.GateHistory
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
    fun aGatedRulePastTheThousandthIsReplayedToo() = runTest {
        // 1 500 gated rules: only rule 1 400 gates the deploy, so the deploy is asked only if it is read.
        (1..1_500).forEach { n ->
            val id = "rule_${n.toString().padStart(4, '0')}"
            rules.rules[org to id] = gated(id = id, cls = if (n == 1_400) "deploy" else "publish")
        }
        replay()
        val deploy = recorded()?.items.orEmpty().single { it.getValue("actionClass").jsonPrimitive.content == "deploy" }
        assertEquals(GateReplay.ASKED, deploy.outcome(), "Rule 1 400 counts")
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aServerReplaysOneOrganizationAtATime() = runTest {
        val orgB = RulesFixtures.orgB
        rules.rules[org to "rule_a"] = gated(id = "rule_a")
        rules.rules[orgB to "rule_b"] = gated(id = "rule_b", orgId = orgB)
        val release = CompletableDeferred<Unit>()
        val reads = mutableListOf<Uuid>()
        val held = object : GateHistory {
            override suspend fun recent(orgId: Uuid, since: Instant, limit: Int): List<Gate> {
                reads += orgId
                if (orgId == org) release.await()
                return emptyList()
            }
        }
        val server = ReplayRulesUseCaseImpl(runs, rules, held, RecordingLogger(), TestClock())
        val runB = run.copy(orgId = orgB)
        runs.runs[org to AnalysisKind.REPLAY] = run
        runs.runs[orgB to AnalysisKind.REPLAY] = runB
        val first = launch { server(run) }
        runCurrent()
        val second = launch { server(runB) }
        runCurrent()
        assertEquals(listOf(org), reads, "B waits for A's replay to end")
        release.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf(org, orgB), reads)
        assertEquals(AnalysisRun.READY, runs.runs[orgB to AnalysisKind.REPLAY]?.phase)
    }

    private fun JsonObject.outcome() = getValue("outcome").jsonPrimitive.content
}
