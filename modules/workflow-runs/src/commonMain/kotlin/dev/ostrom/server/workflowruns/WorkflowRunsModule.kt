// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import dev.ostrom.server.workflowruns.domain.services.NotificationChannels
import dev.ostrom.server.workflowruns.domain.services.RunLaunches
import dev.ostrom.server.workflowruns.domain.services.RunPackages
import dev.ostrom.server.workflowruns.domain.services.RunSessions
import dev.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCase
import dev.ostrom.server.workflowruns.domain.usecases.GetReceiptBundleUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ListChecksUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListChecksUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ReportCheckUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCase
import dev.ostrom.server.workflowruns.domain.usecases.ReportRunnerCheckUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.RunAccess
import dev.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCaseImpl
import dev.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCase
import dev.ostrom.server.workflowruns.domain.usecases.SignWorkflowReceiptUseCaseImpl
import dev.ostrom.server.workflowruns.infrastructure.CompilerRunPackages
import dev.ostrom.server.workflowruns.infrastructure.LaunchesRunLaunches
import dev.ostrom.server.workflowruns.infrastructure.LogChannels
import dev.ostrom.server.workflowruns.infrastructure.PostgresWorkflowRunsRepository
import dev.ostrom.server.workflowruns.infrastructure.SessionsRunSessions
import dev.ostrom.server.workflowruns.infrastructure.WorkflowRunsMigrations
import dev.ostrom.server.workflowruns.presentation.WorkflowRunsRoutesDependencies
import dev.ostrom.server.workflowruns.presentation.workflowRunsRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The workflow-runs module: what the compiled workflows did in a session, reported by the launcher's hooks (check
 * runs, notifications), the session's signed workflow receipt, and the receipt bundle `ostrom verify` reads offline.
 * Needs the gates, sessions, receipts and compiler modules, the launches module (its
 * [dev.ostrom.server.gates.domain.services.GateActorResolver] and the cloud session of a launch) and the runners module
 * (the runner token of a check run the runner reports itself, `isolated_runner`).
 */
object WorkflowRunsModule : ServerModule {
    override val name: String = "workflow-runs"

    override val koinModule: Module = module {
        single<WorkflowRunsRepository> { PostgresWorkflowRunsRepository(get()) }
        single<RunPackages> { CompilerRunPackages(get()) }
        single<RunSessions> { SessionsRunSessions(get(), get()) }
        single { RunAccess(get(), get()) }
        single<ReportCheckUseCase> { ReportCheckUseCaseImpl(get(), get(), get()) }
        single<ListChecksUseCase> { ListChecksUseCaseImpl(get(), get()) }
        single<SendNotificationUseCase> { SendNotificationUseCaseImpl(get(), get(), get(), get()) }
        single<ListNotificationsUseCase> { ListNotificationsUseCaseImpl(get(), get()) }
        single<SignWorkflowReceiptUseCase> { SignWorkflowReceiptUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<GetReceiptBundleUseCase> { GetReceiptBundleUseCaseImpl(get(), get(), get(), get(), get()) }
        single<RunLaunches> { LaunchesRunLaunches(get()) }
        single<ReportRunnerCheckUseCase> { ReportRunnerCheckUseCaseImpl(get(), get(), get()) }
        single {
            WorkflowRunsRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
        }
    }

    override val migrations: List<SchemaMigration> = WorkflowRunsMigrations

    /** Binds [LogChannels] unless a loaded module (an integration) delivers the notifications. */
    override suspend fun initialize(koin: Koin) {
        if (koin.getOrNull<NotificationChannels>() == null) {
            koin.loadModules(listOf(module { single<NotificationChannels> { LogChannels() } }))
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.workflowRunsRoutes(koin.get())
    }
}
