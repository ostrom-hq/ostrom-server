package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BackfillActivePeopleUseCase
import dev.ostrom.server.billing.domain.BackfillActivePeopleUseCaseImpl
import dev.ostrom.server.billing.domain.BillingAccessPolicy
import dev.ostrom.server.billing.domain.BillingNotifier
import dev.ostrom.server.billing.domain.BillingRepository
import dev.ostrom.server.billing.domain.BillingSettings
import dev.ostrom.server.billing.domain.ChangePlanUseCase
import dev.ostrom.server.billing.domain.ChangePlanUseCaseImpl
import dev.ostrom.server.billing.domain.CheckEntitlementUseCase
import dev.ostrom.server.billing.domain.CheckEntitlementUseCaseImpl
import dev.ostrom.server.billing.domain.DenyingBillingAccessPolicy
import dev.ostrom.server.billing.domain.GetBillingOverviewUseCase
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
import dev.ostrom.server.billing.infrastructure.LoggingBillingNotifier
import dev.ostrom.server.billing.infrastructure.StripeConfig
import dev.ostrom.server.billing.infrastructure.StripeGatewayImpl
import dev.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import dev.ostrom.server.billing.presentation.BillingRoutesDependencies
import dev.ostrom.server.billing.presentation.billingRoutes
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
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

/**
 * The billing module: Stripe subscriptions per active person, plan limits enforced on the server, cloud agent usage
 * and its alerts. Other modules depend on its use cases: orgs calls [CheckEntitlementUseCase] before adding a member
 * and binds [BillingAccessPolicy]; sessions and gates call [RecordMemberActivityUseCase]; runners call
 * [CheckEntitlementUseCase] before a cloud agent and [RecordAgentUsageUseCase] after; the `ee/` license module
 * binds [LicenseProvider].
 */
object BillingModule : ServerModule {
    override val name: String = "billing"

    override val koinModule: Module = module {
        single { BillingConfig.load(getOrNull<ConfigSource>() ?: EnvironmentConfigSource()) }
        single { BillingSettings(get<BillingConfig>().stripeSecretKey != null, get<BillingConfig>().consoleUrl) }
        single<BillingRepository> { BillingRepositoryImpl(get()) }
        single<StripeGateway> {
            val config = get<BillingConfig>()
            val client = HttpClient { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }
            StripeGatewayImpl(client, StripeConfig(config.stripeSecretKey.orEmpty(), config.stripeApiBase))
        }
        single { StripeSignatureVerifier(get<BillingConfig>().webhookSecrets, get<Clock>()) }
        single<GetPlanLimitsUseCase> { GetPlanLimitsUseCaseImpl(get(), licenses(), get(), get()) }
        single<GetBillingOverviewUseCase> { GetBillingOverviewUseCaseImpl(get(), access(), get(), get(), get()) }
        single<StartCheckoutUseCase> { StartCheckoutUseCaseImpl(get(), access(), get(), get(), get()) }
        single<OpenCustomerPortalUseCase> { OpenCustomerPortalUseCaseImpl(get(), access(), get(), get()) }
        single<SyncSubscriptionUseCase> { SyncSubscriptionUseCaseImpl(get(), get(), get()) }
        single<ChangePlanUseCase> { ChangePlanUseCaseImpl(get(), access(), get(), get(), get(), get()) }
        single<SetCancellationUseCase> { SetCancellationUseCaseImpl(get(), access(), get(), get(), get()) }
        single<ListInvoicesUseCase> { ListInvoicesUseCaseImpl(get(), access(), get()) }
        single<BackfillActivePeopleUseCase> { BackfillActivePeopleUseCaseImpl(get(), get()) }
        single<HandleStripeEventUseCase> { HandleStripeEventUseCaseImpl(get(), get(), notifier(), get(), get()) }
        single<RecordMemberActivityUseCase> { RecordMemberActivityUseCaseImpl(get()) }
        single<RecordAgentUsageUseCase> { RecordAgentUsageUseCaseImpl(get(), get(), notifier(), get()) }
        single<CheckEntitlementUseCase> { CheckEntitlementUseCaseImpl(get(), get(), get(), get()) }
        single<ProcessOutboxUseCase> {
            val logger = get<Logger>()
            ProcessOutboxUseCaseImpl(get(), get(), get()) { task, error ->
                logger.warn("stripe task failed", mapOf("task_id" to task.id, "kind" to task.kind.key, "error" to error::class.simpleName))
            }
        }
        single {
            BillingRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get())
        }
    }

    override val migrations: List<SchemaMigration> = BillingMigrations

    override fun routes(route: Route, koin: Koin) = route.billingRoutes(koin.get())

    override fun start(scope: CoroutineScope, koin: Koin) {
        if (!koin.get<BillingSettings>().stripeEnabled) return
        val worker = OutboxWorker(koin.get(), koin.get())
        scope.launch { worker.run() }
    }
}

// Ports other modules implement. Billing binds no default for them (Koin refuses overrides, docs/MODULES.md): it
// resolves them when present and falls back otherwise, so the orgs, notifications and `ee/` license modules bind
// them without a conflict.

/** The orgs module's policy; without it nobody may see or change a billing. */
private fun Scope.access(): BillingAccessPolicy = getOrNull() ?: DenyingBillingAccessPolicy()

/** The `ee/` license; without it, no license. */
private fun Scope.licenses(): LicenseProvider = getOrNull() ?: NoLicenseProvider()

/** The notifications module; without it, the alerts are logged. */
private fun Scope.notifier(): BillingNotifier = getOrNull() ?: LoggingBillingNotifier(get())
