// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import run.ostrom.server.secrets.domain.UnresolvedCallerResolver
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BudgetsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("budgets", BudgetsModule.name)
        koinApplication { modules(BudgetsModule.koinModule) }.close()
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
