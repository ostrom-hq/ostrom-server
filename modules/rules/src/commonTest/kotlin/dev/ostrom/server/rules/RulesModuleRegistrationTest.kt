// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.gates.domain.repositories.GateHistory
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.RepositoryRef
import dev.ostrom.server.rules.domain.repositories.AnalysisRepository
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.services.AnalysisEngine
import dev.ostrom.server.rules.domain.services.AnalysisStart
import dev.ostrom.server.rules.domain.services.RepositoryReader
import dev.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCase
import dev.ostrom.server.rules.fakes.FakeAnalysisEngine
import dev.ostrom.server.rules.fakes.FakeAnalysisRepository
import dev.ostrom.server.rules.fakes.FakeGateHistory
import dev.ostrom.server.rules.fakes.FakeRepositoryReader
import dev.ostrom.server.rules.fakes.FakeRulesRepository
import dev.ostrom.server.rules.fakes.RecordingLogger
import dev.ostrom.server.rules.fakes.RepositoryFixtures
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.TestClock
import dev.ostrom.server.rules.infrastructure.NoRepositoryReader
import dev.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import dev.ostrom.server.secrets.domain.UnresolvedCallerResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class RulesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoadAndItsMigrations() {
        assertEquals("rules", RulesModule.name)
        assertEquals(listOf(1L, 2L, 3L), RulesModule.migrations.map { it.version })
        assertTrue(RulesModule.migrations.first().sql.contains("FORCE ROW LEVEL SECURITY"))
        koinApplication { modules(RulesModule.koinModule) }.close()
    }

    @Test
    fun theDeterministicEngineIsTheDefaultAndABoundEngineOrReaderIsKept() = runTest {
        val bare = koinApplication { modules(RulesModule.koinModule, kernel()) }
        RulesModule.initialize(bare.koin)
        assertIs<RepositoryAnalysisEngine>(bare.koin.get<AnalysisEngine>())
        assertIs<NoRepositoryReader>(bare.koin.get<RepositoryReader>())
        bare.close()

        val engine = FakeAnalysisEngine()
        val reader = FakeRepositoryReader()
        val bound = koinApplication {
            modules(
                module {
                    single<AnalysisEngine> { engine }
                    single<RepositoryReader> { reader }
                },
            )
        }
        RulesModule.initialize(bound.koin)
        assertSame(engine, bound.koin.get<AnalysisEngine>())
        assertSame(reader, bound.koin.get<RepositoryReader>())
        bound.close()
    }

    @Test
    fun startRunsTheWorkersThatAnalyseTheQueuedRuns() = runTest {
        val analysed = mutableListOf<String>()
        val reader = FakeRepositoryReader().apply { connect(RulesFixtures.orgA, RepositoryFixtures.api) }
        val app = koinApplication {
            modules(
                RulesModule.koinModule,
                kernel(),
                module {
                    single<RepositoryReader> { reader }
                    single<AnalyzeOrganizationUseCase> {
                        object : AnalyzeOrganizationUseCase {
                            override suspend fun invoke(run: AnalysisRun, repos: List<RepositoryRef>) {
                                analysed += run.id
                            }
                        }
                    }
                },
            )
        }
        val workers = Job()
        RulesModule.start(CoroutineScope(coroutineContext + workers), app.koin)
        val epoch = RulesFixtures.epoch
        val run = AnalysisRun(
            RulesFixtures.orgA,
            AnalysisKind.ANALYSIS,
            "run_1",
            "connect",
            0.0,
            null,
            "lyes",
            epoch,
            epoch,
        )
        assertEquals(AnalysisStart.Started, app.koin.get<RepositoryAnalysisEngine>().start(run))
        advanceUntilIdle()
        assertEquals(listOf("run_1"), analysed)
        assertEquals(RepositoryAnalysisEngine.WORKERS, workers.children.count())
        workers.cancel()
        app.close()
    }

    // The kernel's logger and clock and the stores the engine's definitions read (the gates' history is the gates
    // module's), without a database.
    private fun kernel() = module {
        single<Logger> { RecordingLogger() }
        single<Clock> { TestClock() }
        single<AnalysisRepository> { FakeAnalysisRepository() }
        single<RulesRepository> { FakeRulesRepository() }
        single<GateHistory> { FakeGateHistory() }
    }

    @Test
    fun withoutABoundResolverCallersResolveThroughTheKernelsOrgResolver() {
        val app = koinApplication {
            modules(
                module {
                    single<PrincipalOrgResolver> { RejectingPrincipalOrgResolver() }
                    single(named("callers")) { callerResolver() }
                },
            )
        }
        assertIs<OrgPrincipalCallerResolver>(app.koin.get<CallerResolver>(named("callers")))
        app.close()
    }

    @Test
    fun aBoundResolverWins() {
        val bound = UnresolvedCallerResolver()
        val app = koinApplication {
            modules(
                module {
                    single<CallerResolver> { bound }
                    single(named("callers")) { callerResolver() }
                },
            )
        }
        assertSame(bound, app.koin.get<CallerResolver>(named("callers")))
        app.close()
    }
}
