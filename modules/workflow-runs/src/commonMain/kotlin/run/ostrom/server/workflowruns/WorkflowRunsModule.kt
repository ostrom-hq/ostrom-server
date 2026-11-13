// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.gates.domain.services.GateCheckProofs
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.workflowruns.domain.engine.NodePlayers
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCase
import run.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.engine.StartedRunChildren
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import run.ostrom.server.workflowruns.domain.engine.TickRunsUseCaseImpl
import run.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import run.ostrom.server.workflowruns.domain.services.NotificationChannels
import run.ostrom.server.workflowruns.domain.services.RunAgents
import run.ostrom.server.workflowruns.domain.services.RunChildren
import run.ostrom.server.workflowruns.domain.services.RunGates
import run.ostrom.server.workflowruns.domain.services.RunLaunches
import run.ostrom.server.workflowruns.domain.services.RunPackages
import run.ostrom.server.workflowruns.domain.services.RunSessions
import run.ostrom.server.workflowruns.domain.services.RunTodos
import run.ostrom.server.workflowruns.domain.services.RunWaits
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import run.ostrom.server.workflowruns.domain.services.StepSessions
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.DeliverRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.DeliverRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.DeliverWorkflowSessionUseCase
import run.ostrom.server.workflowruns.domain.usecases.DeliverWorkflowSessionUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCase
import run.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.GetWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.GetWorkflowRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.ListChecksUseCase
import run.ostrom.server.workflowruns.domain.usecases.ListChecksUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCase
import run.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.ListWorkflowRunsUseCase
import run.ostrom.server.workflowruns.domain.usecases.ListWorkflowRunsUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.PendingWorkflowSessionUseCase
import run.ostrom.server.workflowruns.domain.usecases.PendingWorkflowSessionUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCase
import run.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCase
import run.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.RunAccess
import run.ostrom.server.workflowruns.domain.usecases.RunDeliveries
import run.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCase
import run.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.SessionRuns
import run.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCase
import run.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCaseImpl
import run.ostrom.server.workflowruns.infrastructure.AutomationsRunWaits
import run.ostrom.server.workflowruns.infrastructure.CloudRunAgents
import run.ostrom.server.workflowruns.infrastructure.CloudStepSessions
import run.ostrom.server.workflowruns.infrastructure.CompilerRunPackages
import run.ostrom.server.workflowruns.infrastructure.CompilerRunWorkflows
import run.ostrom.server.workflowruns.infrastructure.GatesRunGates
import run.ostrom.server.workflowruns.infrastructure.LaunchesRunLaunches
import run.ostrom.server.workflowruns.infrastructure.LogChannels
import run.ostrom.server.workflowruns.infrastructure.PostgresRunEngineRepository
import run.ostrom.server.workflowruns.infrastructure.PostgresStartedRunsRepository
import run.ostrom.server.workflowruns.infrastructure.PostgresWorkflowRunsRepository
import run.ostrom.server.workflowruns.infrastructure.RunEngineWorker
import run.ostrom.server.workflowruns.infrastructure.RunsGateCheckProofs
import run.ostrom.server.workflowruns.infrastructure.SessionsRunSessions
import run.ostrom.server.workflowruns.infrastructure.WorkflowRunsMigrations
import run.ostrom.server.workflowruns.presentation.WorkflowRunsRoutesDependencies
import run.ostrom.server.workflowruns.presentation.workflowRunsRoutes
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The workflow-runs module: what the compiled workflows did in a session, reported by the launcher's hooks (check
 * runs, notifications), the session's signed workflow receipt, and the receipt bundle `ostrom verify` reads offline.
 * Needs the gates, sessions, receipts and compiler modules, the launches module (its
 * [run.ostrom.server.gates.domain.services.GateActorResolver] and the cloud session of a launch) and the runners module
 * (the runner token of a check run the runner reports itself, `isolated_runner`). It also starts, lists, reads and
 * cancels the runs of the workflows, read from the compiler module, and plays them: an engine on every replica leases
 * each run due to one replica, starts an agent node's cloud session through the runners module, opens a gate node's
 * gate through the gates module, asks a wait node's question through the automations module's waits, starts a
 * workflow node's child run, and writes each step with its audit line (the identity module's log).
 */
object WorkflowRunsModule : ServerModule {
    override val name: String = "workflow-runs"

