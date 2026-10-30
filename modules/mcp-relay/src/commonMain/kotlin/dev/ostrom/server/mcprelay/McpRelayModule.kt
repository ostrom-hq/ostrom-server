// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.mcprelay.domain.ApprovalRequester
import dev.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.AssignMcpServerUseCaseImpl
import dev.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCase
import dev.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCaseImpl
import dev.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.CreateMcpServerUseCaseImpl
import dev.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCase
import dev.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCaseImpl
import dev.ostrom.server.mcprelay.domain.DeleteMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.DeleteMcpServerUseCaseImpl
import dev.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.GetMcpSettingsUseCaseImpl
import dev.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import dev.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCaseImpl
import dev.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCaseImpl
import dev.ostrom.server.mcprelay.domain.ListMcpCallsUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpCallsUseCaseImpl
import dev.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import dev.ostrom.server.mcprelay.domain.ListMcpServersUseCaseImpl
import dev.ostrom.server.mcprelay.domain.ListMemberServersUseCase
import dev.ostrom.server.mcprelay.domain.ListMemberServersUseCaseImpl
import dev.ostrom.server.mcprelay.domain.McpCallLimiter
import dev.ostrom.server.mcprelay.domain.McpHostAvailability
import dev.ostrom.server.mcprelay.domain.McpLimits
import dev.ostrom.server.mcprelay.domain.McpRelayRepository
import dev.ostrom.server.mcprelay.domain.McpServerAdministration
import dev.ostrom.server.mcprelay.domain.McpUpstream
import dev.ostrom.server.mcprelay.domain.McpUsageUseCase
import dev.ostrom.server.mcprelay.domain.McpUsageUseCaseImpl
import dev.ostrom.server.mcprelay.domain.NoApprovalRequester
import dev.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import dev.ostrom.server.mcprelay.domain.PutMcpSettingsUseCaseImpl
import dev.ostrom.server.mcprelay.domain.RelayDeadlines
import dev.ostrom.server.mcprelay.domain.RelayLimits
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCase
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCaseImpl
import dev.ostrom.server.mcprelay.domain.UnassignMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.UnassignMcpServerUseCaseImpl
import dev.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import dev.ostrom.server.mcprelay.domain.UpdateMcpServerUseCaseImpl
import dev.ostrom.server.mcprelay.infrastructure.HostMcpUpstream
import dev.ostrom.server.mcprelay.infrastructure.HttpMcpUpstream
import dev.ostrom.server.mcprelay.infrastructure.McpHostConfig
import dev.ostrom.server.mcprelay.infrastructure.McpRelayMigrations
import dev.ostrom.server.mcprelay.infrastructure.McpRelayRepositoryImpl
import dev.ostrom.server.mcprelay.infrastructure.TransportMcpUpstream
import dev.ostrom.server.mcprelay.infrastructure.UpstreamAddressGuard
import dev.ostrom.server.mcprelay.presentation.McpRelayRoutesDependencies
import dev.ostrom.server.mcprelay.presentation.mcpRelayRoutes
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import io.ktor.client.HttpClient
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The relay's configuration, read once (`OSTROM_MCP_*`).
 *
 * @property host The MCP host running stdio servers, or null.
 * @property allowPrivateUpstreams Whether HTTP servers may be on the private network (self-hosted).
 * @property deadlines List and call deadlines.
 */
data class McpRelayConfig(val host: McpHostConfig?, val allowPrivateUpstreams: Boolean, val deadlines: RelayDeadlines) {
    /** Reading the configuration. */
    companion object {
        private val DEADLINE_MILLIS = 1_000L..600_000L
        private const val LIST_MILLIS = 15_000L
        private const val CALL_MILLIS = 60_000L

        /**
         * Reads it.
         *
         * @throws InvalidConfigException When a value is malformed.
         */
        fun from(source: ConfigSource): McpRelayConfig {
            val url = source["OSTROM_MCP_HOST_URL"]?.takeIf { it.isNotBlank() }
            val token = source["OSTROM_MCP_HOST_TOKEN"]?.takeIf { it.isNotBlank() }
            if ((url == null) != (token == null)) {
                throw InvalidConfigException("OSTROM_MCP_HOST_URL and OSTROM_MCP_HOST_TOKEN go together.")
            }
            fun millis(key: String, default: Long): Long {
                val value = source[key] ?: return default
                return value.toLongOrNull()?.takeIf { it in DEADLINE_MILLIS }
                    ?: throw InvalidConfigException("$key is 1000 to 600000 ms.")
            }
            return McpRelayConfig(
                host = if (url != null && token != null) McpHostConfig(url, token) else null,
                allowPrivateUpstreams = source["OSTROM_MCP_ALLOW_PRIVATE_UPSTREAMS"] == "true",
                deadlines = RelayDeadlines(
                    list = millis("OSTROM_MCP_LIST_TIMEOUT_MS", LIST_MILLIS).milliseconds,
                    call = millis("OSTROM_MCP_CALL_TIMEOUT_MS", CALL_MILLIS).milliseconds,
                ),
            )
        }
    }
}

