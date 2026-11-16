// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.DirectiveRepository
import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.PolicyModelAccess
import run.ostrom.server.gateway.domain.SpendDecision
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.UpdateDirectivesUseCase
import run.ostrom.server.kernel.auth.ModelAccess
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.sharing.domain.AccountGroups
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.MemberPoolRepository
import run.ostrom.server.sharing.domain.NoAccountGroups
import run.ostrom.server.sharing.domain.NoMemberPools
import run.ostrom.server.sharing.domain.NoQuotaHistory
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.domain.SharingOrgs
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class GatewayModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("gateway", GatewayModule.name)
        koinApplication { modules(GatewayModule.koinModule) }.close()
    }

    @Test
    fun itBindsTheModelAccessALaunchReads() {
        val settings = module { single<GatewaySettingsRepository> { InMemorySettings() } }
        val app = koinApplication { modules(GatewayModule.koinModule, settings) }
        assertIs<PolicyModelAccess>(app.koin.get<ModelAccess>())
        app.close()
    }

    @Test
    fun theCapacityControllerLoadsWithOrWithoutTheOptionalSourcesAndPassesEachMinute() = runTest {
        var passes = 0
        val required = module {
            single<SharedAccountRepository> { InMemoryAccounts() }
            single<AccountSlotRepository> { InMemorySlots() }
            single<GatewaySettingsRepository> { InMemorySettings() }
            single<DirectiveRepository> { InMemoryDirectives() }
            single<Clock> { TestClock() }
            single<SharingOrgs> {
                SharingOrgs {
                    passes++
                    emptyList()
                }
            }
            single<Logger> {
                object : Logger {
                    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) =
                        Unit

                    override fun with(fields: Map<String, Any?>): Logger = this
                }
            }
        }
        val optional = module {
            single<QuotaHistoryRepository> { NoQuotaHistory }
            single<AccountGroups> { NoAccountGroups }
            single<MemberPoolRepository> { NoMemberPools }
            single<SpendGuard> { SpendGuard { SpendDecision.Allowed } }
        }
        koinApplication { modules(GatewayModule.koinModule, required) }.let {
            assertIs<UpdateDirectivesUseCase>(it.koin.get<UpdateDirectivesUseCase>())
            it.close()
        }
        val spend = SpendGuard { SpendDecision.Allowed }
        val member = Gw.memberA.memberId
        assertTrue(spend.available(Gw.orgA, member), "A guard that only checks calls never pauses a member")
        val app = koinApplication { modules(GatewayModule.koinModule, required, optional) }
        assertIs<UpdateDirectivesUseCase>(app.koin.get<UpdateDirectivesUseCase>())
        GatewayModule.start(backgroundScope, app.koin)
        runCurrent()
        advanceTimeBy(1.minutes + 1.milliseconds)
        assertTrue(passes >= 2, "A pass each minute")
        app.close()
    }
}
