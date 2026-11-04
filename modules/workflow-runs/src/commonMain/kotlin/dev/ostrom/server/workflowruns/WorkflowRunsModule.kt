// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.server.gates.domain.services.GateCheckProofs
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.workflowruns.domain.engine.NodePlayers
import dev.ostrom.server.workflowruns.domain.engine.PlayRunUseCase
import dev.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import dev.ostrom.server.workflowruns.domain.engine.StartedRunChildren
import dev.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import dev.ostrom.server.workflowruns.domain.engine.TickRunsUseCaseImpl
import dev.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import dev.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import dev.ostrom.server.workflowruns.domain.services.NotificationChannels
import dev.ostrom.server.workflowruns.domain.services.RunAgents
import dev.ostrom.server.workflowruns.domain.services.RunChildren
import dev.ostrom.server.workflowruns.domain.services.RunGates
import dev.ostrom.server.workflowruns.domain.services.RunLaunches
import dev.ostrom.server.workflowruns.domain.services.RunPackages
import dev.ostrom.server.workflowruns.domain.services.RunSessions
import dev.ostrom.server.workflowruns.domain.services.RunWaits
import dev.ostrom.server.workflowruns.domain.services.RunWorkflows
import dev.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCase
import dev.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCase
import dev.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.GetWorkflowRunUseCase
import dev.ostrom.server.workflowruns.domain.usecases.GetWorkflowRunUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ListChecksUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListChecksUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ListWorkflowRunsUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListWorkflowRunsUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.RunAccess
import dev.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase
import dev.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCaseImpl
import dev.ostrom.server.workflowruns.infrastructure.AutomationsRunWaits
import dev.ostrom.server.workflowruns.infrastructure.CloudRunAgents
import dev.ostrom.server.workflowruns.infrastructure.CompilerRunPackages
import dev.ostrom.server.workflowruns.infrastructure.CompilerRunWorkflows
import dev.ostrom.server.workflowruns.infrastructure.GatesRunGates
import dev.ostrom.server.workflowruns.infrastructure.LaunchesRunLaunches
import dev.ostrom.server.workflowruns.infrastructure.LogChannels
import dev.ostrom.server.workflowruns.infrastructure.PostgresRunEngineRepository
import dev.ostrom.server.workflowruns.infrastructure.PostgresStartedRunsRepository
import dev.ostrom.server.workflowruns.infrastructure.PostgresWorkflowRunsRepository
import dev.ostrom.server.workflowruns.infrastructure.RunEngineWorker
import dev.ostrom.server.workflowruns.infrastructure.RunsGateCheckProofs
import dev.ostrom.server.workflowruns.infrastructure.SessionsRunSessions
import dev.ostrom.server.workflowruns.infrastructure.WorkflowRunsMigrations
import dev.ostrom.server.workflowruns.presentation.WorkflowRunsRoutesDependencies
import dev.ostrom.server.workflowruns.presentation.workflowRunsRoutes
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
 * [dev.ostrom.server.gates.domain.services.GateActorResolver] and the cloud session of a launch) and the runners module
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
        single<RunSessions> { SessionsRunSessions(get(), get()) }
        single { RunAccess(get(), get(), get()) }
        single<ReportCheckUseCase> { ReportCheckUseCaseImpl(get(), get(), get()) }
        single<ListChecksUseCase> { ListChecksUseCaseImpl(get(), get()) }
        single<SendNotificationUseCase> { SendNotificationUseCaseImpl(get(), get(), get(), get()) }
        single<ListNotificationsUseCase> { ListNotificationsUseCaseImpl(get(), get()) }
        single<SignWorkflowReceiptUseCase> { SignWorkflowReceiptUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<GetReceiptBundleUseCase> { GetReceiptBundleUseCaseImpl(get(), get(), get(), get(), get()) }
        single<RunLaunches> { LaunchesRunLaunches(get()) }
        single<ReportRunnerCheckUseCase> { ReportRunnerCheckUseCaseImpl(get(), get(), get()) }
        single<GateCheckProofs> { RunsGateCheckProofs(get()) { get() } }
        single<StartedRunsRepository> { PostgresStartedRunsRepository(get(), get()) }
        single<RunEngineRepository> { PostgresRunEngineRepository(get(), get()) }
        single<RunAgents> { CloudRunAgents(get(), get(), get(), get()) }
        single<RunGates> { GatesRunGates(get(), get()) }
        single<RunWaits> { AutomationsRunWaits(get(), get(), get(), get()) }
        single<RunChildren> { StartedRunChildren(get(), get(), get(), get()) }
        single { NodePlayers(get(), get(), get(), get()) }
        single<PlayRunUseCase> { PlayRunUseCaseImpl(get(), get(), get()) }
        single<TickRunsUseCase> { TickRunsUseCaseImpl(get(), get(), get(), get()) }
        single<GetWorkflowRunUseCase> { GetWorkflowRunUseCaseImpl(get()) }
        single<CancelWorkflowRunUseCase> { CancelWorkflowRunUseCaseImpl(get(), get(), get()) }
        single<RunWorkflows> { CompilerRunWorkflows(get()) }
        single<StartWorkflowRunUseCase> { StartWorkflowRunUseCaseImpl(get(), get(), get()) }
        single<ListWorkflowRunsUseCase> { ListWorkflowRunsUseCaseImpl(get(), get()) }
        single {
            WorkflowRunsRoutesDependencies(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
                get(),
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
}
