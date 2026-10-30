package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.AnswerWaitUseCase
import dev.ostrom.server.automations.domain.AnswerWaitUseCaseImpl
import dev.ostrom.server.automations.domain.AskWaitUseCase
import dev.ostrom.server.automations.domain.AskWaitUseCaseImpl
import dev.ostrom.server.automations.domain.MemberDirectory
import dev.ostrom.server.automations.domain.ReadWaitsUseCase
import dev.ostrom.server.automations.domain.ReadWaitsUseCaseImpl
import dev.ostrom.server.automations.domain.WaitEvents
import dev.ostrom.server.automations.domain.WaitsRepository
import dev.ostrom.server.automations.infrastructure.AutomationsMigrations
import dev.ostrom.server.automations.infrastructure.NoOtherMembers
import dev.ostrom.server.automations.infrastructure.SilentWaitEvents
import dev.ostrom.server.automations.infrastructure.WaitsRepositoryImpl
import dev.ostrom.server.automations.presentation.WaitsRoutesDependencies
import dev.ostrom.server.automations.presentation.waitsRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The automations module: waits ("Waiting for you": a question to a person, asked by an agent with `ask_human` or a
 * member, the first answer wins), and next automations with their cron and webhook triggers.
 *
 * A [MemberDirectory] or [WaitEvents] bound by another module replaces the defaults: until then a wait is only asked
 * of its asker, and no one is told of it.
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
    }

    override val migrations: List<SchemaMigration> = AutomationsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.waitsRoutes(koin.get())
    }
}
