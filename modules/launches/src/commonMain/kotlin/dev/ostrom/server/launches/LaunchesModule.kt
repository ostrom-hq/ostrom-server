// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.launches.domain.services.AgentTokenService
import dev.ostrom.server.launches.domain.services.LaunchModelRules
import dev.ostrom.server.launches.domain.services.LauncherResolver
import dev.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCase
import dev.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCase
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCaseImpl
import dev.ostrom.server.launches.domain.usecases.LaunchPolicy
import dev.ostrom.server.launches.infrastructure.IdentityAgentTokens
import dev.ostrom.server.launches.infrastructure.IdentityLauncherResolver
import dev.ostrom.server.launches.infrastructure.LaunchGateActors
import dev.ostrom.server.launches.infrastructure.LaunchesMigrations
import dev.ostrom.server.launches.infrastructure.NoLaunchModelRules
import dev.ostrom.server.launches.infrastructure.PostgresLaunchesRepository
import dev.ostrom.server.launches.presentation.LaunchesRoutesDependencies
import dev.ostrom.server.launches.presentation.launchesRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The launches module: `ostrom <agent>` reads the organization's policy, then creates a launch and gets the agent
 * token its hooks use, scoped to the launch's sessions (audit A12/A13). It also resolves the callers of the gates,
 * sessions and compiler routes ([LaunchGateActors]). Needs the identity, orgs and gates modules.
 */
object LaunchesModule : ServerModule {
    override val name: String = "launches"

    override val koinModule: Module = module {
        single<LaunchesRepository> { PostgresLaunchesRepository(get()) }
        single<LauncherResolver> { IdentityLauncherResolver(get(), get()) }
        single<AgentTokenService> { IdentityAgentTokens(get()) }
        single<GateActorResolver> { LaunchGateActors(get(), get(), get()) }
        single { LaunchPolicy(get()) }
        single<CheckAgentPolicyUseCase> { CheckAgentPolicyUseCaseImpl(get()) }
        single<CreateLaunchUseCase> { CreateLaunchUseCaseImpl(get(), get(), get(), get()) }
        single { LaunchesRoutesDependencies(get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = LaunchesMigrations

    /** Binds [NoLaunchModelRules] unless a loaded module (the inference gateway) binds the organization's rules. */
    override suspend fun initialize(koin: Koin) {
        if (koin.getOrNull<LaunchModelRules>() == null) {
            koin.loadModules(listOf(module { single<LaunchModelRules> { NoLaunchModelRules() } }))
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.launchesRoutes(koin.get())
    }
}
