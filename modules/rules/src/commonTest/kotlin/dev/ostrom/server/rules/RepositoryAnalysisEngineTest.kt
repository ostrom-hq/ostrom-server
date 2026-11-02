// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.RepositoryReadException
import dev.ostrom.server.rules.domain.models.RepositoryRef
import dev.ostrom.server.rules.domain.services.AnalysisStart
import dev.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCase
import dev.ostrom.server.rules.domain.usecases.ReplayRulesUseCase
import dev.ostrom.server.rules.fakes.FakeRepositoryReader
import dev.ostrom.server.rules.fakes.RecordingLogger
import dev.ostrom.server.rules.fakes.RepositoryFixtures
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.infrastructure.NoRepositoryReader
import dev.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryAnalysisEngineTest {
    private val org = RulesFixtures.orgA
    private val reader = FakeRepositoryReader()
    private val logger = RecordingLogger()
    private val analysed = mutableListOf<Pair<String, List<String>>>()
    private var failure: Throwable? = null
    private val analyze = object : AnalyzeOrganizationUseCase {
        override suspend fun invoke(run: AnalysisRun, repos: List<RepositoryRef>) {
            failure?.let { throw it }
            analysed += run.id to repos.map { it.name }
        }
    }
    private val replayed = mutableListOf<String>()
    private val replay = object : ReplayRulesUseCase {
        override suspend fun invoke(run: AnalysisRun) {
            replayed += run.id
        }
    }
    private val engine = RepositoryAnalysisEngine(reader, analyze, replay, logger, capacity = 1)

    private fun run(id: String = "run_1", kind: AnalysisKind = AnalysisKind.ANALYSIS) = AnalysisRun(
        orgId = org,
        kind = kind,
        id = id,
        phase = AnalysisRun.CONNECT,
        progress = 0.0,
        failure = null,
        startedBy = "lyes",
        startedAt = RulesFixtures.epoch,
        updatedAt = RulesFixtures.epoch,
    )

    @Test
    fun aStartListsTheRepositoriesAtOnceAndQueuesTheRun() = runTest {
        reader.connect(org, RepositoryFixtures.api, RepositoryFixtures.web)
        assertEquals(AnalysisStart.Started, engine.start(run()))
        assertEquals(emptyList(), analysed, "Nothing analysed in the request")
        engine.runNext()
        assertEquals(listOf("run_1" to listOf("api", "web")), analysed)
    }

    @Test
    fun aRunThatCannotReadGitHubFailsAtOnceWithItsReason() = runTest {
        assertEquals(AnalysisStart.Unavailable("github", "Install the GitHub App."), engine.start(run()))
        reader.connect(org)
        assertEquals(
            AnalysisStart.Unavailable(
                "github",
                "The GitHub App sees no repository of this organization: grant it the repositories to analyse, then " +
                    "start again.",
            ),
            engine.start(run()),
        )
        reader.listingFailure = RepositoryReadException("GitHub did not answer.")
        assertEquals(AnalysisStart.Unavailable("github", "GitHub did not answer."), engine.start(run()))
        val none = RepositoryAnalysisEngine(NoRepositoryReader(), analyze, logger)
        assertEquals(AnalysisStart.Unavailable("github", NoRepositoryReader.REASON), none.start(run()))
    }

    @Test
    fun aReplayIsQueuedWithoutGitHubAndAFullQueueRefusesVisibly() = runTest {
        val busy = AnalysisStart.Unavailable(
            "engine",
            "Too many analyses are waiting on this server; start again in a minute.",
        )
        assertEquals(AnalysisStart.Started, engine.start(run("replay_1", AnalysisKind.REPLAY)))
        reader.connect(org, RepositoryFixtures.api)
        assertEquals(busy, engine.start(run("run_1")))
        assertEquals(busy, engine.start(run("replay_2", AnalysisKind.REPLAY)))
        engine.runNext()
        assertEquals(listOf("replay_1"), replayed, "The worker replays it")
        assertEquals(emptyList(), analysed, "And reads no repository for it")
        assertEquals(AnalysisStart.Started, engine.start(run("run_1")))
        engine.runNext()
        assertEquals(listOf("run_1" to listOf("api")), analysed)
    }

    @Test
    fun aRunThatThrowsNeverStopsTheWorkers() = runTest {
        reader.connect(org, RepositoryFixtures.api)
        val worker = launch { engine.work() }
        failure = IllegalStateException("database down")
        engine.start(run("run_1"))
        advanceUntilIdle()
        assertEquals(listOf(LogLevel.ERROR to "analysis worker caught a failure"), logger.events)
        failure = null
        engine.start(run("run_2"))
        advanceUntilIdle()
        assertEquals(listOf("run_2" to listOf("api")), analysed, "The worker goes on")
        worker.cancel()
    }

    @Test
    fun invokeWithCancellationPropagatesException() = runTest {
        reader.connect(org, RepositoryFixtures.api)
        engine.start(run())
        failure = CancellationException("stop")
        assertFailsWith<CancellationException> { engine.runNext() }
        assertEquals(emptyList(), logger.events)
    }

    @Test
    fun withoutAGitHubModuleNothingCanBeRead() = runTest {
        val none = NoRepositoryReader()
        val repo = RepositoryFixtures.ref("api")
        assertFailsWith<RepositoryReadException> { none.tree(org, repo) }
        assertFailsWith<RepositoryReadException> { none.file(org, repo, "CODEOWNERS") }
        val error = assertFailsWith<RepositoryReadException> { none.protection(org, repo) }
        assertEquals(NoRepositoryReader.REASON, error.message)
    }
}
