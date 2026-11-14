// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.infrastructure.CLOUD_ACCESS_SETTINGS
import run.ostrom.server.runners.infrastructure.CloudRuntimeRunnerDelivery
import run.ostrom.server.runners.infrastructure.SettingsAllowedOrgs
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.billing.domain.GetAgentUsageUseCase
import run.ostrom.server.billing.domain.GetPlanLimitsUseCase
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.runners.domain.AllowlistedCloudPlanProvider
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCaseImpl
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CloudUsageMeter
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.DeleteRunnerUseCase
import run.ostrom.server.runners.domain.DeleteRunnerUseCaseImpl
import run.ostrom.server.runners.domain.ExecutionPlanner
import run.ostrom.server.runners.domain.FreeCloudPlanProvider
import run.ostrom.server.runners.domain.GetCloudSessionUseCase
import run.ostrom.server.runners.domain.GetCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.IssueTerminalTicketUseCase
import run.ostrom.server.runners.domain.IssueTerminalTicketUseCaseImpl
import run.ostrom.server.runners.domain.ListCloudSessionsUseCase
import run.ostrom.server.runners.domain.ListCloudSessionsUseCaseImpl
import run.ostrom.server.runners.domain.ListRegisteredRunnersUseCase
import run.ostrom.server.runners.domain.ListRegisteredRunnersUseCaseImpl
import run.ostrom.server.runners.domain.ListRunnersUseCase
import run.ostrom.server.runners.domain.ListRunnersUseCaseImpl
import run.ostrom.server.runners.domain.MeteredCreateCloudSession
import run.ostrom.server.runners.domain.NoRunnerGitHubTokens
import run.ostrom.server.runners.domain.OpenTerminalUseCase
import run.ostrom.server.runners.domain.OpenTerminalUseCaseImpl
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RecordCloudSessionActivityUseCase
import run.ostrom.server.runners.domain.RecordCloudSessionActivityUseCaseImpl
import run.ostrom.server.runners.domain.RegisterRunnerUseCase
import run.ostrom.server.runners.domain.RegisterRunnerUseCaseImpl
import run.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCase
import run.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.RegisteredRunnersRepository
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.RunnerAssignmentsRepository
import run.ostrom.server.runners.domain.RunnerGitHubTokenUseCase
import run.ostrom.server.runners.domain.RunnerGitHubTokenUseCaseImpl
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCase
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.RunnerVariables
import run.ostrom.server.runners.domain.RunnerVariablesUseCase
import run.ostrom.server.runners.domain.RunnerVariablesUseCaseImpl
import run.ostrom.server.runners.domain.RunnerWorkSignals
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.SetCloudSessionGitHubAccessUseCase
import run.ostrom.server.runners.domain.SetCloudSessionGitHubAccessUseCaseImpl
import run.ostrom.server.runners.domain.TerminalActivity
import run.ostrom.server.runners.domain.TerminalTickets
import run.ostrom.server.runners.infrastructure.BillingCloudPlanProvider
import run.ostrom.server.runners.infrastructure.BillingCloudUsageMeter
import run.ostrom.server.runners.infrastructure.CloudSessionAutomationLauncher
import run.ostrom.server.runners.infrastructure.CloudSessionsRepositoryImpl
import run.ostrom.server.runners.infrastructure.DockerExecutionPlan
import run.ostrom.server.runners.infrastructure.InProcessRunnerWorkSignals
import run.ostrom.server.runners.infrastructure.KtorTerminalForwarder
import run.ostrom.server.runners.infrastructure.ReconcilerWorker
import run.ostrom.server.runners.infrastructure.RegisteredRunnersRepositoryImpl
import run.ostrom.server.runners.infrastructure.RegisteredRuntimeDriver
import run.ostrom.server.runners.infrastructure.RunnerAssignmentsRepositoryImpl
import run.ostrom.server.runners.infrastructure.RunnerTokensImpl
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.runners.infrastructure.RunnersMigrations
import run.ostrom.server.runners.infrastructure.RuntimeDrivers
import run.ostrom.server.runners.infrastructure.TerminalHostsImpl
import run.ostrom.server.runners.infrastructure.TerminalRelay
import run.ostrom.server.runners.infrastructure.TerminalReplica
import run.ostrom.server.runners.infrastructure.TerminalTicketsImpl
import run.ostrom.server.runners.infrastructure.replicaHttpClient
import run.ostrom.server.runners.infrastructure.runnerVariables
import run.ostrom.server.runners.presentation.CloudSessionsRoutesDependencies
import run.ostrom.server.runners.presentation.RunnerRuntime
import run.ostrom.server.runners.presentation.RunnersRoutesDependencies
import run.ostrom.server.runners.presentation.TerminalRoutesDependencies
import run.ostrom.server.runners.presentation.cloudSessionsRoutes
import run.ostrom.server.runners.presentation.runnersRoutes
import run.ostrom.server.runners.presentation.terminalRoutes
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