    override val koinModule: Module = module {
        single<WorkflowRunsRepository> { PostgresWorkflowRunsRepository(get()) }
        single<RunPackages> { CompilerRunPackages(get()) }
        single<RunSessions> { SessionsRunSessions(get(), get(), get()) }
        single { RunAccess(get(), get(), get()) }
        single<ReportCheckUseCase> { ReportCheckUseCaseImpl(get(), get(), get()) }
        single<ListChecksUseCase> { ListChecksUseCaseImpl(get(), get()) }
        single<SendNotificationUseCase> { SendNotificationUseCaseImpl(get(), get(), get(), get()) }
        single<ListNotificationsUseCase> { ListNotificationsUseCaseImpl(get(), get()) }
        single<SignWorkflowReceiptUseCase> { SignWorkflowReceiptUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<GetReceiptBundleUseCase> { GetReceiptBundleUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<RunLaunches> { LaunchesRunLaunches(get()) }
        single<ReportRunnerCheckUseCase> { ReportRunnerCheckUseCaseImpl(get(), get(), get()) }
        single<GateCheckProofs> { RunsGateCheckProofs(get()) { get() } }
        single<StartedRunsRepository> { PostgresStartedRunsRepository(get(), get()) }
        single<RunEngineRepository> { PostgresRunEngineRepository(get(), get()) }
        single<RunAgents> { CloudRunAgents(get(), get(), get(), get()) }
        single<RunGates> { GatesRunGates(get(), get()) }
        single<RunWaits> { AutomationsRunWaits(get(), get(), get(), get()) }
        single<RunChildren> { StartedRunChildren(get(), get(), get(), get()) }
        single { NodePlayers(get(), get(), get(), get(), get()) }
        single<PlayRunUseCase> { PlayRunUseCaseImpl(get(), get(), get()) }
        single<TickRunsUseCase> { TickRunsUseCaseImpl(get(), get(), get(), get()) }
        single<GetWorkflowRunUseCase> { GetWorkflowRunUseCaseImpl(get()) }
        single<CancelWorkflowRunUseCase> { CancelWorkflowRunUseCaseImpl(get(), get(), get()) }
        single<RunWorkflows> { CompilerRunWorkflows(get()) }
        single<StartWorkflowRunUseCase> { StartWorkflowRunUseCaseImpl(get(), get(), get()) }
        single<ListWorkflowRunsUseCase> { ListWorkflowRunsUseCaseImpl(get(), get()) }
        // No module keeps the sessions' todos yet: no RunTodos is bound, and a delivery is never refused `todo_open`.
        single { RunDeliveries(get(), get(), getOrNull<RunTodos>(), get()) }
        single<DeliverRunUseCase> { DeliverRunUseCaseImpl(get(), get()) }
        single<StepSessions> { CloudStepSessions(get(), get(), get(), get(), get()) }
        single { SessionRuns(get(), get(), get()) }
        single<DeliverWorkflowSessionUseCase> { DeliverWorkflowSessionUseCaseImpl(get(), get()) }
        single<PendingWorkflowSessionUseCase> { PendingWorkflowSessionUseCaseImpl(get(), get()) }
        single {
            WorkflowRunsRoutesDependencies(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
                get(), get(), get(), get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = WorkflowRunsMigrations

    /** Binds [LogChannels] unless a loaded module (an integration) delivers the notifications. */
    override suspend fun initialize(koin: Koin) {
        if (koin.getOrNull<NotificationChannels>() == null) {
            koin.loadModules(listOf(module { single<NotificationChannels> { LogChannels() } }))
        }
    }

    /** Plays the runs of workflows: every replica runs the engine, each run leased to one of them at a time. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val worker = RunEngineWorker(koin.get(), koin.get())
        scope.launch { worker.run() }
    }

    override fun routes(route: Route, koin: Koin) {
        route.workflowRunsRoutes(koin.get())
    }

    /**
     * Workflow runs, the notifications the hooks ask for (`/api/v1/notifications`), and the delivery of an agent step
     * (`deliverRun`, `deliverWorkflowSession`, `pendingWorkflowSession` and the `ostrom-workflow` MCP server).
     */
    override suspend fun features(koin: Koin): Set<String> =
        setOf(ServerFeatures.WORKFLOWS, ServerFeatures.NOTIFICATIONS, ServerFeatures.WORKFLOWS_DELIVER)
}
