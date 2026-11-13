// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.automations.domain.AutomationWorkflows
import run.ostrom.server.automations.domain.NoWorkflows
import run.ostrom.server.automations.domain.WorkflowsStartedByAutomations
import run.ostrom.server.automations.domain.CancelSessionWaitsUseCaseImpl
import run.ostrom.server.automations.domain.AutomationsRepository
import run.ostrom.server.automations.domain.ImportAutomationsUseCase
import run.ostrom.server.automations.domain.ImportAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.ManageAutomationsUseCase
import run.ostrom.server.automations.domain.ManageAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.RunAutomationsUseCase
import run.ostrom.server.automations.domain.RunAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.WebhookSecrets
import run.ostrom.server.automations.domain.AnswerWaitUseCaseImpl
import run.ostrom.server.automations.domain.AskWaitUseCase
import run.ostrom.server.automations.domain.AskWaitUseCaseImpl
import run.ostrom.server.automations.domain.ReadWaitsUseCase
import run.ostrom.server.automations.domain.ReadWaitsUseCaseImpl
import run.ostrom.server.automations.domain.ReceiveWaitEventUseCase
import run.ostrom.server.automations.domain.ReceiveWaitEventUseCaseImpl
import run.ostrom.server.automations.domain.ResolveDueWaitsUseCase
import run.ostrom.server.automations.domain.ResolveDueWaitsUseCaseImpl
import run.ostrom.server.automations.domain.WaitEventRateLimit
import run.ostrom.server.automations.domain.WaitEventSecrets
import run.ostrom.server.automations.domain.TimedWaitsRepository
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.domain.WaitEvents
import run.ostrom.server.automations.domain.WaitSessions
import run.ostrom.server.automations.domain.WaitSessionsImpl
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.automations.domain.WakeWaitsUseCase
import run.ostrom.server.automations.domain.WakeWaitsUseCaseImpl
import run.ostrom.server.automations.infrastructure.AutomationScheduler
import run.ostrom.server.automations.infrastructure.AutomationsMigrations
import run.ostrom.server.automations.infrastructure.AutomationsRepositoryImpl
import run.ostrom.server.automations.infrastructure.DueWaitsWorker
import run.ostrom.server.automations.infrastructure.NoAgentSessions
import run.ostrom.server.automations.infrastructure.NoMemberLogins
import run.ostrom.server.automations.infrastructure.NoOtherMembers
import run.ostrom.server.automations.infrastructure.NoWaitAgents
import run.ostrom.server.automations.infrastructure.SilentWaitEvents
import run.ostrom.server.automations.infrastructure.TimedWaitsRepositoryImpl
import run.ostrom.server.automations.infrastructure.UnavailableLauncher
import run.ostrom.server.automations.infrastructure.WaitEventSecretsImpl
import run.ostrom.server.automations.infrastructure.WaitsRepositoryImpl
import run.ostrom.server.automations.infrastructure.WebhookSecretsImpl
import run.ostrom.server.automations.presentation.AutomationsRoutesDependencies
import run.ostrom.server.automations.presentation.automationsRoutes
import run.ostrom.server.automations.presentation.WaitsRoutesDependencies
import run.ostrom.server.automations.presentation.waitsRoutes
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.sessions.AgentSessions
import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.kernel.workflows.AutomationUsage
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The automations module: waits ("Waiting for you": a question to a person, asked by an agent with `ask_human` or a
 * member, the first answer wins), and automations, which start a cloud agent session on a cron or a signed webhook.
 *
 * A [MemberDirectory], [MemberLogins], [AgentSessions], [WaitEvents], [AutomationLauncher] or [WaitAgentResolver]
 * bound by another module replaces the defaults: until then a wait is only asked of its asker and by id, a member asks
 * in no session, no one is told of it, an automation's runs fail `cloud_sessions_unavailable`, and no agent token asks
 * through the waits MCP server. An [AutomationWorkflows] bound by the module that runs workflows lets an action start
 * a workflow run; until then no workflow exists for one. It binds [AutomationUsage], so a workflow an automation
 * starts is not deleted under it. Its [SessionEndListener] cancels the open waits of a session that ends.
 */
object AutomationsModule : ServerModule {
    override val name: String = "automations"

