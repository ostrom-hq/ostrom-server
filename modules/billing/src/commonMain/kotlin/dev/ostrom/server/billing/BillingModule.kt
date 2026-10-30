// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BackfillActivePeopleUseCase
import dev.ostrom.server.billing.domain.BackfillActivePeopleUseCaseImpl
import dev.ostrom.server.billing.domain.BillingMetrics
import dev.ostrom.server.billing.domain.BillingNotifier
import dev.ostrom.server.billing.domain.BillingRepository
import dev.ostrom.server.billing.domain.BillingSettings
import dev.ostrom.server.billing.domain.ChangePlanUseCase
import dev.ostrom.server.billing.domain.ChangePlanUseCaseImpl
import dev.ostrom.server.billing.domain.CheckEntitlementUseCase
import dev.ostrom.server.billing.domain.CheckEntitlementUseCaseImpl
import dev.ostrom.server.billing.domain.GetBillingOverviewUseCase
import dev.ostrom.server.billing.domain.GetAgentUsageUseCase
import dev.ostrom.server.billing.domain.GetAgentUsageUseCaseImpl
import dev.ostrom.server.billing.domain.GetBillingOverviewUseCaseImpl
import dev.ostrom.server.billing.domain.GetPlanLimitsUseCase
import dev.ostrom.server.billing.domain.GetPlanLimitsUseCaseImpl
import dev.ostrom.server.billing.domain.HandleStripeEventUseCase
import dev.ostrom.server.billing.domain.HandleStripeEventUseCaseImpl
import dev.ostrom.server.billing.domain.LicenseProvider
import dev.ostrom.server.billing.domain.ListInvoicesUseCase
import dev.ostrom.server.billing.domain.ListInvoicesUseCaseImpl
import dev.ostrom.server.billing.domain.NoLicenseProvider
import dev.ostrom.server.billing.domain.OpenCustomerPortalUseCase
import dev.ostrom.server.billing.domain.OpenCustomerPortalUseCaseImpl
import dev.ostrom.server.billing.domain.OutboxTask
import dev.ostrom.server.billing.domain.ProcessOutboxUseCase
import dev.ostrom.server.billing.domain.ProcessOutboxUseCaseImpl
import dev.ostrom.server.billing.domain.RecordAgentUsageUseCase
import dev.ostrom.server.billing.domain.RecordAgentUsageUseCaseImpl
import dev.ostrom.server.billing.domain.RecordMemberActivityUseCase
import dev.ostrom.server.billing.domain.RecordMemberActivityUseCaseImpl
import dev.ostrom.server.billing.domain.SetCancellationUseCase
import dev.ostrom.server.billing.domain.SetCancellationUseCaseImpl
import dev.ostrom.server.billing.domain.StartCheckoutUseCase
import dev.ostrom.server.billing.domain.StartCheckoutUseCaseImpl
import dev.ostrom.server.billing.domain.StripeGateway
import dev.ostrom.server.billing.domain.SyncSubscriptionUseCase
import dev.ostrom.server.billing.domain.SyncSubscriptionUseCaseImpl
import dev.ostrom.server.billing.infrastructure.BillingMigrations
import dev.ostrom.server.billing.infrastructure.BillingRepositoryImpl
import dev.ostrom.server.billing.infrastructure.GuardedBillingNotifier
import dev.ostrom.server.billing.infrastructure.LoggingBillingMetrics
import dev.ostrom.server.billing.infrastructure.LoggingBillingNotifier
import dev.ostrom.server.billing.infrastructure.StripeConfig
import dev.ostrom.server.billing.infrastructure.StripeGatewayImpl
import dev.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import dev.ostrom.server.billing.presentation.BillingRoutesDependencies
import dev.ostrom.server.billing.presentation.billingRoutes
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Clock

/** How long a Stripe call may take before it fails (and its outbox task is retried). */
private const val STRIPE_TIMEOUT_MILLIS = 30_000L

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
        single { BillingConfig.load(get<ConfigSource>()) }
        single { BillingSettings(get<BillingConfig>().stripeSecretKey != null, get<BillingConfig>().consoleUrl) }
        single<BillingRepository> { BillingRepositoryImpl(get()) }
        single<StripeGateway> {
            val config = get<BillingConfig>()
            val client = HttpClient { install(HttpTimeout) { requestTimeoutMillis = STRIPE_TIMEOUT_MILLIS } }
            StripeGatewayImpl(client, StripeConfig(config.stripeSecretKey.orEmpty(), config.stripeApiBase))
        }
        single { StripeSignatureVerifier(get<BillingConfig>().webhookSecrets, get<Clock>()) }
        single<GetPlanLimitsUseCase> { GetPlanLimitsUseCaseImpl(get(), licenses(), get(), get()) }
        single<GetBillingOverviewUseCase> { GetBillingOverviewUseCaseImpl(get(), get(), get(), get()) }
        single<StartCheckoutUseCase> { StartCheckoutUseCaseImpl(get(), get(), get(), get()) }
        single<OpenCustomerPortalUseCase> { OpenCustomerPortalUseCaseImpl(get(), get(), get()) }
        single<SyncSubscriptionUseCase> {
            SyncSubscriptionUseCaseImpl(get(), get(), get(), unknownPriceLogger(get()))
        }
        single<ChangePlanUseCase> { ChangePlanUseCaseImpl(get(), get(), get(), get(), get()) }
        single<SetCancellationUseCase> { SetCancellationUseCaseImpl(get(), get(), get(), get()) }
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

    override fun start(scope: CoroutineScope, koin: Koin) {
        if (!koin.get<BillingSettings>().stripeEnabled) return
        val worker = OutboxWorker(koin.get(), koin.get())
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
