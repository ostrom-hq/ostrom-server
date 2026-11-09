// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.rules.domain.models.AnalysisFailure
import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.models.RuleStrength
import run.ostrom.server.rules.domain.services.RuleInference
import run.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCaseImpl
import run.ostrom.server.rules.fakes.FakeAnalysisRepository
import run.ostrom.server.rules.fakes.FakeRepositoryReader
import run.ostrom.server.rules.fakes.FakeRulesRepository
import run.ostrom.server.rules.fakes.RecordingLogger
import run.ostrom.server.rules.fakes.RepositoryFixture
import run.ostrom.server.rules.fakes.RepositoryFixtures
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.fakes.TestClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AnalyzeOrganizationUseCaseTest {
    private val org = RulesFixtures.orgA
    private val clock = TestClock()
    private val runs = FakeAnalysisRepository()
    private val rules = FakeRulesRepository()
    private val reader = FakeRepositoryReader()
    private val logger = RecordingLogger()
    private val analyze = AnalyzeOrganizationUseCaseImpl(runs, rules, reader, logger, clock)
    private val previous = JsonObject(mapOf("name" to JsonPrimitive("last")))
    private val run = AnalysisRun(
        orgId = org,
        kind = AnalysisKind.ANALYSIS,
        id = "run_1",
        phase = AnalysisRun.CONNECT,
        progress = 0.0,
        failure = null,
        startedBy = "lyes",
        startedAt = RulesFixtures.epoch,
        updatedAt = RulesFixtures.epoch,
        items = listOf(previous),
    )

    private fun recorded() = runs.runs[org to AnalysisKind.ANALYSIS]

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun analyse(vararg fixtures: RepositoryFixture) {
        reader.connect(org, *fixtures)
        runs.runs[org to AnalysisKind.ANALYSIS] = run
        analyze(run, fixtures.map { it.repo })
    }

    @Test
    fun theAnalysisReadsEachRepositoryProposesItsRulesAndEndsReady() = runTest {
        val seen = mutableListOf<Pair<String, Int>>()
        reader.onTree = { recorded()?.let { seen += it.phase to (it.progress * 1000).roundToInt() } }
        analyse(RepositoryFixtures.api, RepositoryFixtures.web, RepositoryFixtures.tools, RepositoryFixtures.docs)

        assertEquals(listOf("web", "api", "docs", "tools"), reader.trees, "Most recently pushed first")
        assertEquals(listOf("read" to 0, "read" to 225, "read" to 450, "read" to 675), seen)
        assertEquals(listOf("web/package.json", "api/.github/CODEOWNERS"), reader.files, "Only the wanted files")
        val ready = recorded()
        assertEquals(AnalysisRun.READY, ready?.phase)
        assertEquals(1.0, ready?.progress)
        assertEquals(null, ready?.failure)
        assertEquals(
            listOf(
                json(
                    """{"name":"web","description":"The web service","language":"TypeScript","defaultBranch":"develop",
                    |"archived":false,"pushedAt":"2026-10-30T09:00:00Z","owners":[]}""".trimMargin(),
                ),
                json(
                    """{"name":"api","description":"The api service","language":"Kotlin","defaultBranch":"main",
                    |"archived":false,"pushedAt":"2026-10-29T10:00:00Z","owners":["lyes"]}""".trimMargin(),
                ),
                json(
                    """{"name":"docs","description":"The docs service","defaultBranch":"main","archived":false,
                    |"pushedAt":"2026-10-01T00:00:00Z","owners":[]}""".trimMargin(),
                ),
                json("""{"name":"tools","language":"Python","defaultBranch":"main","archived":false,"owners":[]}"""),
            ),
            ready?.items,
        )

        assertEquals(20, rules.rules.size, "9 for api, 6 for web, 3 for tools, 2 for docs")
        assertEquals(1, rules.transactions, "One transaction for every proposal")
        val branch = rules.rules.getValue(org to "rule_learned_96cd39a4b8216a1472494c1f")
        assertEquals(
            "Never push to `main` of api: open a pull request, which merges after 1 approval and the checks `ci` pass.",
            branch.statement,
        )
        assertEquals(RuleStatus.PROPOSED, branch.status)
        assertEquals("learned", branch.origin)
        assertEquals("analysis", branch.foundBy)
        assertEquals(RulesFixtures.epoch, branch.foundAt)
        assertEquals(RuleStrength.REQUIRED, branch.strength)
        assertEquals(0.95, branch.confidence)
        assertEquals(null, branch.decidedBy)
        assertEquals(listOf("lyes"), branch.gate?.approvers)
        assertTrue(rules.decisions.isEmpty(), "A proposal is not a decision: nothing is signed")
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun aNewRunProposesOnlyWhatIsMissingAndNeverRewritesARule() = runTest {
        val build = "rule_learned_2ba325693cbd92ee1184b581"
        val branch = "rule_learned_96cd39a4b8216a1472494c1f"
        val ci = "rule_learned_6738541d6607a6107137544c"
        val secrets = "rule_learned_d22c1fc99c8a7d391a12a5b9"
        val accepted = RulesFixtures.rule(branch, status = RuleStatus.ACCEPTED).copy(decidedBy = "lyes")
        val rejected = RulesFixtures.rule(ci, status = RuleStatus.REJECTED)
        val written = RulesFixtures.rule(secrets).copy(origin = "written")
        val edited = RulesFixtures.rule(build).copy(scope = listOf("api/src/"), reviewBy = "2027-01-28")
        listOf(accepted, rejected, written, edited).forEach { rules.rules[org to it.id] = it }
        clock.advance(1.days)

        analyse(RepositoryFixtures.api)

        assertEquals(accepted, rules.rules[org to branch], "Decided: the team's")
        assertEquals(rejected, rules.rules[org to ci], "Rejected: not proposed again")
        assertEquals(written, rules.rules[org to secrets], "Written by the team")
        assertEquals(edited, rules.rules[org to build], "Still proposed, but a person may have edited it")
        val added = rules.rules.getValue(org to "rule_learned_4316fafe7fd948d4dcd81406")
        assertEquals(clock.now(), added.foundAt, "The missing ones are proposed")
        assertEquals(9, rules.rules.size)
    }

    @Test
    fun onlyArchivedRepositoriesFailTheRunAndKeepTheLastResults() = runTest {
        analyse(RepositoryFixture(RepositoryFixtures.ref("old", archived = true), listOf("a.kt")))
        assertEquals(emptyList(), reader.trees)
        val failed = recorded()
        assertEquals(AnalysisRun.FAILED, failed?.phase)
        assertEquals(
            AnalysisFailure(
                "github",
                "Every repository the GitHub App sees is archived: there is nothing to analyse.",
                RulesFixtures.epoch,
                "/api/v1/analysis/restart",
            ),
            failed?.failure,
        )
        assertEquals(listOf(previous), failed?.items, "A failed run erases no result")
    }

    @Test
    fun aRepositoryThatCannotBeReadIsLeftOutAndNoneReadableFailsTheRun() = runTest {
        reader.failing = setOf("api")
        analyse(RepositoryFixtures.api, RepositoryFixtures.web)
        assertEquals(AnalysisRun.READY, recorded()?.phase)
        assertEquals(
            listOf("web"),
            recorded()?.items?.map { it.getValue("name") }?.map { (it as JsonPrimitive).content },
        )
        assertEquals(listOf(LogLevel.WARN to "analysis could not read a repository"), logger.events)

        reader.failing = setOf("api", "web")
        analyse(RepositoryFixtures.api, RepositoryFixtures.web)
        val failed = recorded()?.failure
        assertEquals("github", failed?.stage)
        assertEquals(
            "Ostrom could not read any repository of the organization on GitHub; see the server log, then start the " +
                "analysis again.",
            failed?.reason,
        )
    }

    @Test
    fun aRunReadsTheThirtyMostRecentlyPushedRepositories() = runTest {
        val fixtures = (0..30).map {
            val day = (it + 1).toString().padStart(2, '0')
            RepositoryFixture(
                RepositoryFixtures.ref("r$it", pushedAt = "2026-10-${day}T00:00:00Z"),
                listOf("README.md"),
            )
        }
        analyse(*fixtures.toTypedArray())
        assertEquals(30, reader.trees.size)
        assertEquals("r30", reader.trees.first())
        assertTrue("r0" !in reader.trees, "The oldest is left out")
    }

    @Test
    fun aRunANewerOneReplacedStopsWithoutWritingAnything() = runTest {
        val newer = run.copy(id = "run_2")
        reader.onTree = { runs.runs[org to AnalysisKind.ANALYSIS] = newer }
        analyse(RepositoryFixtures.api)
        assertEquals(newer, recorded(), "The newer run is untouched")
        assertTrue(rules.rules.isEmpty())
        assertEquals(listOf(LogLevel.DEBUG to "analysis run superseded"), logger.events)

        reader.onTree = { runs.runs.remove(org to AnalysisKind.ANALYSIS) }
        analyse(RepositoryFixtures.api)
        assertEquals(null, recorded(), "A run that vanished is not written back")
        assertTrue(rules.rules.isEmpty())
    }

    @Test
    fun aFailureOfARunThatWasReplacedOrRemovedWritesNothing() = runTest {
        val newer = run.copy(id = "run_2")
        reader.failing = setOf("api")
        reader.onTree = { runs.runs[org to AnalysisKind.ANALYSIS] = newer }
        analyse(RepositoryFixtures.api)
        assertEquals(newer, recorded())

        reader.onTree = {}
        reader.connect(org)
        analyze(run, listOf(RepositoryFixtures.ref("old", archived = true)))
        assertEquals(newer, recorded(), "Another run is not failed")
        runs.runs.clear()
        analyze(run, listOf(RepositoryFixtures.ref("old", archived = true)))
        assertEquals(null, recorded(), "No run, nothing written")
    }

    @Test
    fun anUnexpectedErrorFailsTheRunAndIsLogged() = runTest {
        reader.onTree = { error("boom") }
        analyse(RepositoryFixtures.api)
        assertEquals("engine", recorded()?.failure?.stage)
        assertEquals("The analysis stopped on an unexpected error; see the server log.", recorded()?.failure?.reason)
        assertEquals(listOf(LogLevel.ERROR to "analysis failed"), logger.events)
    }

    @Test
    fun invokeWithCancellationPropagatesExceptionAndFailsTheRun() = runTest {
        reader.onTree = { throw CancellationException("server stops") }
        assertFailsWith<CancellationException> { analyse(RepositoryFixtures.api) }
        assertEquals("engine", recorded()?.failure?.stage)
        assertEquals("The server stopped during the analysis; start it again.", recorded()?.failure?.reason)
    }

    @Test
    fun aFindingPastTheBoundsOfARuleIsLeftOutAndAMissingFileIsSkipped() = runTest {
        val long = "a".repeat(300)
        val big = RepositoryFixture(RepositoryFixtures.ref("big"), listOf("$long/terraform/main.tf", "package.json"))
        analyse(big)
        assertEquals(listOf("big/package.json"), reader.files)
        assertEquals(
            listOf("Branches", "Build"),
            rules.rules.values.map { it.group }.sorted(),
            "The infrastructure rule's scope is too long; npm and the branch stay",
        )
        assertEquals(AnalysisRun.READY, recorded()?.phase)
        assertEquals(listOf(LogLevel.WARN to "analysis left out a proposal"), logger.events)
    }

    // AUDIT-SECU-ANALYSE R1: a tree past 20,000 paths is cut and said cut; each repository is let go before the next.
    @Test
    fun aTreePastTwentyThousandPathsIsCutAndSaidCut() = runTest {
        val huge = RepositoryFixture(
            RepositoryFixtures.ref("huge"),
            listOf("gradlew", "build.gradle.kts") + (1..25_000).map { "src/f$it.kt" },
        )
        analyse(huge)
        val build = rules.rules.values.single { it.group == "Build" && it.output == "check" }
        assertTrue(build.why.orEmpty().endsWith(RuleInference.TRUNCATED), build.why)
        assertEquals(AnalysisRun.READY, recorded()?.phase)
    }

    // AUDIT-SECU-ANALYSE R2: GitHub slow or rate-limited at every call held one of the server's two workers for hours.
    @Test
    fun aRunPastItsDeadlineFailsAndGivesItsWorkerBack() = runTest {
        reader.onTree = { awaitCancellation() }
        analyse(RepositoryFixtures.api)
        assertEquals("engine", recorded()?.failure?.stage)
        assertEquals("The analysis took longer than 15 minutes; start it again.", recorded()?.failure?.reason)
        assertEquals(listOf(previous), recorded()?.items, "The last results stay")
        assertTrue(rules.rules.isEmpty(), "Nothing half-written")
    }
}
