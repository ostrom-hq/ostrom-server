// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.services.NoSessionCosts
import dev.ostrom.server.sessions.domain.services.SessionCosts
import dev.ostrom.server.sessions.domain.usecases.CloseSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.CloseSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.EndSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.EndSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.GetSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.GetSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.ListSessionsUseCase
import dev.ostrom.server.sessions.domain.usecases.ListSessionsUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.ReopenSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.ReopenSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.ReportSeenUseCase
import dev.ostrom.server.sessions.domain.usecases.ReportSeenUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.SessionAliveUseCase
import dev.ostrom.server.sessions.domain.usecases.SessionAliveUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.SessionSegments
import dev.ostrom.server.sessions.domain.usecases.StartSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.StartSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCase
import dev.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCaseImpl
import dev.ostrom.server.sessions.infrastructure.PostgresSessionsRepository
import dev.ostrom.server.sessions.infrastructure.SessionsGateGuard
import dev.ostrom.server.sessions.infrastructure.SessionsMigrations
import dev.ostrom.server.sessions.presentation.SessionsRoutesDependencies
import dev.ostrom.server.sessions.presentation.sessionsRoutes
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * The sessions module: the segments of the coding agent sessions (start, sign of life, end with a signed receipt,
 * reopening after a lost launcher), and the guard that refuses a gate in a closed session. Needs the gates module (the
 * gates a session asked, the callers) and the receipts module.
 */
object SessionsModule : ServerModule {
    override val name: String = "sessions"

    override val koinModule: Module = module {
        single<SessionsRepository> { PostgresSessionsRepository(get()) }
        single<GateSessions> { SessionsGateGuard(get()) }
        single<CloseSessionUseCase> {
            CloseSessionUseCaseImpl(get(), get(), get(), get(), sessionCosts(), get())
        }
        single { SessionSegments(get(), get(), get()) }
        single<StartSessionUseCase> { StartSessionUseCaseImpl(get(), get()) }
        single<EndSessionUseCase> { EndSessionUseCaseImpl(get(), get()) }
        single<SessionAliveUseCase> { SessionAliveUseCaseImpl(get(), get()) }
        single<ReopenSessionUseCase> { ReopenSessionUseCaseImpl(get(), get()) }
        single<ReportSeenUseCase> { ReportSeenUseCaseImpl(get(), get()) }
        single<GetSessionUseCase> { GetSessionUseCaseImpl(get(), get(), get()) }
        single<ListSessionsUseCase> { ListSessionsUseCaseImpl(get()) }
        single<SweepStaleSessionsUseCase> { SweepStaleSessionsUseCaseImpl(get(), get(), get(), get()) }
        single { SessionsRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = SessionsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.sessionsRoutes(koin.get())
    }

    /** Closes the lost sessions every 30 s; a failing run is logged by the server's scope and the next one runs. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val sweep = koin.get<SweepStaleSessionsUseCase>()
        scope.launch {
            while (true) {
                delay(SweepStaleSessionsUseCaseImpl.EVERY)
                supervisorScope { launch { sweep() } }
            }
        }
    }
}

/** The cost the usage module measures when it is there; without it, none ([NoSessionCosts]), never the agent's. */
internal fun Scope.sessionCosts(): SessionCosts = getOrNull<SessionCosts>() ?: NoSessionCosts()
