// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import dev.ostrom.server.rules.domain.services.AnalysisEngine
import dev.ostrom.server.rules.fakes.FakeAnalysisEngine
import dev.ostrom.server.rules.infrastructure.NoAnalysisEngine
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import dev.ostrom.server.secrets.domain.UnresolvedCallerResolver
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RulesModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoadAndItsMigrations() {
        assertEquals("rules", RulesModule.name)
        assertEquals(listOf(1L, 2L, 3L), RulesModule.migrations.map { it.version })
        assertTrue(RulesModule.migrations.first().sql.contains("FORCE ROW LEVEL SECURITY"))
        koinApplication { modules(RulesModule.koinModule) }.close()
    }

    @Test
    fun withoutAnEngineEveryRunIsRefusedAndABoundEngineIsKept() = runTest {
        val bare = koinApplication { }
        RulesModule.initialize(bare.koin)
        assertIs<NoAnalysisEngine>(bare.koin.get<AnalysisEngine>())
        bare.close()

        val engine = FakeAnalysisEngine()
        val bound = koinApplication { modules(module { single<AnalysisEngine> { engine } }) }
        RulesModule.initialize(bound.koin)
        assertSame(engine, bound.koin.get<AnalysisEngine>())
        bound.close()
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
