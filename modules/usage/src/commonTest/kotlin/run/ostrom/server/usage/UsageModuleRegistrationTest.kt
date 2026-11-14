// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.RejectingPrincipalOrgResolver
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import run.ostrom.server.secrets.domain.UnresolvedCallerResolver
import run.ostrom.server.usage.domain.PurgeUsageUseCase
import run.ostrom.server.usage.domain.UsageRepository
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class UsageModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("usage", UsageModule.name)
        koinApplication { modules(UsageModule.koinModule) }.close()
    }

    @Test
    fun thePurgeReadsTheRetentionTheConsoleStoresAtEachRun() = runTest {
        val repository = InMemoryUsageRepository()
        val clock = TestClock()
        var days = "30"
        val app = koinApplication {
            modules(
                UsageModule.koinModule,
                module {
                    single<UsageRepository> { repository }
                    single<Clock> { clock }
                    single<InstanceSettings> { InstanceSettings { SettingsValues(mapOf("retentionDays" to days)) } }
                },
            )
        }
        repository.events += modelEvent(1, at = TestCallers.epoch - 31.days)
        repository.events += modelEvent(2, at = TestCallers.epoch - 20.days)
        assertEquals(1L, app.koin.get<PurgeUsageUseCase>()())
        days = "10"
        assertEquals(1L, app.koin.get<PurgeUsageUseCase>()(), "The next run reads the new retention")
        assertEquals(listOf("usage"), app.koin.getAll<SettingsSection>().map { it.name })
        app.close()
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