private val REPLICA_HTTP = named("runners.replica.http")

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
        single(named(CLOUD_ACCESS_SETTINGS.name)) { CLOUD_ACCESS_SETTINGS }
        single<CloudSessionsRepository> { CloudSessionsRepositoryImpl(get()) }
        single<RunnerTokens> { RunnerTokensImpl() }
        // Billing's plans and hours counter when the billing module is there (always on a full server); the free
        // plan and the sessions' own monthly counter otherwise.
        single<CloudPlanProvider> {
            val limits = getOrNull<GetPlanLimitsUseCase>()
            val plans = if (limits == null) FreeCloudPlanProvider() else BillingCloudPlanProvider(limits)
            AllowlistedCloudPlanProvider(SettingsAllowedOrgs(get()), plans)
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
        // The console's cloud runtime names the runner image (cloud module); OSTROM_RUNNERS_IMAGE while it names none.
        single<RunnerDeliverySource> {
            CloudRuntimeRunnerDelivery(getOrNull<CloudRuntimeSource>(), get<RunnersConfig>().runnerImage)
        }
        single<RuntimeDriver> { RuntimeDrivers.create(get(), { RegisteredRuntimeDriver(get(), get(), get()) }, get()) }
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
        // The members' GitHub access when the GitHub integration is there (always on a full server).
        single<SetCloudSessionGitHubAccessUseCase> { SetCloudSessionGitHubAccessUseCaseImpl(get(), get(), get()) }
        single<RunnerGitHubTokenUseCase> {
            RunnerGitHubTokenUseCaseImpl(get(), get(), getOrNull<RunnerGitHubTokens>() ?: NoRunnerGitHubTokens)
        }
        single<RunnerVariables> { runnerVariables(getOrNull<ResolveEnvironmentUseCase>()) }
        single<RunnerVariablesUseCase> { RunnerVariablesUseCaseImpl(get(), get(), get()) }
        single { CloudSessionsRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
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
        single<TerminalTickets> { TerminalTicketsImpl(get(), get()) }
        single<AuthenticateRunnerUseCase> { AuthenticateRunnerUseCaseImpl(get(), get()) }
        single<OpenTerminalUseCase> { OpenTerminalUseCaseImpl(get()) }
        single<IssueTerminalTicketUseCase> { IssueTerminalTicketUseCaseImpl(get(), get()) }
        single { TerminalActivity(get(), get()) }
        // Hops to the replica holding a runner's socket, only once replicas are told their URL (one replica: none).
        single(REPLICA_HTTP) { replicaHttpClient() } onClose { it?.close() }
        single {
            val replica = get<RunnersConfig>().replicaUrl?.let { url ->
                TerminalReplica(url, TerminalHostsImpl(get(), url), KtorTerminalForwarder(get(REPLICA_HTTP)))
            }
            TerminalRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), replica)
        }
        single<AutomationLauncher> { CloudSessionAutomationLauncher(get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = RunnersMigrations

    override fun routes(route: Route, koin: Koin) {
        route.cloudSessionsRoutes(koin.get())
        route.runnersRoutes(koin.get())
        route.terminalRoutes(koin.get())
    }

    /**
     * Registered runners; cloud runners only with a runtime driver configured (`OSTROM_RUNNERS_DRIVER`); their GitHub
     * access once members can link a GitHub account.
     */
    override suspend fun features(koin: Koin): Set<String> {
        if (koin.get<RunnersConfig>().driver == RunnersConfig.DriverKind.NONE) return setOf(ServerFeatures.RUNNERS)
        val github = koin.getOrNull<RunnerGitHubTokens>()?.available() == true
        // The members' values, once the secrets module resolves them.
        val variables = koin.getOrNull<ResolveEnvironmentUseCase>() != null
        return setOfNotNull(
            ServerFeatures.RUNNERS,
            ServerFeatures.RUNNERS_CLOUD,
            ServerFeatures.RUNNERS_CLOUD_GITHUB.takeIf { github },
            ServerFeatures.RUNNERS_CLOUD_VARIABLES.takeIf { variables },
        )
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val worker = koin.get<ReconcilerWorker>()
        // Undispatched: the loop is running, up to its first query, when the server starts serving.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { worker.run() }
        val signals = koin.get<RunnerWorkSignals>()
        scope.launch { signals.watch() }
    }
}
