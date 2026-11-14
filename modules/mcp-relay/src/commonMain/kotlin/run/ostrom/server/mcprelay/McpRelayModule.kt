// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.network.outboundHttpClient
import run.ostrom.server.mcprelay.domain.ApprovalRequester
import run.ostrom.server.mcprelay.domain.AssignMcpServerUseCase
import run.ostrom.server.mcprelay.domain.AssignMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCase
import run.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCaseImpl
import run.ostrom.server.mcprelay.domain.CreateMcpServerUseCase
import run.ostrom.server.mcprelay.domain.CreateMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCase
import run.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCaseImpl
import run.ostrom.server.mcprelay.domain.DeleteMcpServerUseCase
import run.ostrom.server.mcprelay.domain.DeleteMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.GetMcpSettingsUseCase
import run.ostrom.server.mcprelay.domain.GetMcpSettingsUseCaseImpl
import run.ostrom.server.mcprelay.domain.HostedProcessAdministration
import run.ostrom.server.mcprelay.domain.HostedSweep
import run.ostrom.server.mcprelay.domain.HostedWarmup
import run.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCase
import run.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCaseImpl
import run.ostrom.server.mcprelay.domain.ListHostedProcessesUseCase
import run.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCase
import run.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCaseImpl
import run.ostrom.server.mcprelay.domain.ListMcpCallsUseCase
import run.ostrom.server.mcprelay.domain.ListMcpCallsUseCaseImpl
import run.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import run.ostrom.server.mcprelay.domain.ListMcpServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.ListMemberServersUseCase
import run.ostrom.server.mcprelay.domain.ListMemberServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.McpCallLimiter
import run.ostrom.server.mcprelay.domain.McpCallSlotRepository
import run.ostrom.server.mcprelay.domain.McpHostAvailability
import run.ostrom.server.mcprelay.domain.McpLimits
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpServerAdministration
import run.ostrom.server.mcprelay.domain.PersonalMcpServersUseCase
import run.ostrom.server.mcprelay.domain.PersonalMcpServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.McpUpstream
import run.ostrom.server.mcprelay.domain.McpUsageUseCase
import run.ostrom.server.mcprelay.domain.McpUsageUseCaseImpl
import run.ostrom.server.mcprelay.domain.NoApprovalRequester
import run.ostrom.server.mcprelay.domain.PutMcpSettingsUseCase
import run.ostrom.server.mcprelay.domain.PutMcpSettingsUseCaseImpl
import run.ostrom.server.mcprelay.domain.RelayDeadlines
import run.ostrom.server.mcprelay.domain.RelayLimits
import run.ostrom.server.mcprelay.domain.RelayMcpMessageUseCase
import run.ostrom.server.mcprelay.domain.RelayMcpMessageUseCaseImpl
import run.ostrom.server.mcprelay.domain.RestartHostedProcessUseCase
import run.ostrom.server.mcprelay.domain.RetainHostedMcpProcessesUseCaseImpl
import run.ostrom.server.mcprelay.domain.UnassignMcpServerUseCase
import run.ostrom.server.mcprelay.domain.UnassignMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.UpdateMcpServerUseCase
import run.ostrom.server.mcprelay.domain.UpdateMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.infrastructure.HostMcpUpstream
import run.ostrom.server.mcprelay.infrastructure.HttpMcpUpstream
import run.ostrom.server.mcprelay.infrastructure.McpCallSlotRepositoryImpl
import run.ostrom.server.mcprelay.infrastructure.McpHostConfig
import run.ostrom.server.mcprelay.infrastructure.McpRelayMigrations
import run.ostrom.server.mcprelay.infrastructure.McpRelayRepositoryImpl
import run.ostrom.server.mcprelay.infrastructure.TransportMcpUpstream
import run.ostrom.server.mcprelay.infrastructure.UpstreamAddressGuard
import run.ostrom.server.mcprelay.presentation.McpRelayRoutesDependencies
import run.ostrom.server.mcprelay.presentation.mcpRelayRoutes
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import io.ktor.client.HttpClient
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
            val host = when {
                url == null && token == null -> null
                url != null && token != null -> McpHostConfig(url, token)
                else -> throw InvalidConfigException("OSTROM_MCP_HOST_URL and OSTROM_MCP_HOST_TOKEN go together.")
            }
            fun millis(key: String, default: Long): Long {
                val value = source[key] ?: return default
                return value.toLongOrNull()?.takeIf { it in DEADLINE_MILLIS }
                    ?: throw InvalidConfigException("$key is 1000 to 600000 ms.")
            }
            return McpRelayConfig(
                host = host,
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

    private val WARMUP_SCOPE = named("mcpRelayWarmupScope")

    /**
     * The Koin name of the client of the org's upstream MCP servers (a URL a member supplied): every connection is
     * resolved once and checked, replaced by tests.
     */
    val UPSTREAM_CLIENT = named("mcpRelayUpstreamClient")

    override val koinModule: Module = module {
        single { McpRelayConfig.from(get()) }
        single(HTTP_CLIENT) {
            HttpClient {
                followRedirects = false
                expectSuccess = false
            }
        } onClose { it?.close() } // Its selector holds a thread of Dispatchers.IO until then (StoppedApplicationsTest).
        // A destination the org's admins typed: public addresses only, checked where the connection is made, so that
        // neither a name that resolves to a private address nor DNS rebinding reaches the server's own network.
        single(UPSTREAM_CLIENT) {
            outboundHttpClient(allowPrivate = get<McpRelayConfig>().allowPrivateUpstreams)
        } onClose { it?.close() }
        single<McpRelayRepository> { McpRelayRepositoryImpl(get()) }
        single { HostedRuntime(get<McpRelayConfig>().host?.let { HostMcpUpstream(get<HttpClient>(HTTP_CLIENT), it) }) }
        // The member listings' warm-ups, outside their requests; cancelled with the server.
        single(WARMUP_SCOPE) { CoroutineScope(SupervisorJob()) } onClose { it?.cancel() }
        single<McpUpstream> {
            val config = get<McpRelayConfig>()
            TransportMcpUpstream(
                http = HttpMcpUpstream(
                    get<HttpClient>(UPSTREAM_CLIENT),
                    UpstreamAddressGuard(config.allowPrivateUpstreams),
                ),
                host = get<HostedRuntime>().host,
            )
        }
        single { McpHostAvailability { get<McpRelayConfig>().host != null } }
        single<McpCallSlotRepository> { McpCallSlotRepositoryImpl(get()) }
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
        single<ListMemberServersUseCase> {
            ListMemberServersUseCaseImpl(
                get(),
                get<HostedRuntime>().host?.let { HostedWarmup(get(), it, get(WARMUP_SCOPE), get()) },
            )
        }
        single { HostedProcessAdministration(get(), getOrNull<MemberDirectory>(), get<HostedRuntime>().host) }
        single<ListHostedProcessesUseCase> { get<HostedProcessAdministration>() }
        single<RestartHostedProcessUseCase> { get<HostedProcessAdministration>() }
        single<PersonalMcpServersUseCase> { PersonalMcpServersUseCaseImpl(get(), get(), get()) }
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
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
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

    /** The MCP relay, and the members' own servers. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.MCP, ServerFeatures.MCP_PERSONAL)

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
        val host = koin.getOrNull<HostedRuntime>()?.host ?: return
        val retain = RetainHostedMcpProcessesUseCaseImpl(repository, koin.getOrNull<MemberDirectory>(), host, clock)
        scope.launch {
            var last: HostedSweep? = null
            while (true) {
                val sweep = try {
                    retain()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    HostedSweep.Incomplete
                }
                // Once per change of outcome, never the keys: a host down for an hour is one line, not sixty.
                if (sweep !is HostedSweep.Done && sweep != last) {
                    logger.warn("mcp retain sweep", mapOf("outcome" to sweep.toString()))
                }
                last = sweep
                delay(1.minutes)
            }
        }
    }

    /** The MCP host, when one is configured: it runs the stdio servers, warmed and swept from here. */
    private class HostedRuntime(val host: HostMcpUpstream?)
}
