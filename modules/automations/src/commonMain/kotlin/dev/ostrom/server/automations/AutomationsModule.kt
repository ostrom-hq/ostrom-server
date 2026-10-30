// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.AnswerWaitUseCase
import dev.ostrom.server.automations.domain.AutomationLauncher
import dev.ostrom.server.automations.domain.AutomationsRepository
import dev.ostrom.server.automations.domain.ManageAutomationsUseCase
import dev.ostrom.server.automations.domain.ManageAutomationsUseCaseImpl
import dev.ostrom.server.automations.domain.RunAutomationsUseCase
import dev.ostrom.server.automations.domain.RunAutomationsUseCaseImpl
import dev.ostrom.server.automations.domain.WebhookSecrets
import dev.ostrom.server.automations.domain.AnswerWaitUseCaseImpl
import dev.ostrom.server.automations.domain.AskWaitUseCase
import dev.ostrom.server.automations.domain.AskWaitUseCaseImpl
import dev.ostrom.server.automations.domain.MemberDirectory
import dev.ostrom.server.automations.domain.ReadWaitsUseCase
import dev.ostrom.server.automations.domain.ReadWaitsUseCaseImpl
import dev.ostrom.server.automations.domain.WaitEvents
import dev.ostrom.server.automations.domain.WaitsRepository
import dev.ostrom.server.automations.infrastructure.AutomationScheduler
import dev.ostrom.server.automations.infrastructure.AutomationsMigrations
import dev.ostrom.server.automations.infrastructure.AutomationsRepositoryImpl
import dev.ostrom.server.automations.infrastructure.NoOtherMembers
import dev.ostrom.server.automations.infrastructure.SilentWaitEvents
import dev.ostrom.server.automations.infrastructure.UnavailableLauncher
import dev.ostrom.server.automations.infrastructure.WaitsRepositoryImpl
import dev.ostrom.server.automations.infrastructure.WebhookSecretsImpl
import dev.ostrom.server.automations.presentation.AutomationsRoutesDependencies
import dev.ostrom.server.automations.presentation.automationsRoutes
import dev.ostrom.server.automations.presentation.WaitsRoutesDependencies
import dev.ostrom.server.automations.presentation.waitsRoutes
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
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
 * A [MemberDirectory], [WaitEvents] or [AutomationLauncher] bound by another module replaces the defaults: until
 * then a wait is only asked of its asker, no one is told of it, and an automation's runs fail
 * `cloud_sessions_unavailable`.
 */
object AutomationsModule : ServerModule {
    override val name: String = "automations"

    override val koinModule: Module = module {
        single<WaitsRepository> { WaitsRepositoryImpl(get()) }
        single<AskWaitUseCase> {
            AskWaitUseCaseImpl(
                get(),
                getOrNull<MemberDirectory>() ?: NoOtherMembers,
                getOrNull<WaitEvents>() ?: SilentWaitEvents,
                get(),
            )
        }
        single<ReadWaitsUseCase> { ReadWaitsUseCaseImpl(get()) }
        single<AnswerWaitUseCase> { AnswerWaitUseCaseImpl(get(), getOrNull<WaitEvents>() ?: SilentWaitEvents, get()) }
        single { WaitsRoutesDependencies(get(), get(), get(), get()) }
        single<AutomationsRepository> { AutomationsRepositoryImpl(get()) }
        single<WebhookSecrets> { WebhookSecretsImpl(get()) }
        single<ManageAutomationsUseCase> { ManageAutomationsUseCaseImpl(get(), get(), get()) }
        single<RunAutomationsUseCase> {
            RunAutomationsUseCaseImpl(
                get(),
                getOrNull<AutomationLauncher>() ?: UnavailableLauncher,
                get(),
                getOrNull<MemberDirectory>(),
                get(),
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

    override fun start(scope: CoroutineScope, koin: Koin) {
        val scheduler = koin.get<AutomationScheduler>()
        scope.launch { scheduler.run() }
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
