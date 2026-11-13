// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sessions

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.observability.CachedCount
import run.ostrom.server.kernel.observability.OstromMetrics
import run.ostrom.server.kernel.sessions.AgentSessions
import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.sessions.domain.services.NoSessionCosts
import run.ostrom.server.sessions.domain.services.SessionCosts
import run.ostrom.server.sessions.domain.usecases.CloseSessionUseCase
import run.ostrom.server.sessions.domain.usecases.CloseSessionUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.EndSessionUseCase
import run.ostrom.server.sessions.domain.usecases.EndSessionUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.GetSessionUseCase
import run.ostrom.server.sessions.domain.usecases.GetSessionUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.ListSessionsUseCase
import run.ostrom.server.sessions.domain.usecases.ListSessionsUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.ReopenSessionUseCase
import run.ostrom.server.sessions.domain.usecases.ReopenSessionUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.ReportSeenUseCase
import run.ostrom.server.sessions.domain.usecases.ReportSeenUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.SessionAliveUseCase
import run.ostrom.server.sessions.domain.usecases.SessionAliveUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.SessionSegments
import run.ostrom.server.sessions.domain.usecases.StartSessionUseCase
import run.ostrom.server.sessions.domain.usecases.StartSessionUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCase
import run.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCaseImpl
import run.ostrom.server.sessions.infrastructure.ActiveSessionsCount
import run.ostrom.server.sessions.infrastructure.PostgresSessionsRepository
import run.ostrom.server.sessions.infrastructure.SessionsAgentDirectory
import run.ostrom.server.sessions.infrastructure.SessionsGateGuard
import run.ostrom.server.sessions.infrastructure.SessionsMigrations
import run.ostrom.server.sessions.presentation.SessionsRoutesDependencies
import run.ostrom.server.sessions.presentation.sessionsRoutes
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
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
        single<EndSessionUseCase> { EndSessionUseCaseImpl(get(), get(), getAll<SessionEndListener>()) }
        single<AgentSessions> { SessionsAgentDirectory(get()) }
        single<SessionAliveUseCase> { SessionAliveUseCaseImpl(get(), get()) }
        single<ReopenSessionUseCase> { ReopenSessionUseCaseImpl(get(), get()) }
        single<ReportSeenUseCase> { ReportSeenUseCaseImpl(get(), get()) }
        single<GetSessionUseCase> { GetSessionUseCaseImpl(get(), get(), get()) }
        single<ListSessionsUseCase> { ListSessionsUseCaseImpl(get()) }
        single<SweepStaleSessionsUseCase> { SweepStaleSessionsUseCaseImpl(get(), get(), get(), get()) }
        single(named(ACTIVE_SESSIONS)) { CachedCount(ActiveSessionsCount(get(), get())::read) }
        single { SessionsRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = SessionsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.sessionsRoutes(koin.get(), koin.get())
    }

    /** Agent sessions. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.SESSIONS)

    /**
     * Closes the lost sessions every 30 s; a failing run is logged by the server's scope and the next one runs. Keeps
     * the count of the `ostrom.sessions.active{where="local"}` gauge the same way.
     */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val sweep = koin.get<SweepStaleSessionsUseCase>()
        scope.launch {
            while (true) {
                delay(SweepStaleSessionsUseCaseImpl.EVERY)
                supervisorScope { launch { sweep() } }
            }
        }
        val active = koin.get<CachedCount>(named(ACTIVE_SESSIONS))
        koin.get<OstromMetrics>().observeSessionsActive("local", active::value)
        active.refreshEvery(scope)
    }

    /** The Koin name of the running sessions' count. */
    const val ACTIVE_SESSIONS: String = "sessions.active"
}

/** The cost the usage module measures when it is there; without it, none ([NoSessionCosts]), never the agent's. */
internal fun Scope.sessionCosts(): SessionCosts = getOrNull<SessionCosts>() ?: NoSessionCosts()
