// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.kernel.auth.ModelAccess
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.launches.domain.services.AgentTokenService
import dev.ostrom.server.launches.domain.services.CloudExecutions
import dev.ostrom.server.launches.domain.services.LaunchModelRules
import dev.ostrom.server.launches.domain.services.LauncherResolver
import dev.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCase
import dev.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCase
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.EndLaunchUseCase
import dev.ostrom.server.launches.domain.usecases.EndLaunchUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.LaunchPolicy
import dev.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCase
import dev.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCaseImpl
import dev.ostrom.server.launches.infrastructure.IdentityAgentTokens
import dev.ostrom.server.launches.infrastructure.IdentityLauncherResolver
import dev.ostrom.server.launches.infrastructure.LaunchAgents
import dev.ostrom.server.launches.infrastructure.LaunchGateActors
import dev.ostrom.server.launches.infrastructure.LaunchRelayCallers
import dev.ostrom.server.launches.infrastructure.LaunchesMigrations
import dev.ostrom.server.launches.infrastructure.ModelAccessLaunchRules
import dev.ostrom.server.launches.infrastructure.NoLaunchModelRules
import dev.ostrom.server.launches.infrastructure.PostgresLaunchesRepository
import dev.ostrom.server.launches.infrastructure.RunnerCloudExecutions
import dev.ostrom.server.launches.presentation.LaunchesRoutesDependencies
import dev.ostrom.server.launches.presentation.launchesRoutes
import dev.ostrom.server.secrets.domain.CallerResolver
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The launches module: `ostrom <agent>` reads the organization's policy, then creates a launch and gets the agent
 * token its hooks use, scoped to the launch's sessions (audit A12/A13). It also resolves the callers of the gates,
 * sessions and compiler routes ([LaunchGateActors]), and those of the MCP relay, the inference gateway and the budgets
 * and the usage events ([LaunchRelayCallers]), so that an agent token reaches the organization's MCP servers. It also
 * opens the launch of a cloud session its runner asks for, and ends a launch when its own agent token asks (the end of
 * `ostrom <agent>`). Needs the identity, orgs, gates and runners modules; it binds the secrets module's
 * `CallerResolver`, which those modules look up.
 */
object LaunchesModule : ServerModule {
    override val name: String = "launches"

    override val koinModule: Module = module {
        single<LaunchesRepository> { PostgresLaunchesRepository(get()) }
        single<LauncherResolver> { IdentityLauncherResolver(get(), get(), get()) }
        single<AgentTokenService> { IdentityAgentTokens(get(), get()) }
        single<CloudExecutions> { RunnerCloudExecutions(get()) }
        single { LaunchAgents(get(), get(), get(), get()) }
        single<GateActorResolver> { LaunchGateActors(get(), get()) }
        single<CallerResolver> { LaunchRelayCallers(get(), get()) }
        single { LaunchPolicy(get()) }
        single<CheckAgentPolicyUseCase> { CheckAgentPolicyUseCaseImpl(get(), get()) }
        single<CreateLaunchUseCase> { CreateLaunchUseCaseImpl(get(), get(), get(), get()) }
        single<OpenRunnerLaunchUseCase> { OpenRunnerLaunchUseCaseImpl(get(), get()) }
        single<EndLaunchUseCase> { EndLaunchUseCaseImpl(get()) }
        single { LaunchesRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = LaunchesMigrations

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
