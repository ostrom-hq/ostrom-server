// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.events.domain.OpenEventFeedUseCase
import run.ostrom.server.events.domain.OpenEventFeedUseCaseImpl
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.events.infrastructure.EventsMigrations
import run.ostrom.server.events.infrastructure.EventsOrgDeletionListener
import run.ostrom.server.events.infrastructure.EventsPurgeWorker
import run.ostrom.server.events.infrastructure.JournalOrgEventPublisher
import run.ostrom.server.events.infrastructure.OrgEventsRepositoryImpl
import run.ostrom.server.events.presentation.EventsRoutesDependencies
import run.ostrom.server.events.presentation.eventsRoutes
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule

/**
 * The events module: the organization's event feed of TeamCode (`team_events`), one gap-free sequence per organization
 * in PostgreSQL that every replica writes and reads. Modules publish to it through the kernel's [OrgEventPublisher],
 * which this module binds, preferably in the transaction of the change they report; `GET /api/v1/events` streams it,
 * and the events are kept 7 days.
 */
object EventsModule : ServerModule {
    override val name: String = "events"

    override val koinModule: Module = module {
        single<OrgEventsRepository> { OrgEventsRepositoryImpl(get()) }
        single<OrgEventPublisher> { JournalOrgEventPublisher(get(), get()) }
        single<OpenEventFeedUseCase> { OpenEventFeedUseCaseImpl(get()) }
        single { EventsRoutesDependencies(get(), get(), get()) }
        single { EventsPurgeWorker(get(), get(), get()) }
        // A deleted organization's journal is erased once its deletion is final (OrgsModule calls every listener).
        single<OrgDeletionListener>(named("events")) { EventsOrgDeletionListener(get()) }
    }

    override val migrations: List<SchemaMigration> = EventsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.eventsRoutes(koin.get())
    }

    /** The feed is served on every instance: it needs nothing but the database. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.EVENTS)

    /** Purges the events past their retention until the server stops. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val purge = koin.get<EventsPurgeWorker>()
        scope.launch { purge.run() }
    }
}
