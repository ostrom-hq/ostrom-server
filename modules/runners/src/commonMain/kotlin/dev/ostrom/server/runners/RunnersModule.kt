// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.automations.domain.AutomationLauncher
import dev.ostrom.server.billing.domain.GetAgentUsageUseCase
import dev.ostrom.server.billing.domain.GetPlanLimitsUseCase
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.runners.domain.AllowlistedCloudPlanProvider
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import dev.ostrom.server.runners.domain.ChangeCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import dev.ostrom.server.runners.domain.CheckRunnerTokenUseCaseImpl
import dev.ostrom.server.runners.domain.CloudPlanProvider
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.CloudUsageMeter
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCase
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.DeleteRunnerUseCase
import dev.ostrom.server.runners.domain.DeleteRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.ExecutionPlanner
import dev.ostrom.server.runners.domain.FreeCloudPlanProvider
import dev.ostrom.server.runners.domain.GetCloudSessionUseCase
import dev.ostrom.server.runners.domain.GetCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.IssueTerminalTicketUseCase
import dev.ostrom.server.runners.domain.IssueTerminalTicketUseCaseImpl
import dev.ostrom.server.runners.domain.ListCloudSessionsUseCase
import dev.ostrom.server.runners.domain.ListCloudSessionsUseCaseImpl
import dev.ostrom.server.runners.domain.ListRegisteredRunnersUseCase
import dev.ostrom.server.runners.domain.ListRegisteredRunnersUseCaseImpl
import dev.ostrom.server.runners.domain.ListRunnersUseCase
import dev.ostrom.server.runners.domain.ListRunnersUseCaseImpl
import dev.ostrom.server.runners.domain.MeteredCreateCloudSession
import dev.ostrom.server.runners.domain.OpenTerminalUseCase
import dev.ostrom.server.runners.domain.OpenTerminalUseCaseImpl
import dev.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import dev.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import dev.ostrom.server.runners.domain.RecordCloudSessionActivityUseCase
import dev.ostrom.server.runners.domain.RecordCloudSessionActivityUseCaseImpl
import dev.ostrom.server.runners.domain.RegisterRunnerUseCase
import dev.ostrom.server.runners.domain.RegisterRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCase
import dev.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCaseImpl
import dev.ostrom.server.runners.domain.RegisteredRunnersRepository
import dev.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import dev.ostrom.server.runners.domain.RunnerAssignmentsRepository
import dev.ostrom.server.runners.domain.RunnerHeartbeatUseCase
import dev.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.domain.RunnerWorkSignals
import dev.ostrom.server.runners.domain.RuntimeDriver
import dev.ostrom.server.runners.domain.TerminalActivity
import dev.ostrom.server.runners.domain.TerminalTickets
import dev.ostrom.server.runners.infrastructure.BillingCloudPlanProvider
import dev.ostrom.server.runners.infrastructure.BillingCloudUsageMeter
import dev.ostrom.server.runners.infrastructure.CloudSessionAutomationLauncher
import dev.ostrom.server.runners.infrastructure.CloudSessionsRepositoryImpl
import dev.ostrom.server.runners.infrastructure.DockerExecutionPlan
import dev.ostrom.server.runners.infrastructure.InMemoryTerminalTickets
import dev.ostrom.server.runners.infrastructure.InProcessRunnerWorkSignals
import dev.ostrom.server.runners.infrastructure.ReconcilerWorker
import dev.ostrom.server.runners.infrastructure.RegisteredRunnersRepositoryImpl
import dev.ostrom.server.runners.infrastructure.RegisteredRuntimeDriver
import dev.ostrom.server.runners.infrastructure.RunnerAssignmentsRepositoryImpl
import dev.ostrom.server.runners.infrastructure.RunnerTokensImpl
import dev.ostrom.server.runners.infrastructure.RunnersConfig
import dev.ostrom.server.runners.infrastructure.RunnersMigrations
import dev.ostrom.server.runners.infrastructure.RuntimeDrivers
import dev.ostrom.server.runners.infrastructure.TerminalRelay
import dev.ostrom.server.runners.presentation.CloudSessionsRoutesDependencies
import dev.ostrom.server.runners.presentation.RunnerRuntime
import dev.ostrom.server.runners.presentation.RunnersRoutesDependencies
import dev.ostrom.server.runners.presentation.TerminalRoutesDependencies
import dev.ostrom.server.runners.presentation.cloudSessionsRoutes
import dev.ostrom.server.runners.presentation.runnersRoutes
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
 * execution (Kubernetes namespace `ostrom`, a local Docker container, or a container on one of the organization's
 * registered runners, `ostrom-runner machine`), with a workspace volume that survives
 * pauses; the runner in the pod reports through the runner channel; the free plan's guards pause idle, overlong or
 * over-budget sessions.
 */
