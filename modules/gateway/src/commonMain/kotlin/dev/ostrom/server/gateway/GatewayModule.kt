package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.GatewaySettingsRepository
import dev.ostrom.server.gateway.domain.GetGatewaySettingsUseCase
import dev.ostrom.server.gateway.domain.GetGatewaySettingsUseCaseImpl
import dev.ostrom.server.gateway.domain.GetModelPolicyUseCase
import dev.ostrom.server.gateway.domain.GetModelPolicyUseCaseImpl
import dev.ostrom.server.gateway.domain.InferencePlanner
import dev.ostrom.server.gateway.domain.InferenceUpstream
import dev.ostrom.server.gateway.domain.InstanceProviderKeys
import dev.ostrom.server.gateway.domain.ModelCallObserver
import dev.ostrom.server.gateway.domain.ModelUsageSink
import dev.ostrom.server.gateway.domain.PriceTable
import dev.ostrom.server.gateway.domain.ProviderEndpoints
import dev.ostrom.server.gateway.domain.PutGatewaySettingsUseCase
import dev.ostrom.server.gateway.domain.PutGatewaySettingsUseCaseImpl
import dev.ostrom.server.gateway.domain.PutModelPolicyUseCase
import dev.ostrom.server.gateway.domain.PutModelPolicyUseCaseImpl
import dev.ostrom.server.gateway.domain.RelayInferenceUseCase
import dev.ostrom.server.gateway.domain.RelayInferenceUseCaseImpl
import dev.ostrom.server.gateway.domain.SpendDecision
import dev.ostrom.server.gateway.domain.SpendGuard
import dev.ostrom.server.gateway.infrastructure.GatewayMigrations
import dev.ostrom.server.gateway.infrastructure.GatewaySettingsRepositoryImpl
import dev.ostrom.server.gateway.infrastructure.KtorInferenceUpstream
import dev.ostrom.server.gateway.presentation.GatewayRoutesDependencies
import dev.ostrom.server.gateway.presentation.gatewayRoutes
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.UnresolvedCallerResolver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The gateway's configuration, from the instance (Docker environment, Helm values): provider keys that every org
 * uses (they come before an org's own key), and provider URLs (tests and proxies).
 *
 * @property keys The instance's provider keys.
 * @property endpoints The provider URLs.
 */
data class GatewayConfig(val keys: InstanceProviderKeys, val endpoints: ProviderEndpoints) {
    companion object {
        /** Reads `OSTROM_GATEWAY_*` (docs/MODULES.md). */
        fun from(source: ConfigSource): GatewayConfig {
            val defaults = ProviderEndpoints()
            fun url(key: String, default: String): String {
                val value = source[key] ?: return default
                require(value.startsWith("https://") || value.startsWith("http://")) { "$key must be an http(s) URL." }
                return value.trimEnd('/')
            }
            return GatewayConfig(
                keys = InstanceProviderKeys(
                    anthropic = source["OSTROM_GATEWAY_ANTHROPIC_API_KEY"],
                    openai = source["OSTROM_GATEWAY_OPENAI_API_KEY"],
                    openrouter = source["OSTROM_GATEWAY_OPENROUTER_API_KEY"],
                ),
                endpoints = ProviderEndpoints(
                    anthropic = url("OSTROM_GATEWAY_ANTHROPIC_URL", defaults.anthropic),
                    openai = url("OSTROM_GATEWAY_OPENAI_URL", defaults.openai),
                    openrouter = url("OSTROM_GATEWAY_OPENROUTER_URL", defaults.openrouter),
                    codex = url("OSTROM_GATEWAY_CODEX_URL", defaults.codex),
                ),
            )
        }
    }
}

/**
 * The gateway module: agents send their model calls to Ostrom; the gateway serves them with the org's shared
 * subscription accounts or its provider keys, streams the answer back untouched, and records usage. Budgets, the usage
 * ledger and traces are ports ([SpendGuard], [ModelUsageSink], [ModelCallObserver]) bound by the modules that own
 * them; unbound, every call is allowed, and nothing is recorded or traced.
 */
object GatewayModule : ServerModule {
    override val name: String = "gateway"

    /** The Koin name of the providers' HTTP client, replaced by tests. */
    val HTTP_CLIENT = named("gatewayHttpClient")

    override val koinModule: Module = module {
        single { GatewayConfig.from(getOrNull<ConfigSource>() ?: EnvironmentConfigSource()) }
        single(HTTP_CLIENT) {
            HttpClient {
                followRedirects = false
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }
        }
        single<GatewaySettingsRepository> { GatewaySettingsRepositoryImpl(get()) }
        single<InferenceUpstream> { KtorInferenceUpstream(get(HTTP_CLIENT)) }
        single {
            val config = get<GatewayConfig>()
            InferencePlanner(get(), get(), get(), getOrNull<SpendGuard>() ?: AllowAll, config.keys, config.endpoints)
        }
        single<RelayInferenceUseCase> {
            RelayInferenceUseCaseImpl(
                planner = get(),
                upstream = get(),
                prices = PriceTable(),
                usage = getOrNull<ModelUsageSink>() ?: ModelUsageSink { },
                observer = getOrNull<ModelCallObserver>() ?: ModelCallObserver { },
                clock = get(),
            )
        }
        single<GetGatewaySettingsUseCase> { GetGatewaySettingsUseCaseImpl(get()) }
        single<PutGatewaySettingsUseCase> { PutGatewaySettingsUseCaseImpl(get()) }
        single<GetModelPolicyUseCase> { GetModelPolicyUseCaseImpl(get()) }
        single<PutModelPolicyUseCase> { PutModelPolicyUseCaseImpl(get()) }
        single {
            GatewayRoutesDependencies(get(), getOrNull<CallerResolver>() ?: UnresolvedCallerResolver(), get(), get(), get(), get(), get(), get())
        }
    }

    override val migrations: List<SchemaMigration> = GatewayMigrations

    override suspend fun initialize(koin: Koin) {
        koin.get<GatewayConfig>()
    }

    override fun routes(route: Route, koin: Koin) {
        route.gatewayRoutes(koin.get())
    }

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SOCKET_TIMEOUT_MS = 300_000L

    /** Every call allowed: the default until the budgets module binds its [SpendGuard]. */
    private val AllowAll = SpendGuard { _, _ -> SpendDecision.Allowed }
}
