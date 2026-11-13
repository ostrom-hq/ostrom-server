// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.core.telemetry.Telemetry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AllowAllSpend
import run.ostrom.server.gateway.domain.AwaitAdmissionUseCase
import run.ostrom.server.gateway.domain.AwaitAdmissionUseCaseImpl
import run.ostrom.server.gateway.domain.BudgetCheck
import run.ostrom.server.gateway.domain.CancelInferenceRequestUseCase
import run.ostrom.server.gateway.domain.CancelInferenceRequestUseCaseImpl
import run.ostrom.server.gateway.domain.CancellationWatch
import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.GetGatewaySettingsUseCase
import run.ostrom.server.gateway.domain.GetGatewaySettingsUseCaseImpl
import run.ostrom.server.gateway.domain.GetInferenceQueueUseCase
import run.ostrom.server.gateway.domain.GetInferenceQueueUseCaseImpl
import run.ostrom.server.gateway.domain.GetModelPolicyUseCase
import run.ostrom.server.gateway.domain.GetModelPolicyUseCaseImpl
import run.ostrom.server.gateway.domain.InferencePlanner
import run.ostrom.server.gateway.domain.InferenceUpstream
import run.ostrom.server.gateway.domain.ModelCallObserver
import run.ostrom.server.gateway.domain.ModelUsageSink
import run.ostrom.server.gateway.domain.NoModelCallObserver
import run.ostrom.server.gateway.domain.NoModelUsageSink
import run.ostrom.server.gateway.domain.PolicyModelAccess
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.PutGatewaySettingsUseCase
import run.ostrom.server.gateway.domain.PutGatewaySettingsUseCaseImpl
import run.ostrom.server.gateway.domain.PutModelPolicyUseCase
import run.ostrom.server.gateway.domain.PutModelPolicyUseCaseImpl
import run.ostrom.server.gateway.domain.RelayInferenceUseCase
import run.ostrom.server.gateway.domain.RelayInferenceUseCaseImpl
import run.ostrom.server.gateway.domain.RequestCancellationRepository
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.UpstreamRequests
import run.ostrom.server.gateway.infrastructure.AdmissionQueueRepositoryImpl
import run.ostrom.server.gateway.infrastructure.GatewayMigrations
import run.ostrom.server.gateway.infrastructure.GatewaySettingsRepositoryImpl
import run.ostrom.server.gateway.infrastructure.KtorInferenceUpstream
import run.ostrom.server.gateway.infrastructure.MeteredModelCallObserver
import run.ostrom.server.gateway.infrastructure.RequestCancellationRepositoryImpl
import run.ostrom.server.gateway.infrastructure.SpanModelCallTracer
import run.ostrom.server.gateway.presentation.GatewayGovernanceChange
import run.ostrom.server.gateway.presentation.GatewayRoutesDependencies
import run.ostrom.server.gateway.presentation.gatewayRoutes
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.ModelAccess
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Duration.Companion.milliseconds

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
        single { GatewayConfig.from(get<ConfigSource>()) }
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
        single<GatewaySettingsRepository> { GatewaySettingsRepositoryImpl(get()) }
        single<InferenceUpstream> { KtorInferenceUpstream(get(HTTP_CLIENT), SILENCE_MS.milliseconds) }
        single { PriceTable() }
        single {
            val config = get<GatewayConfig>()
            InferencePlanner(
                settings = get(),
                pool = get(),
                revealSecrets = get(),
                spend = BudgetCheck(getOrNull<SpendGuard>() ?: AllowAllSpend, get()),
                keys = config.keys,
                requests = UpstreamRequests(config.endpoints),
            )
        }
        single<AdmissionQueueRepository> { AdmissionQueueRepositoryImpl(get()) }
        single<AwaitAdmissionUseCase> { AwaitAdmissionUseCaseImpl(get(), get()) }
        single<GetInferenceQueueUseCase> { GetInferenceQueueUseCaseImpl(get()) }
        single<RequestCancellationRepository> { RequestCancellationRepositoryImpl(get()) }
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
        single<GetGatewaySettingsUseCase> { GetGatewaySettingsUseCaseImpl(get()) }
        single<PutGatewaySettingsUseCase> { PutGatewaySettingsUseCaseImpl(get()) }
        single<GetModelPolicyUseCase> { GetModelPolicyUseCaseImpl(get()) }
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
            )
        }
    }

    override val migrations: List<SchemaMigration> = GatewayMigrations

    /** Reads the configuration at start: a malformed URL stops the server instead of failing calls. */
    override suspend fun initialize(koin: Koin) {
        koin.get<GatewayConfig>()
    }

    override fun routes(route: Route, koin: Koin) {
        route.gatewayRoutes(koin.get())
    }

    /**
     * The inference gateway, its admission queue and the cancellation of a call: an org configures its own provider
     * keys, so all are served.
     */
    override suspend fun features(koin: Koin): Set<String> =
        setOf(ServerFeatures.INFERENCE, ServerFeatures.INFERENCE_QUEUE, ServerFeatures.INFERENCE_CANCEL)
}
