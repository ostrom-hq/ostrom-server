// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.billing.domain.BackfillActivePeopleUseCase
import run.ostrom.server.billing.domain.BackfillActivePeopleUseCaseImpl
import run.ostrom.server.billing.domain.BillingMetrics
import run.ostrom.server.billing.domain.BillingNotifier
import run.ostrom.server.billing.domain.BillingRepository
import run.ostrom.server.billing.domain.BillingSettings
import run.ostrom.server.billing.domain.ChangePlanUseCase
import run.ostrom.server.billing.domain.ChangePlanUseCaseImpl
import run.ostrom.server.billing.domain.CheckEntitlementUseCase
import run.ostrom.server.billing.domain.CheckEntitlementUseCaseImpl
import run.ostrom.server.billing.domain.EndDeletedOrgSubscriptionUseCase
import run.ostrom.server.billing.domain.EndDeletedOrgSubscriptionUseCaseImpl
import run.ostrom.server.billing.domain.GetAgentUsageUseCase
import run.ostrom.server.billing.domain.GetAgentUsageUseCaseImpl
import run.ostrom.server.billing.domain.GetBillingOverviewUseCase
import run.ostrom.server.billing.domain.GetBillingOverviewUseCaseImpl
import run.ostrom.server.billing.domain.GetPlanLimitsUseCase
import run.ostrom.server.billing.domain.GetPlanLimitsUseCaseImpl
import run.ostrom.server.billing.domain.HandleStripeEventUseCase
import run.ostrom.server.billing.domain.HandleStripeEventUseCaseImpl
import run.ostrom.server.billing.domain.LicenseProvider
import run.ostrom.server.billing.domain.ListInvoicesUseCase
import run.ostrom.server.billing.domain.ListInvoicesUseCaseImpl
import run.ostrom.server.billing.domain.NoLicenseProvider
import run.ostrom.server.billing.domain.NoMemberEmails
import run.ostrom.server.billing.domain.OpenCustomerPortalUseCase
import run.ostrom.server.billing.domain.OpenCustomerPortalUseCaseImpl
import run.ostrom.server.billing.domain.OutboxTask
import run.ostrom.server.billing.domain.ProcessOutboxUseCase
import run.ostrom.server.billing.domain.ProcessOutboxUseCaseImpl
import run.ostrom.server.billing.domain.RecordAgentUsageUseCase
import run.ostrom.server.billing.domain.RecordAgentUsageUseCaseImpl
import run.ostrom.server.billing.domain.RecordMemberActivityUseCase
import run.ostrom.server.billing.domain.RecordMemberActivityUseCaseImpl
import run.ostrom.server.billing.domain.SetCancellationUseCase
import run.ostrom.server.billing.domain.SetCancellationUseCaseImpl
import run.ostrom.server.billing.domain.StartCheckoutUseCase
import run.ostrom.server.billing.domain.StartCheckoutUseCaseImpl
import run.ostrom.server.billing.domain.StripeGateway
import run.ostrom.server.billing.domain.StripeSettings
import run.ostrom.server.billing.domain.SyncSubscriptionUseCase
import run.ostrom.server.billing.domain.SyncSubscriptionUseCaseImpl
import run.ostrom.server.billing.infrastructure.BillingMigrations
import run.ostrom.server.billing.infrastructure.BillingOrgDeletionListener
import run.ostrom.server.billing.infrastructure.BillingRepositoryImpl
import run.ostrom.server.billing.infrastructure.GuardedBillingNotifier
import run.ostrom.server.billing.infrastructure.LoggingBillingMetrics
import run.ostrom.server.billing.infrastructure.LoggingBillingNotifier
import run.ostrom.server.billing.infrastructure.StripeAccount
import run.ostrom.server.billing.infrastructure.StripeGatewayImpl
import run.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import run.ostrom.server.billing.presentation.BillingRoutesDependencies
import run.ostrom.server.billing.presentation.billingRoutes
import run.ostrom.server.kernel.auth.MemberEmails
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.settings.SettingsSection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.dsl.onClose

/** How long a Stripe call may take before it fails (and its outbox task is retried). */
private const val STRIPE_TIMEOUT_MILLIS = 30_000L
private val STRIPE_HTTP = named("billing.stripe.http")

/** The console's URL, which the server needs to start (the return pages of Checkout and the portal). */
private const val CONSOLE_URL = "OSTROM_CONSOLE_URL"
private const val DEFAULT_CONSOLE_URL = "https://app.ostrom.run"

/**
 * The billing module: Stripe subscriptions per active person, plan limits enforced on the server, cloud agent usage
 * and its alerts. Other modules depend on its use cases: orgs calls [CheckEntitlementUseCase] before adding a member
 * (the caller's role comes from the kernel's PrincipalOrgResolver); sessions and gates call
 * [RecordMemberActivityUseCase]; runners read [GetPlanLimitsUseCase] and [GetAgentUsageUseCase] before and while a
 * cloud agent runs, and add its time with [RecordAgentUsageUseCase]; the `ee/` license module binds [LicenseProvider].
 */
object BillingModule : ServerModule {
    override val name: String = "billing"

