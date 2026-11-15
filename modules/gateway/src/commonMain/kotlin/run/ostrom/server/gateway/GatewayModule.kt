// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.core.telemetry.Telemetry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.ApiPriceRepository
import run.ostrom.server.gateway.domain.AllowAllSpend
import run.ostrom.server.gateway.domain.AwaitAdmissionUseCase
import run.ostrom.server.gateway.domain.AwaitAdmissionUseCaseImpl
import run.ostrom.server.gateway.domain.BudgetCheck
import run.ostrom.server.gateway.domain.CancelInferenceRequestUseCase
import run.ostrom.server.gateway.domain.CancelInferenceRequestUseCaseImpl
import run.ostrom.server.gateway.domain.CancellationWatch
import run.ostrom.server.gateway.domain.DatabaseOrgPrices
import run.ostrom.server.gateway.domain.DirectiveRepository
import run.ostrom.server.gateway.domain.DirectiveUseCases
import run.ostrom.server.gateway.domain.DirectiveUseCasesImpl
import run.ostrom.server.gateway.domain.GatewayInstanceSettings
import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.GetApiPricesUseCase
import run.ostrom.server.gateway.domain.GetApiPricesUseCaseImpl
import run.ostrom.server.gateway.domain.GetGatewaySettingsUseCase
import run.ostrom.server.gateway.domain.GetGatewaySettingsUseCaseImpl
import run.ostrom.server.gateway.domain.GetInferenceQueueUseCase
import run.ostrom.server.gateway.domain.GetInferenceQueueUseCaseImpl
import run.ostrom.server.gateway.domain.GetModelPolicyUseCase
import run.ostrom.server.gateway.domain.GetModelPolicyUseCaseImpl
import run.ostrom.server.gateway.domain.GetPacingSettingsUseCase
import run.ostrom.server.gateway.domain.GetPacingSettingsUseCaseImpl
import run.ostrom.server.gateway.domain.InferencePlanner
import run.ostrom.server.gateway.domain.InferenceUpstream
import run.ostrom.server.gateway.domain.LoadApiPriceDefaultsUseCase
import run.ostrom.server.gateway.domain.LoadApiPriceDefaultsUseCaseImpl
import run.ostrom.server.gateway.domain.ModelCallObserver
import run.ostrom.server.gateway.domain.ModelUsageSink
import run.ostrom.server.gateway.domain.NoModelCallObserver
import run.ostrom.server.gateway.domain.NoModelUsageSink
import run.ostrom.server.gateway.domain.OrgPrices
import run.ostrom.server.gateway.domain.PacingSettingsRepository
import run.ostrom.server.gateway.domain.PacingSources
import run.ostrom.server.gateway.domain.PolicyModelAccess
import run.ostrom.server.gateway.domain.PutApiPricesUseCase
import run.ostrom.server.gateway.domain.PutApiPricesUseCaseImpl
import run.ostrom.server.gateway.domain.PutGatewaySettingsUseCase
import run.ostrom.server.gateway.domain.PutGatewaySettingsUseCaseImpl
import run.ostrom.server.gateway.domain.PutModelPolicyUseCase
import run.ostrom.server.gateway.domain.PutModelPolicyUseCaseImpl
import run.ostrom.server.gateway.domain.RelayInferenceUseCase
import run.ostrom.server.gateway.domain.RelayInferenceUseCaseImpl
import run.ostrom.server.gateway.domain.RequestCancellationRepository
import run.ostrom.server.gateway.domain.RunPacingControllerUseCase
import run.ostrom.server.gateway.domain.SetPacingSettingsUseCase
import run.ostrom.server.gateway.domain.SetPacingSettingsUseCaseImpl
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.UpdateDirectivesUseCase
import run.ostrom.server.gateway.domain.UpdateDirectivesUseCaseImpl
import run.ostrom.server.gateway.infrastructure.AdmissionQueueRepositoryImpl
import run.ostrom.server.gateway.infrastructure.ApiPriceRepositoryImpl
import run.ostrom.server.gateway.infrastructure.DirectiveRepositoryImpl
import run.ostrom.server.gateway.infrastructure.GatewayMigrations
import run.ostrom.server.gateway.infrastructure.GatewaySettingsRepositoryImpl
import run.ostrom.server.gateway.infrastructure.KtorInferenceUpstream
import run.ostrom.server.gateway.infrastructure.MeteredModelCallObserver
import run.ostrom.server.gateway.infrastructure.PacingSettingsRepositoryImpl
import run.ostrom.server.gateway.infrastructure.RequestCancellationRepositoryImpl
import run.ostrom.server.gateway.infrastructure.SpanModelCallTracer
import run.ostrom.server.gateway.presentation.GatewayGovernanceChange
import run.ostrom.server.gateway.presentation.GatewayRoutesDependencies
import run.ostrom.server.gateway.presentation.PacingRoutesDependencies
import run.ostrom.server.gateway.presentation.gatewayRoutes
import run.ostrom.server.gateway.presentation.pacingRoutes
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.ModelAccess
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.ScheduledJob
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.repeatDue
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import run.ostrom.server.sharing.domain.AccountGroups
import run.ostrom.server.sharing.domain.NoAccountGroups
import run.ostrom.server.sharing.domain.NoQuotaHistory
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The gateway module: agents send their model calls to Ostrom; the gateway serves them with the org's shared
 * subscription accounts or its provider keys, streams the answer back untouched, and records usage. Budgets, the usage
 * ledger and traces are ports ([SpendGuard], [ModelUsageSink], [ModelCallObserver]) bound by the modules that own
 * them; unbound, every call is allowed, and nothing is recorded or traced. It binds [ModelAccess] with the org's model
 * policy, which a launch reads before it starts.
 */