    override val koinModule: Module = module {
        single<WaitsRepository> { WaitsRepositoryImpl(get(), get()) }
        single<TimedWaitsRepository> { TimedWaitsRepositoryImpl(get(), get()) }
        single<WaitSessions> {
            WaitSessionsImpl(
                getOrNull<AgentSessions>() ?: NoAgentSessions,
                getOrNull<MemberLogins>() ?: NoMemberLogins,
            )
        }
        single<AskWaitUseCase> {
            AskWaitUseCaseImpl(
                get(),
                getOrNull<MemberDirectory>() ?: NoOtherMembers,
                getOrNull<WaitEvents>() ?: SilentWaitEvents,
                get(),
                get(),
                get(),
            )
        }
        single<WaitEventSecrets> { WaitEventSecretsImpl() }
        single<ReceiveWaitEventUseCase> {
            ReceiveWaitEventUseCaseImpl(
                get(),
                get(),
                WaitEventRateLimit(get()),
                getOrNull<WaitEvents>() ?: SilentWaitEvents,
                get(),
            )
        }
        single<ResolveDueWaitsUseCase> {
            ResolveDueWaitsUseCaseImpl(get(), getOrNull<WaitEvents>() ?: SilentWaitEvents, get())
        }
        single { DueWaitsWorker(get(), get()) }
        single<ReadWaitsUseCase> { ReadWaitsUseCaseImpl(get(), get()) }
        single<WakeWaitsUseCase> { WakeWaitsUseCaseImpl(get(), get(), get()) }
        single<SessionEndListener> {
            CancelSessionWaitsUseCaseImpl(get(), getOrNull<WaitEvents>() ?: SilentWaitEvents, get())
        }
        single<AnswerWaitUseCase> { AnswerWaitUseCaseImpl(get(), getOrNull<WaitEvents>() ?: SilentWaitEvents, get()) }
        single {
            WaitsRoutesDependencies(
                get(),
                get(),
                get(),
                get(),
                getOrNull<WaitAgentResolver>() ?: NoWaitAgents,
                get(),
                get(),
                publicUrlOf(get()),
            )
        }
        single<AutomationsRepository> { AutomationsRepositoryImpl(get()) }
        single<WebhookSecrets> { WebhookSecretsImpl(get()) }
        single<ManageAutomationsUseCase> {
            ManageAutomationsUseCaseImpl(get(), get(), get(), getOrNull<AutomationWorkflows>() ?: NoWorkflows)
        }
        single<ImportAutomationsUseCase> {
            ImportAutomationsUseCaseImpl(get(), get(), getOrNull<AutomationWorkflows>() ?: NoWorkflows)
        }
        single<AutomationUsage> { WorkflowsStartedByAutomations(get()) }
        single<RunAutomationsUseCase> {
            RunAutomationsUseCaseImpl(
                get(),
                getOrNull<AutomationLauncher>() ?: UnavailableLauncher,
                get(),
                getOrNull<MemberDirectory>(),
                get(),
                workflows = getOrNull<AutomationWorkflows>() ?: NoWorkflows,
            )
        }
        single { AutomationScheduler(get(), get()) }
        single {
            AutomationsRoutesDependencies(get(), get(), get(), publicUrlOf(get()))
        }
    }

    override val migrations: List<SchemaMigration> = AutomationsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.waitsRoutes(koin.get())
        route.automationsRoutes(koin.get())
    }

    /**
     * Automations, the waits (`/api/v1/waits`) it serves and the wake of an agent's session, and the waits that resolve
     * by themselves (`wait_for`, the event address), whose clock [start] runs.
     */
    override suspend fun features(koin: Koin): Set<String> = setOf(
        ServerFeatures.AUTOMATIONS,
        ServerFeatures.WAITS,
        ServerFeatures.WAITS_WAKE,
        ServerFeatures.WAITS_TIMED,
    )

    override fun start(scope: CoroutineScope, koin: Koin) {
        val scheduler = koin.get<AutomationScheduler>()
        val dueWaits = koin.get<DueWaitsWorker>()
        scope.launch { scheduler.run() }
        scope.launch { dueWaits.run() }
    }
}

/**
 * The server's public URL, without a trailing slash, for the webhook URLs; empty when unset (relative URLs).
 *
 * @param source The configuration.
 *
 * @return The URL.
 */
internal fun publicUrlOf(source: ConfigSource): String = (source["OSTROM_PUBLIC_URL"] ?: "").trimEnd('/')