    override val koinModule: Module = module {
        // The Stripe account an administrator sets in the console (the admin module collects every bound section).
        single<SettingsSection>(named(StripeSettings.SECTION.name)) { StripeSettings.SECTION }
        single { StripeAccount(get()) }
        single {
            val account = get<StripeAccount>()
            BillingSettings(get<ConfigSource>()[CONSOLE_URL] ?: DEFAULT_CONSOLE_URL) { account.read() != null }
        }
        single<BillingRepository> { BillingRepositoryImpl(get()) }
        // Its own definition, so that Koin closes it with the application: its selector holds a thread of
        // Dispatchers.IO until then (StoppedApplicationsTest).
        single(STRIPE_HTTP) {
            HttpClient { install(HttpTimeout) { requestTimeoutMillis = STRIPE_TIMEOUT_MILLIS } }
        } onClose { it?.close() }
        single<StripeGateway> {
            val account = get<StripeAccount>()
            StripeGatewayImpl(get(STRIPE_HTTP), { account.read() })
        }
        single {
            val account = get<StripeAccount>()
            StripeSignatureVerifier({ account.read()?.webhookSecrets.orEmpty() }, get<Clock>())
        }
        single<GetPlanLimitsUseCase> { GetPlanLimitsUseCaseImpl(get(), licenses(), get(), get()) }
        single<GetBillingOverviewUseCase> { GetBillingOverviewUseCaseImpl(get(), get(), get(), get()) }
        single<StartCheckoutUseCase> {
            StartCheckoutUseCaseImpl(
                get(),
                get(),
                get(),
                get(),
                getOrNull<MemberEmails>() ?: NoMemberEmails,
                customerEmailFailureLogger(get()),
            )
        }
        single<OpenCustomerPortalUseCase> { OpenCustomerPortalUseCaseImpl(get(), get(), get()) }
        single<SyncSubscriptionUseCase> {
            SyncSubscriptionUseCaseImpl(get(), get(), get(), unknownPriceLogger(get()))
        }
        single<ChangePlanUseCase> { ChangePlanUseCaseImpl(get(), get(), get(), get(), get()) }
        single<SetCancellationUseCase> { SetCancellationUseCaseImpl(get(), get(), get(), get()) }
        single<EndDeletedOrgSubscriptionUseCase> { EndDeletedOrgSubscriptionUseCaseImpl(get(), get(), get(), get()) }
        // Named: every module binds its own listener, and the orgs module reads them all.
        single<OrgDeletionListener>(named("billing")) { BillingOrgDeletionListener(get()) }
        single<ListInvoicesUseCase> { ListInvoicesUseCaseImpl(get(), get()) }
        single<BackfillActivePeopleUseCase> { BackfillActivePeopleUseCaseImpl(get(), get()) }
        single<HandleStripeEventUseCase> {
            HandleStripeEventUseCaseImpl(get(), get(), notifier(), get(), metrics(), get())
        }
        single<RecordMemberActivityUseCase> { RecordMemberActivityUseCaseImpl(get()) }
        single<RecordAgentUsageUseCase> { RecordAgentUsageUseCaseImpl(get(), get(), notifier(), get()) }
        single<GetAgentUsageUseCase> { GetAgentUsageUseCaseImpl(get(), get()) }
        single<CheckEntitlementUseCase> { CheckEntitlementUseCaseImpl(get(), get(), get(), get()) }
        single<ProcessOutboxUseCase> {
            ProcessOutboxUseCaseImpl(get(), get(), get(), outboxFailureLogger(get()))
        }
        single {
            BillingRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get())
        }
    }

    override val migrations: List<SchemaMigration> = BillingMigrations

    override fun routes(route: Route, koin: Koin) {
        route.billingRoutes(koin.get())
    }

    /**
     * Billing, only while the instance's Stripe account is stored: without it the routes answer that nothing can be
     * bought.
     */
    override suspend fun features(koin: Koin): Set<String> =
        if (koin.get<BillingSettings>().stripeEnabled()) setOf(ServerFeatures.BILLING) else emptySet()

    /** The Stripe tasks' worker, which sends them while an account is stored (one can be saved at any time). */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val settings = koin.get<BillingSettings>()
        val worker = OutboxWorker(koin.get(), koin.get(), enabled = { settings.stripeEnabled() })
        scope.launch { worker.run() }
    }
}

// Ports other modules implement. Billing binds no default for them (Koin refuses overrides, docs/MODULES.md): it
// resolves them when present and falls back otherwise, so the notifications and `ee/` license modules bind them
// without a conflict.

/** Logs a failed Stripe task by its id, kind and error class, never the error's message. */
internal fun outboxFailureLogger(logger: Logger): (OutboxTask, Exception) -> Unit = { task, error ->
    val fields = mapOf("task_id" to task.id, "kind" to task.kind.key, "error" to error::class.simpleName)
    logger.warn("stripe task failed", fields)
}

/** Logs a customer whose address Stripe did not update, by its id and error class: Checkout goes on without it. */
internal fun customerEmailFailureLogger(logger: Logger): (String, Exception) -> Unit = { customerId, error ->
    val fields = mapOf("customer_id" to customerId, "error" to error::class.simpleName)
    logger.warn("stripe customer email not updated", fields)
}

/** Logs a subscription whose price is not in the catalog: it keeps its previous plan until someone looks. */
internal fun unknownPriceLogger(logger: Logger): (String, String?) -> Unit = { subscriptionId, lookupKey ->
    val fields = mapOf("subscription_id" to subscriptionId, "lookup_key" to lookupKey)
    logger.error("subscription price not in the catalog", fields)
}

/** The `ee/` license; without it, no license. */
private fun Scope.licenses(): LicenseProvider = getOrNull() ?: NoLicenseProvider()

/** The notifications module; without it, the alerts are logged. Either way, a failed notification is only logged. */
private fun Scope.metrics(): BillingMetrics = getOrNull() ?: LoggingBillingMetrics(get())

private fun Scope.notifier(): BillingNotifier =
    GuardedBillingNotifier(getOrNull() ?: LoggingBillingNotifier(get()), get())
