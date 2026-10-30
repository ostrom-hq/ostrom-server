// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.automations.domain.AutomationLauncher
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.runners.domain.AllowlistedCloudPlanProvider
import dev.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import dev.ostrom.server.runners.domain.ChangeCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import dev.ostrom.server.runners.domain.CheckRunnerTokenUseCaseImpl
import dev.ostrom.server.runners.domain.CloudPlanProvider
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCase
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.FreeCloudPlanProvider
import dev.ostrom.server.runners.domain.GetCloudSessionUseCase
import dev.ostrom.server.runners.domain.GetCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.ListCloudSessionsUseCase
import dev.ostrom.server.runners.domain.ListCloudSessionsUseCaseImpl
import dev.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import dev.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.RecordCloudSessionActivityUseCase
import dev.ostrom.server.runners.domain.RecordCloudSessionActivityUseCaseImpl
import dev.ostrom.server.runners.domain.RunnerHeartbeatUseCase
import dev.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.domain.RuntimeDriver
import dev.ostrom.server.runners.infrastructure.CloudSessionAutomationLauncher
import dev.ostrom.server.runners.infrastructure.CloudSessionsRepositoryImpl
import dev.ostrom.server.runners.infrastructure.ReconcilerWorker
import dev.ostrom.server.runners.infrastructure.RunnerTokensImpl
import dev.ostrom.server.runners.infrastructure.RunnersConfig
import dev.ostrom.server.runners.infrastructure.RunnersMigrations
import dev.ostrom.server.runners.infrastructure.RuntimeDrivers
import dev.ostrom.server.runners.presentation.CloudSessionsRoutesDependencies
import dev.ostrom.server.runners.presentation.cloudSessionsRoutes
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.IssueTerminalTicketUseCase
import dev.ostrom.server.runners.domain.IssueTerminalTicketUseCaseImpl
import dev.ostrom.server.runners.domain.OpenTerminalUseCase
import dev.ostrom.server.runners.domain.OpenTerminalUseCaseImpl
import dev.ostrom.server.runners.domain.TerminalActivity
import dev.ostrom.server.runners.domain.TerminalTickets
import dev.ostrom.server.runners.infrastructure.InMemoryTerminalTickets
import dev.ostrom.server.runners.infrastructure.TerminalRelay
import dev.ostrom.server.runners.presentation.TerminalRoutesDependencies
import dev.ostrom.server.runners.presentation.terminalRoutes
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The runners module: cloud sessions. A member creates a session; the leased reconciler starts one pod per
 * execution (Kubernetes namespace `ostrom`, or a local Docker container), with a workspace volume that survives
 * pauses; the runner in the pod reports through the runner channel; the free plan's guards pause idle, overlong or
 * over-budget sessions.
 */
object RunnersModule : ServerModule {
    override val name: String = "runners"

    override val koinModule: Module = module {
        single<RunnersConfig> { RunnersConfig.from(get<ConfigSource>()) }
        single<CloudSessionsRepository> { CloudSessionsRepositoryImpl(get()) }
        single<RunnerTokens> { RunnerTokensImpl() }
        single<CloudPlanProvider> {
            AllowlistedCloudPlanProvider(get<RunnersConfig>().allowedOrgs, FreeCloudPlanProvider())
        }
        single<RuntimeDriver> { RuntimeDrivers.create(get()) }
        single<CreateCloudSessionUseCase> { CreateCloudSessionUseCaseImpl(get(), get(), get()) }
        single<GetCloudSessionUseCase> { GetCloudSessionUseCaseImpl(get()) }
        single<ListCloudSessionsUseCase> { ListCloudSessionsUseCaseImpl(get()) }
        single<ChangeCloudSessionUseCase> { ChangeCloudSessionUseCaseImpl(get(), get(), get()) }
        single<CheckRunnerTokenUseCase> { CheckRunnerTokenUseCaseImpl(get(), get()) }
        single<RunnerHeartbeatUseCase> { RunnerHeartbeatUseCaseImpl(get(), get(), get()) }
        single<RecordCloudSessionActivityUseCase> { RecordCloudSessionActivityUseCaseImpl(get(), get()) }
        single<ReconcileCloudSessionUseCase> { ReconcileCloudSessionUseCaseImpl(get(), get(), get(), get(), get()) }
        single { ReconcilerWorker(get(), get(), get(), get()) }
        single { CloudSessionsRoutesDependencies(get(), get(), get(), get(), get(), get(), get()) }
        single { TerminalRelay() }
        single<TerminalTickets> { InMemoryTerminalTickets(get(), get()) }
        single<AuthenticateRunnerUseCase> { AuthenticateRunnerUseCaseImpl(get(), get()) }
        single<OpenTerminalUseCase> { OpenTerminalUseCaseImpl(get()) }
        single<IssueTerminalTicketUseCase> { IssueTerminalTicketUseCaseImpl(get(), get()) }
        single { TerminalActivity(get(), get()) }
        single { TerminalRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get()) }
        single<AutomationLauncher> { CloudSessionAutomationLauncher(get(), get()) }
    }

    override val migrations: List<SchemaMigration> = RunnersMigrations

    override fun routes(route: Route, koin: Koin) {
        route.cloudSessionsRoutes(koin.get())
        route.terminalRoutes(koin.get())
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val worker = koin.get<ReconcilerWorker>()
        // Undispatched: the loop is running, up to its first query, when the server starts serving.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { worker.run() }
    }
}