object RunnersModule : ServerModule {
    override val name: String = "runners"

    override val koinModule: Module = module {
        single<RunnersConfig> { RunnersConfig.from(get<ConfigSource>()) }
        single<CloudSessionsRepository> { CloudSessionsRepositoryImpl(get()) }
        single<RunnerTokens> { RunnerTokensImpl() }
        // Billing's plans and hours counter when the billing module is there (always on a full server); the free
        // plan and the sessions' own monthly counter otherwise.
        single<CloudPlanProvider> {
            val limits = getOrNull<GetPlanLimitsUseCase>()
            val plans = if (limits == null) FreeCloudPlanProvider() else BillingCloudPlanProvider(limits)
            AllowlistedCloudPlanProvider(get<RunnersConfig>().allowedOrgs, plans)
        }
        single<CloudUsageMeter> {
            val read = getOrNull<GetAgentUsageUseCase>()
            if (read == null) RepositoryCloudUsageMeter(get()) else BillingCloudUsageMeter(read, get())
        }
        single<RunnerAssignmentsRepository> { RunnerAssignmentsRepositoryImpl(get()) }
        single<ExecutionPlanner> {
            // The registered runner's own engine chooses the network; the server only says where it is reached.
            val config = get<RunnersConfig>()
            DockerExecutionPlan(null, config.serverUrl, config.runnerImage)
        }
        single<RunnerWorkSignals> { InProcessRunnerWorkSignals(get(), get()) }
        single<RuntimeDriver> { RuntimeDrivers.create(get(), { RegisteredRuntimeDriver(get(), get(), get()) }) }
        single<CreateCloudSessionUseCase> {
            MeteredCreateCloudSession(CreateCloudSessionUseCaseImpl(get(), get(), get(), get()), get())
        }
        single<GetCloudSessionUseCase> { GetCloudSessionUseCaseImpl(get(), get()) }
        single<ListCloudSessionsUseCase> { ListCloudSessionsUseCaseImpl(get()) }
        single<ChangeCloudSessionUseCase> { ChangeCloudSessionUseCaseImpl(get(), get(), get(), get()) }
        single<CheckRunnerTokenUseCase> { CheckRunnerTokenUseCaseImpl(get(), get()) }
        single<RunnerHeartbeatUseCase> { RunnerHeartbeatUseCaseImpl(get(), get(), get(), get()) }
        single<RecordCloudSessionActivityUseCase> { RecordCloudSessionActivityUseCaseImpl(get(), get()) }
        single<ReconcileCloudSessionUseCase> {
            ReconcileCloudSessionUseCaseImpl(get(), get(), get(), get(), get(), get())
        }
        single { ReconcilerWorker(get(), get(), get(), get()) }
        single { CloudSessionsRoutesDependencies(get(), get(), get(), get(), get(), get(), get()) }
        single<ListRunnersUseCase> { ListRunnersUseCaseImpl(get(), get()) }
        single<RegisteredRunnersRepository> { RegisteredRunnersRepositoryImpl(get()) }
        single<ListRegisteredRunnersUseCase> { ListRegisteredRunnersUseCaseImpl(get(), get()) }
        single<RegisterRunnerUseCase> { RegisterRunnerUseCaseImpl(get(), get(), get()) }
        single<DeleteRunnerUseCase> { DeleteRunnerUseCaseImpl(get()) }
        single<RegisteredRunnerHeartbeatUseCase> {
            RegisteredRunnerHeartbeatUseCaseImpl(get(), get(), get(), get(), get(), get(), get())
        }
        single {
            val config = get<RunnersConfig>()
            val runtime = RunnerRuntime(config.runtimeLabel, config.runnerImage)
            RunnersRoutesDependencies(get(), runtime, get(), get(), get(), get(), get(), get())
        }
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
        route.runnersRoutes(koin.get())
        route.terminalRoutes(koin.get())
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val worker = koin.get<ReconcilerWorker>()
        // Undispatched: the loop is running, up to its first query, when the server starts serving.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { worker.run() }
        val signals = koin.get<RunnerWorkSignals>()
        scope.launch { signals.watch() }
    }
}
