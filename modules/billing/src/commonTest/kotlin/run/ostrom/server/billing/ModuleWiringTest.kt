// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.BillingMetrics
import run.ostrom.server.billing.domain.BillingNotConfiguredException
import run.ostrom.server.billing.domain.BillingNotifier
import run.ostrom.server.billing.domain.BillingRepository
import run.ostrom.server.billing.domain.BillingSettings
import run.ostrom.server.billing.domain.EventOutcome
import run.ostrom.server.billing.domain.GetAgentUsageUseCase
import run.ostrom.server.billing.domain.GetPlanLimitsUseCase
import run.ostrom.server.billing.domain.HandleStripeEventUseCase
import run.ostrom.server.billing.domain.InvalidWebhookSignatureException
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
import run.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import run.ostrom.server.billing.infrastructure.hmacSha256Hex
import run.ostrom.server.kernel.auth.MemberEmails
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.kernel.settings.SettingsValues
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    stripe: Map<String, String>,
    ports: Module = module { },
    block: suspend (Koin) -> Unit,
) {
    val application = koinApplication {
        allowOverride(true)
        modules(
            BillingModule.koinModule,
            module {
                single<ConfigSource> { MapConfigSource(emptyMap()) }
                single<InstanceSettings> {
                    InstanceSettings { section ->
                        if (section == "stripe") SettingsValues(stripe) else SettingsValues.EMPTY
                    }
                }
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

private val CLOUD = mapOf("secretKey" to "sk_test_x", "webhookSecret" to "whsec_x")

class ModuleWiringTest {
    @Test
    fun onOstromCloudTheModuleTalksToStripeAndStartsItsWorker() = runTest {
        withBillingKoin(CLOUD) { koin -> checkCloud(koin, this) }
    }

    private suspend fun checkCloud(koin: Koin, scope: TestScope) {
        assertTrue(koin.get<BillingSettings>().stripeEnabled())
        assertEquals("https://app.ostrom.run", koin.get<BillingSettings>().consoleUrl)
        assertEquals(listOf("stripe"), koin.getAll<SettingsSection>().map { it.name })
        assertIs<StripeGatewayImpl>(koin.get<StripeGateway>())
        // The webhooks are verified with the stored secret.
        val payload = "{}"
        val signature = hmacSha256Hex("whsec_x", "${NOW.epochSeconds}.$payload".encodeToByteArray())
        koin.get<StripeSignatureVerifier>().verify(payload, "t=${NOW.epochSeconds},v1=$signature")
        assertEquals(0, koin.get<ProcessOutboxUseCase>()(10))
        // The orgs module reads every module's listener: billing's is bound under its own name.
        assertIs<BillingOrgDeletionListener>(koin.getAll<OrgDeletionListener>().single())
        BillingModule.start(scope.backgroundScope, koin)
    }

    @Test
    fun selfHostedTheModuleSendsNothingToStripeAndHasNoLicenseByDefault() = runTest {
        val scope = this
        withBillingKoin(emptyMap()) { koin ->
            assertFalse(koin.get<BillingSettings>().stripeEnabled())
            assertFailsWith<BillingNotConfiguredException> { koin.get<StripeGateway>().customerEmail("cus_1") }
            assertFailsWith<InvalidWebhookSignatureException> {
                koin.get<StripeSignatureVerifier>().verify("{}", "t=${NOW.epochSeconds},v1=00")
            }
            BillingModule.start(scope.backgroundScope, koin)
            scope.testScheduler.runCurrent()
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