object GatewayModule : ServerModule {
    override val name: String = "gateway"

    /** The Koin name of the providers' HTTP client, replaced by tests. */
    val HTTP_CLIENT = named("gatewayHttpClient")

    private const val CONNECT_TIMEOUT_MS = 10_000L

    /** How long a provider may stay silent: the socket timeout of engines that apply it, and the gateway's own cut. */
    private const val SILENCE_MS = 300_000L

    override val koinModule: Module = module {
        // The instance's provider keys and URLs an administrator sets in the console (the admin module collects
        // every bound section).
        single<SettingsSection>(named(GatewayInstanceSettings.SECTION.name)) { GatewayInstanceSettings.SECTION }
        single(HTTP_CLIENT) {
            HttpClient {
                followRedirects = false
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SILENCE_MS
                }
            }
        } onClose { it?.close() } // Its selector holds a thread of Dispatchers.IO until then (StoppedApplicationsTest).
        single<GatewaySettingsRepository> { GatewaySettingsRepositoryImpl(get<JdbcDatabase>()) }
        single<InferenceUpstream> { KtorInferenceUpstream(get(HTTP_CLIENT), SILENCE_MS.milliseconds) }
        single<ApiPriceRepository> { ApiPriceRepositoryImpl(get<JdbcDatabase>()) }
        single<OrgPrices> { DatabaseOrgPrices(get(), get()) }
        single {
            val instance = get<InstanceSettings>()
            InferencePlanner(
                settings = get(),
                pool = get(),
                revealSecrets = get(),
                spend = BudgetCheck(getOrNull<SpendGuard>() ?: AllowAllSpend, get()),
                instance = { GatewayInstanceSettings.providers(instance.read(GatewayInstanceSettings.SECTION.name)) },
            )
        }
        single<AdmissionQueueRepository> { AdmissionQueueRepositoryImpl(get<JdbcDatabase>()) }
        single<AwaitAdmissionUseCase> { AwaitAdmissionUseCaseImpl(get(), get()) }
        single<GetInferenceQueueUseCase> { GetInferenceQueueUseCaseImpl(get()) }
        single<RequestCancellationRepository> { RequestCancellationRepositoryImpl(get<JdbcDatabase>()) }
        single<CancelInferenceRequestUseCase> { CancelInferenceRequestUseCaseImpl(get()) }
        single<RelayInferenceUseCase> {
            RelayInferenceUseCaseImpl(
                planner = get(),
                upstream = get(),
                prices = get(),
                usage = getOrNull<ModelUsageSink>() ?: NoModelUsageSink,
                observer = MeteredModelCallObserver(get(), getOrNull<ModelCallObserver>() ?: NoModelCallObserver),
                clock = get(),
                tracer = SpanModelCallTracer(get<Telemetry>().tracer),
                admission = get(),
                cancellations = CancellationWatch(get()),
            )
        }
        single<PacingSettingsRepository> { PacingSettingsRepositoryImpl(get<JdbcDatabase>()) }
        single<DirectiveRepository> { DirectiveRepositoryImpl(get<JdbcDatabase>()) }
        single<GetPacingSettingsUseCase> { GetPacingSettingsUseCaseImpl(get()) }
        single<SetPacingSettingsUseCase> { SetPacingSettingsUseCaseImpl(get()) }
        single<DirectiveUseCases> { DirectiveUseCasesImpl(get(), get()) }
        single<UpdateDirectivesUseCase> {
            val sources = PacingSources(
                accounts = get(),
                history = getOrNull<QuotaHistoryRepository>() ?: NoQuotaHistory,
                slots = get(),
                groups = getOrNull<AccountGroups>() ?: NoAccountGroups,
                settings = get(),
                spend = getOrNull<SpendGuard>() ?: AllowAllSpend,
            )
            UpdateDirectivesUseCaseImpl(sources, get(), get())
        }
        single { RunPacingControllerUseCase(get(), get()) }
        single {
            PacingRoutesDependencies(
                callers = getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()),
                getSettings = get(),
                setSettings = get(),
                directives = get(),
                audit = get(),
            )
        }
        single<GetGatewaySettingsUseCase> { GetGatewaySettingsUseCaseImpl(get()) }
        single<PutGatewaySettingsUseCase> { PutGatewaySettingsUseCaseImpl(get()) }
        single<GetModelPolicyUseCase> { GetModelPolicyUseCaseImpl(get()) }
        single<GetApiPricesUseCase> { GetApiPricesUseCaseImpl(get()) }
        single<PutApiPricesUseCase> { PutApiPricesUseCaseImpl(get()) }
        single<LoadApiPriceDefaultsUseCase> { LoadApiPriceDefaultsUseCaseImpl(get()) }
        single<ModelAccess> { PolicyModelAccess(get()) }
        single<PutModelPolicyUseCase> { PutModelPolicyUseCaseImpl(get()) }
        single<GovernanceChange>(named("gateway")) {
            GatewayGovernanceChange(get(), get<GatewayRoutesDependencies>().callers, get(), get())
        }
        single {
            GatewayRoutesDependencies(
                tokens = get(),
                callers = getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()),
                relay = get(),
                getSettings = get(),
                putSettings = get(),
                getPolicy = get(),
                putPolicy = get(),
                clock = get(),
                audit = get(),
                getQueue = get(),
                cancel = get(),
                getApiPrices = get(),
                putApiPrices = get(),
                loadApiPriceDefaults = get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = GatewayMigrations

    override fun routes(route: Route, koin: Koin) {
        route.gatewayRoutes(koin.get())
        route.pacingRoutes(koin.get())
    }

    /** The capacity controller's pass, each minute, one replica at a time (TeamCode's maintenance `directives.update`). */
    val PACING_CONTROLLER: ScheduledJob = ScheduledJob("gateway/pacing", 1.minutes, 45.seconds)

    // Each minute, the controller advises every member with a recent session; a failed pass is logged and tried again
    // the next minute, it never stops the server.
    override fun start(scope: CoroutineScope, koin: Koin) {
        val controller = koin.get<RunPacingControllerUseCase>()
        val logger = koin.get<Logger>()
        val work = koin.scheduledWork()
        scope.launch {
            work.repeatDue(PACING_CONTROLLER, logger, "pacing controller pass failed") { controller() }
        }
    }

    /**
     * The inference gateway, its admission queue, the cancellation of a call, the org's price table and the capacity
     * controller's pacing: an org configures its own provider keys, so all are served.
     */
    override suspend fun features(koin: Koin): Set<String> =
        setOf(
            ServerFeatures.INFERENCE,
            ServerFeatures.INFERENCE_QUEUE,
            ServerFeatures.INFERENCE_CANCEL,
            ServerFeatures.CAPACITY_API_PRICES,
            ServerFeatures.CAPACITY_PACING,
        )
}
