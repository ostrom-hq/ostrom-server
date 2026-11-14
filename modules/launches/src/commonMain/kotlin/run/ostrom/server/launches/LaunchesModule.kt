// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.kernel.auth.ModelAccess
import run.ostrom.server.kernel.database.ScheduledJob
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.repeatDue
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.launches.domain.services.AgentTokenService
import run.ostrom.server.launches.domain.services.CloudExecutions
import run.ostrom.server.launches.domain.services.HooksCheckRules
import run.ostrom.server.launches.domain.services.LaunchModelRules
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCase
import run.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCaseImpl
import run.ostrom.server.launches.domain.usecases.CreateLaunchUseCase
import run.ostrom.server.launches.domain.usecases.CreateLaunchUseCaseImpl
import run.ostrom.server.launches.domain.usecases.EndLaunchUseCase
import run.ostrom.server.launches.domain.usecases.EndLaunchUseCaseImpl
import run.ostrom.server.launches.domain.usecases.LaunchPolicy
import run.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCase
import run.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCaseImpl
import run.ostrom.server.launches.domain.usecases.ReportHooksMissingUseCase
import run.ostrom.server.launches.domain.usecases.ReportHooksMissingUseCaseImpl
import run.ostrom.server.launches.infrastructure.IdentityAgentTokens
import run.ostrom.server.launches.infrastructure.IdentityLauncherResolver
import run.ostrom.server.launches.infrastructure.LaunchAgents
import run.ostrom.server.launches.infrastructure.LaunchGateActors
import run.ostrom.server.launches.infrastructure.LaunchRelayCallers
import run.ostrom.server.launches.infrastructure.LaunchWaitAgents
import run.ostrom.server.launches.infrastructure.LaunchesMigrations
import run.ostrom.server.launches.infrastructure.ModelAccessLaunchRules
import run.ostrom.server.launches.infrastructure.NoLaunchModelRules
import run.ostrom.server.launches.infrastructure.OrgsHooksCheckRules
import run.ostrom.server.launches.infrastructure.PostgresLaunchesRepository
import run.ostrom.server.launches.infrastructure.RunnerCloudExecutions
import run.ostrom.server.launches.presentation.LaunchesRoutesDependencies
import run.ostrom.server.launches.presentation.launchesRoutes
import run.ostrom.server.secrets.domain.CallerResolver
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The launches module: `ostrom <agent>` reads the organization's policy, then creates a launch and gets the agent
 * token its hooks use, scoped to the launch's sessions (audit A12/A13). It also resolves the callers of the gates,
 * sessions and compiler routes ([LaunchGateActors]), and those of the MCP relay, the inference gateway and the budgets
 * and the usage events ([LaunchRelayCallers]), so that an agent token reaches the organization's MCP servers, and the
 * agent token of the waits MCP server ([LaunchWaitAgents]), so that an agent asks a person. It also opens the launch of
 * a cloud session its runner asks for, and ends a launch when its own agent token asks (the end of `ostrom <agent>`).
 * A launch tells the CLI when to look for the hooks and whether to stop an agent without them, and the launch's own
 * agent token records `hooks.missing` in the organization's audit log when none ran.
 * Needs the identity, orgs, gates and runners modules; it binds the secrets module's `CallerResolver` and the
 * automations module's `WaitAgentResolver`, which those modules look up.
 */
object LaunchesModule : ServerModule {
    override val name: String = "launches"

    override val koinModule: Module = module {
        single<LaunchesRepository> { PostgresLaunchesRepository(get(), get()) }
        single<LauncherResolver> { IdentityLauncherResolver(get(), get(), get()) }
        single<AgentTokenService> { IdentityAgentTokens(get(), get()) }
        single<CloudExecutions> { RunnerCloudExecutions(get()) }
        single { LaunchAgents(get(), get(), get(), get()) }
        single<GateActorResolver> { LaunchGateActors(get(), get()) }
        single<CallerResolver> { LaunchRelayCallers(get(), get()) }
        single<WaitAgentResolver> { LaunchWaitAgents(get(), get()) }
        single<HooksCheckRules> { OrgsHooksCheckRules(get(), get()) }
        single { LaunchPolicy(get()) }
        single<CheckAgentPolicyUseCase> { CheckAgentPolicyUseCaseImpl(get(), get()) }
        single<CreateLaunchUseCase> {
            CreateLaunchUseCaseImpl(get(), get(), get(), get(), get(), workflows = getOrNull())
        }
        single<OpenRunnerLaunchUseCase> { OpenRunnerLaunchUseCaseImpl(get(), get()) }
        single<EndLaunchUseCase> { EndLaunchUseCaseImpl(get()) }
        single<ReportHooksMissingUseCase> { ReportHooksMissingUseCaseImpl(get(), get()) }
        single { LaunchesRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = LaunchesMigrations

    /** How long a launch is kept once expired: its agent token no longer acts, and nothing reads it afterwards. */
    val LAUNCH_RETENTION: Duration = 30.days

    /** The longest the hourly purge may take. */
    private val PURGE_TIMEOUT: Duration = 5.minutes

    // Every hour, the launches expired more than LAUNCH_RETENTION ago are deleted: a row per `ostrom <agent>`, they
    // were kept for ever. One replica at a time runs it (ScheduledWork). A failed purge is logged and tried again the
    // next hour; it never stops the server.
    override fun start(scope: CoroutineScope, koin: Koin) {
        val launches = koin.get<LaunchesRepository>()
        val clock = koin.get<Clock>()
        val logger = koin.get<Logger>()
        val work = koin.scheduledWork()
        scope.launch {
            work.repeatDue(ScheduledJob("launches/purge", 1.hours, PURGE_TIMEOUT), logger, "launch purge failed") {
                launches.purge(clock.now() - LAUNCH_RETENTION)
            }
        }
    }

    /**
     * Binds the organization's rules on models unless a loaded module binds its own: the inference gateway's model
     * policy when it is loaded ([ModelAccessLaunchRules] on its [ModelAccess]), every model otherwise
     * ([NoLaunchModelRules]).
     */
    override suspend fun initialize(koin: Koin) {
        if (koin.getOrNull<LaunchModelRules>() == null) {
            val rules = module {
                single<LaunchModelRules> {
                    getOrNull<ModelAccess>()?.let { ModelAccessLaunchRules(it, get()) } ?: NoLaunchModelRules()
                }
            }
            koin.loadModules(listOf(rules))
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.launchesRoutes(koin.get())
    }
}