/** The MCP relay module. */
object McpRelayModule : ServerModule {
    override val name: String = "mcp-relay"

    /** The Koin name of the relay's HTTP client (redirects off), replaced by tests. */
    val HTTP_CLIENT = named("mcpRelayHttpClient")

    override val koinModule: Module = module {
        single { McpRelayConfig.from(getOrNull<ConfigSource>() ?: EnvironmentConfigSource()) }
        single(HTTP_CLIENT) {
            HttpClient {
                followRedirects = false
                expectSuccess = false
            }
        }
        single<McpRelayRepository> { McpRelayRepositoryImpl(get()) }
        single<McpUpstream> {
            val config = get<McpRelayConfig>()
            val client = get<HttpClient>(HTTP_CLIENT)
            TransportMcpUpstream(
                http = HttpMcpUpstream(client, UpstreamAddressGuard(config.allowPrivateUpstreams)),
                host = config.host?.let { HostMcpUpstream(client, it) },
            )
        }
        single { McpHostAvailability { get<McpRelayConfig>().host != null } }
        single { McpCallLimiter(McpLimits(), get()) }
        single { McpServerAdministration(get(), get(), get(), get()) }
        single<CreateMcpServerUseCase> { CreateMcpServerUseCaseImpl(get()) }
        single<UpdateMcpServerUseCase> { UpdateMcpServerUseCaseImpl(get()) }
        single<DeleteMcpServerUseCase> { DeleteMcpServerUseCaseImpl(get()) }
        single<ListMcpServersUseCase> { ListMcpServersUseCaseImpl(get()) }
        single<AssignMcpServerUseCase> { AssignMcpServerUseCaseImpl(get(), get(), get()) }
        single<UnassignMcpServerUseCase> { UnassignMcpServerUseCaseImpl(get()) }
        single<ListMcpAssignmentsUseCase> { ListMcpAssignmentsUseCaseImpl(get()) }
        single<CreateArgumentPolicyUseCase> { CreateArgumentPolicyUseCaseImpl(get(), get()) }
        single<DeleteArgumentPolicyUseCase> { DeleteArgumentPolicyUseCaseImpl(get()) }
        single<ListArgumentPoliciesUseCase> { ListArgumentPoliciesUseCaseImpl(get()) }
        single<GetMcpSettingsUseCase> { GetMcpSettingsUseCaseImpl(get()) }
        single<PutMcpSettingsUseCase> { PutMcpSettingsUseCaseImpl(get()) }
        single<ListMcpCallsUseCase> { ListMcpCallsUseCaseImpl(get()) }
        single<McpUsageUseCase> { McpUsageUseCaseImpl(get(), get()) }
        single<ListMemberServersUseCase> { ListMemberServersUseCaseImpl(get()) }
        single<RelayMcpMessageUseCase> {
            RelayMcpMessageUseCaseImpl(
                repository = get(),
                revealSecrets = get(),
                upstream = get(),
                approvals = getOrNull<ApprovalRequester>() ?: NoApprovalRequester(),
                limits = RelayLimits(get(), get<McpRelayConfig>().deadlines),
                clock = get(),
            )
        }
        single {
            McpRelayRoutesDependencies(
                getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()),
                get(), get(), get(), get(), get(), get(), get(), get(), get(),
                get(), get(), get(), get(), get(), get(), get(), get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = McpRelayMigrations

    override suspend fun initialize(koin: Koin) {
        koin.get<McpRelayConfig>()
    }

    override fun routes(route: Route, koin: Koin) {
        route.mcpRelayRoutes(koin.get())
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val repository = koin.get<McpRelayRepository>()
        val clock = koin.get<Clock>()
        val logger = koin.get<Logger>()
        scope.launch {
            while (true) {
                try {
                    repository.purgeCalls(clock.now() - 30.days)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.warn("mcp journal purge failed", mapOf("error" to error::class.simpleName))
                }
                delay(1.hours)
            }
        }
    }
}
