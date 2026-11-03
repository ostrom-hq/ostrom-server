// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.models.WorkflowDoc
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.rules.domain.models.AnalysisBusyException
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.services.AnalysisStart
import dev.ostrom.server.rules.domain.usecases.AnalysisSnapshot
import dev.ostrom.server.rules.domain.usecases.GetAnalysisStateUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.GetReplayUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.StartAnalysisUseCaseImpl
import dev.ostrom.server.rules.fakes.FakeAnalysisEngine
import dev.ostrom.server.rules.fakes.FakeAnalysisRepository
import dev.ostrom.server.rules.fakes.FakeRulesRepository
import dev.ostrom.server.rules.fakes.FakeWorkflows
import dev.ostrom.server.rules.fakes.RecordingAuditTrail
import dev.ostrom.server.rules.fakes.RecordingLogger
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.TestClock
import dev.ostrom.server.rules.infrastructure.NoAnalysisEngine
import dev.ostrom.server.rules.presentation.toDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AnalysisUseCasesTest {
    private val org = RulesFixtures.orgA
    private val clock = TestClock()
    private val runs = FakeAnalysisRepository()
    private val engine = FakeAnalysisEngine()
    private val logger = RecordingLogger()
    private val audit = RecordingAuditTrail()
    private val start = StartAnalysisUseCaseImpl(runs, engine, logger, clock, audit)

    @Test
    fun aStartedRunIsRecordedThenHandedToTheEngine() = runTest {
        val run = start(RulesFixtures.lyes, AnalysisKind.ANALYSIS)
        assertTrue(run.id.startsWith("run_"))
        assertEquals(AnalysisRun.CONNECT, run.phase)
        assertEquals(0.0, run.progress)
        assertEquals("lyes", run.startedBy)
        assertEquals(RulesFixtures.epoch, run.startedAt)
        assertTrue(run.running)
        assertEquals(listOf(run), engine.started, "Recorded before the engine starts")
        assertEquals(run, runs.run(org, AnalysisKind.ANALYSIS))
        assertEquals(1, runs.transactions)
        val line = audit.records.single()
        assertEquals("rule.analysis.started", line.event)
        assertEquals(RulesFixtures.lyes.actor, line.actor)
        assertEquals(org, line.orgId)
        assertEquals("analysis" to run.id, line.subjectType to line.subjectId)
        assertEquals(mapOf("kind" to "analysis"), line.details)
    }

    @Test
    fun aSecondRunOfTheSameKindWaitsForTheFirstButAReplayDoesNot() = runTest {
        start(RulesFixtures.lyes, AnalysisKind.ANALYSIS)
        assertFailsWith<AnalysisBusyException> { start(RulesFixtures.lyes, AnalysisKind.ANALYSIS) }
        assertEquals(AnalysisKind.REPLAY, start(RulesFixtures.lyes, AnalysisKind.REPLAY).kind)
        assertEquals(AnalysisKind.ANALYSIS, start(RulesFixtures.bob, AnalysisKind.ANALYSIS).kind, "Another org")
        // A refused start is no start: three lines, the replay's kind and Bob's organization among them.
        assertEquals(listOf("analysis", "replay", "analysis"), audit.records.map { it.details.getValue("kind") })
        assertEquals(RulesFixtures.orgB, audit.records.last().orgId)
    }

    @Test
    fun aRunTheEngineCannotStartFailsAtOnceAndCanBeStartedAgain() = runTest {
        engine.answer = AnalysisStart.Unavailable("inference", "No model gateway.")
        clock.advance(1.seconds)
        val failed = start(RulesFixtures.lyes, AnalysisKind.REPLAY)
        assertEquals(AnalysisRun.FAILED, failed.phase)
        assertEquals(false, failed.running)
        val failure = failed.failure
        assertEquals("inference", failure?.stage)
        assertEquals("No model gateway.", failure?.reason)
        assertEquals("/api/v1/analysis/replay", failure?.retryPath)
        assertEquals(clock.now(), failure?.at)
        assertEquals(failed, runs.run(org, AnalysisKind.REPLAY))

        engine.answer = AnalysisStart.Started
        val again = start(RulesFixtures.lyes, AnalysisKind.REPLAY)
        assertNull(again.failure, "A failed run never blocks the next")
        runs.runs[org to AnalysisKind.REPLAY] = again.copy(phase = AnalysisRun.READY)
        clock.advance(1.minutes)
        assertEquals(AnalysisRun.CONNECT, start(RulesFixtures.lyes, AnalysisKind.REPLAY).phase, "Nor a ready one")
    }

    @Test
    fun aRunThatStoppedReportingForAnHourNoLongerBlocksTheNext() = runTest {
        val stuck = start(RulesFixtures.lyes, AnalysisKind.ANALYSIS)
        clock.advance(59.minutes)
        assertFailsWith<AnalysisBusyException> { start(RulesFixtures.lyes, AnalysisKind.ANALYSIS) }
        clock.advance(1.minutes)
        val next = start(RulesFixtures.lyes, AnalysisKind.ANALYSIS)
        assertTrue(next.id != stuck.id, "A new run replaces the stuck one")
    }

    @Test
    fun aNewRunKeepsTheLastResultsUntilItWritesItsOwn() = runTest {
        val item = JsonObject(mapOf("judged" to JsonPrimitive(true)))
        val done = start(RulesFixtures.lyes, AnalysisKind.REPLAY).copy(phase = AnalysisRun.READY, items = listOf(item))
        runs.runs[org to AnalysisKind.REPLAY] = done
        engine.answer = AnalysisStart.Unavailable("engine", "No engine.")
        assertEquals(listOf(item), start(RulesFixtures.lyes, AnalysisKind.REPLAY).items)
        assertEquals(listOf(item), runs.run(org, AnalysisKind.REPLAY)?.items)
    }

    @Test
    fun anEngineThatThrowsFailsTheRunAndIsLogged() = runTest {
        engine.failure = IllegalStateException("boom")
        val failed = start(RulesFixtures.lyes, AnalysisKind.ANALYSIS)
        assertEquals("engine", failed.failure?.stage)
        assertEquals("/api/v1/analysis/restart", failed.failure?.retryPath)
        assertTrue(failed.failure?.reason.orEmpty().contains("server log"), "The cause stays in the log")
        assertEquals(listOf(LogLevel.ERROR to "analysis engine failed to start"), logger.events)
    }

    @Test
    fun aCancelledStartIsNeverSwallowed() = runTest {
        engine.failure = CancellationException("stop")
        assertFailsWith<CancellationException> { start(RulesFixtures.lyes, AnalysisKind.ANALYSIS) }
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun withoutAnEngineEveryRunFailsWithItsReason() = runTest {
        val none = StartAnalysisUseCaseImpl(runs, NoAnalysisEngine(), logger, clock, audit)
        val failed = none(RulesFixtures.lyes, AnalysisKind.ANALYSIS)
        assertEquals("engine", failed.failure?.stage)
        assertEquals("No analysis engine runs on this server yet.", failed.failure?.reason)
    }

    @Test
    fun theStateCarriesEveryRulePastAThousand() = runTest {
        val rules = FakeRulesRepository()
        (1..1_500).forEach { n ->
            val id = "rule_${n.toString().padStart(4, '0')}"
            rules.rules[org to id] = RulesFixtures.rule(id, group = "Group ${n % 7}")
        }
        val state = GetAnalysisStateUseCaseImpl(runs, rules, FakeWorkflows(emptyList()))(org)
        assertEquals(1_500, state.rules.size, "Not cut at 1 000")
        assertTrue(state.rules.any { it.id == "rule_1400" }, "Rule 1 400 counts")
    }

    @Test
    fun theStateCarriesTheLastAnalysisTheRulesAndTheWorkflows() = runTest {
        val rules = FakeRulesRepository()
        rules.rules[org to "rule_1"] = RulesFixtures.rule("rule_1")
        val workflow = Workflow(
            id = "release",
            name = "Release",
            domain = "Shipping",
            nodes = emptyList(),
            edges = emptyList(),
            status = WorkflowStatus.ACCEPTED,
            foundAt = "2026-10-30T12:00:00.000Z",
        )
        val stored = StoredWorkflow(
            WorkflowDoc(JsonObject(mapOf("id" to JsonPrimitive("release"))), workflow),
            3,
            "lyes",
            RulesFixtures.epoch,
        )
        val state = GetAnalysisStateUseCaseImpl(runs, rules, FakeWorkflows(listOf(stored)))
        val empty = state(org)
        assertNull(empty.run)
        assertEquals(listOf("rule_1"), empty.rules.map { it.id })
        assertEquals(listOf(stored), empty.workflows)
        val dto = empty.toDto()
        assertNull(dto.phase)
        assertNull(dto.progress)
        assertNull(dto.failure)
        assertEquals(emptyList(), dto.repos)
        assertEquals(listOf("rule_1"), dto.rules.map { it.id })
        val served = dto.workflows.single()
        assertEquals("release", served.getValue("id").jsonPrimitive.content)
        assertEquals("3", served.getValue("version").jsonPrimitive.content)
        assertEquals("lyes", served.getValue("updatedBy").jsonPrimitive.content)
        assertEquals("2026-10-30T12:00:00Z", served.getValue("updatedAt").jsonPrimitive.content)

        engine.answer = AnalysisStart.Unavailable("github", "GitHub is not connected.")
        start(RulesFixtures.lyes, AnalysisKind.ANALYSIS)
        val failed = state(org).toDto()
        assertEquals(AnalysisRun.FAILED, failed.phase)
        assertEquals(0.0, failed.progress)
        assertEquals(
            mapOf(
                "stage" to "github",
                "reason" to "GitHub is not connected.",
                "at" to "2026-10-30T12:00:00.000Z",
                "retryPath" to "/api/v1/analysis/restart",
            ),
            failed.failure,
        )
        val last = runs.run(org, AnalysisKind.ANALYSIS)?.copy(failure = null)
        val running = AnalysisSnapshot(last, emptyList(), emptyList())
        assertNull(running.toDto().failure)
    }

    @Test
    fun theReplayServesWhatTheLastReplayFound() = runTest {
        val replay = GetReplayUseCaseImpl(runs)
        assertEquals(emptyList(), replay(org), "No replay yet")
        val item = JsonObject(mapOf("judged" to JsonPrimitive(false)))
        val run = start(RulesFixtures.lyes, AnalysisKind.REPLAY)
        runs.runs[org to AnalysisKind.REPLAY] = run.copy(phase = AnalysisRun.READY, items = listOf(item))
        assertEquals(listOf(item), replay(org))
    }

    // AUDIT-SECU-ANALYSE R3: a replay holds the server's only replay lane; an organization replays once a minute.
    @Test
    fun aReplayStartsAtMostOnceAMinute() = runTest {
        val done = start(RulesFixtures.lyes, AnalysisKind.REPLAY).copy(phase = AnalysisRun.READY)
        runs.runs[org to AnalysisKind.REPLAY] = done
        clock.advance(59.seconds)
        val refused = assertFailsWith<OstromException> { start(RulesFixtures.lyes, AnalysisKind.REPLAY) }
        assertEquals("A replay ran less than a minute ago: start it again in a minute.", refused.message)
        assertEquals(done, runs.run(org, AnalysisKind.REPLAY), "A refused start writes nothing")
        clock.advance(1.seconds)
        assertEquals(AnalysisRun.CONNECT, start(RulesFixtures.lyes, AnalysisKind.REPLAY).phase)
        val analysed = start(RulesFixtures.lyes, AnalysisKind.ANALYSIS).copy(phase = AnalysisRun.READY)
        runs.runs[org to AnalysisKind.ANALYSIS] = analysed
        assertEquals(AnalysisRun.CONNECT, start(RulesFixtures.lyes, AnalysisKind.ANALYSIS).phase, "Not an analysis")
    }
}
