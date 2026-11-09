// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.BillingMetrics
import run.ostrom.server.billing.domain.BillingNotifier
import run.ostrom.server.billing.domain.BillingRepository
import run.ostrom.server.billing.domain.BillingSettings
import run.ostrom.server.billing.domain.EventOutcome
import run.ostrom.server.billing.domain.GetAgentUsageUseCase
import run.ostrom.server.billing.domain.GetPlanLimitsUseCase
import run.ostrom.server.billing.domain.HandleStripeEventUseCase
import run.ostrom.server.billing.domain.LicenseGrant
import run.ostrom.server.billing.domain.LicenseProvider
import run.ostrom.server.billing.domain.ProcessOutboxUseCase
import run.ostrom.server.billing.domain.RecordAgentUsageUseCase
import run.ostrom.server.billing.domain.StartCheckoutUseCase
import run.ostrom.server.billing.domain.StripeEvent
import run.ostrom.server.billing.domain.StripeGateway
import run.ostrom.server.billing.fakes.FakeBillingRepository
import run.ostrom.server.billing.fakes.FakeLicenseProvider
import run.ostrom.server.billing.fakes.FakeMemberEmails
import run.ostrom.server.billing.fakes.FakeNotifier
import run.ostrom.server.billing.fakes.FixedClock
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.infrastructure.BillingOrgDeletionListener
import run.ostrom.server.billing.infrastructure.StripeGatewayImpl
import run.ostrom.server.kernel.auth.MemberEmails
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.OrgDeletionListener
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val ORG = Uuid.parse("0195f7a2-0000-7000-8000-000000000001")

/**
 * Runs [block] on the module's own bindings, on fakes for what the kernel and other modules provide. The Koin is
 * closed after: a module keeps its singletons until then, and the next test would read this one's.
 */
private suspend fun withBillingKoin(
    config: Map<String, String>,
    ports: Module = module { },
    block: suspend (Koin) -> Unit,
) {
    val application = koinApplication {
        allowOverride(true)
        modules(
            BillingModule.koinModule,
            module {
                single<ConfigSource> { MapConfigSource(config) }
                single<Logger> { JsonLogger(LogLevel.ERROR) }
                single<Clock> { FixedClock() }
                single<BillingRepository> { FakeBillingRepository() }
            },
            ports,
        )
    }
    try {
        block(application.koin)
    } finally {
        application.close()
    }
}

private val CLOUD = mapOf("OSTROM_STRIPE_SECRET_KEY" to "sk_test_x", "OSTROM_STRIPE_WEBHOOK_SECRET" to "whsec_x")

class ModuleWiringTest {
    @Test
    fun onOstromCloudTheModuleTalksToStripeAndStartsItsWorker() = runTest {
        withBillingKoin(CLOUD) { koin -> checkCloud(koin, this) }
    }

    private suspend fun checkCloud(koin: Koin, scope: TestScope) {
        assertTrue(koin.get<BillingSettings>().stripeEnabled)
        assertIs<StripeGatewayImpl>(koin.get<StripeGateway>())
        assertEquals(0, koin.get<ProcessOutboxUseCase>()(10))
        // The orgs module reads every module's listener: billing's is bound under its own name.
        assertIs<BillingOrgDeletionListener>(koin.getAll<OrgDeletionListener>().single())
        BillingModule.start(scope.backgroundScope, koin)
    }

    @Test
    fun selfHostedTheModuleStartsNoWorkerAndHasNoLicenseByDefault() = runTest {
        val scope = this
        withBillingKoin(emptyMap()) { koin ->
            assertFalse(koin.get<BillingSettings>().stripeEnabled)
            BillingModule.start(scope.backgroundScope, koin)
            assertEquals(null, koin.get<GetPlanLimitsUseCase>()(ORG).maxPeople, "No license: no people limit")
            koin.get<HandleStripeEventUseCase>()
            koin.get<RecordAgentUsageUseCase>()
            // Without the orgs module, nobody's address is known: Checkout asks for it.
            koin.get<StartCheckoutUseCase>()
            assertEquals(0L, koin.get<GetAgentUsageUseCase>()(ORG).agentSeconds)
        }
    }

    @Test
    fun theLicenseNotificationAndObservabilityModulesAreUsedWhenTheyAreBound() = runTest {
        val license = FakeLicenseProvider(LicenseGrant(emptySet(), maxPeople = 40, expiresAt = null))
        val metrics = FakeBillingMetrics()
        val ports = module {
            single<LicenseProvider> { license }
            single<BillingNotifier> { FakeNotifier() }
            single<BillingMetrics> { metrics }
            single<MemberEmails> { FakeMemberEmails() }
        }
        withBillingKoin(emptyMap(), ports) { koin ->
            assertEquals(40, koin.get<GetPlanLimitsUseCase>()(ORG).maxPeople)
            val unknown = StripeEvent("evt_1", "invoice.paid", NOW, null, "sub_1", ORG.toString(), null)
            assertEquals(EventOutcome.IGNORED_UNKNOWN_ORG, koin.get<HandleStripeEventUseCase>()(unknown))
            assertEquals(listOf("evt_1 invoice.paid"), metrics.unknownOrganizations)
            koin.get<RecordAgentUsageUseCase>()
            // With the orgs module, Checkout reads the payer's verified address.
            koin.get<StartCheckoutUseCase>()
        }
    }
}
