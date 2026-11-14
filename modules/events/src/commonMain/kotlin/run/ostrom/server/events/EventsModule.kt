// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.events.domain.DeliverWebhooksUseCase
import run.ostrom.server.events.domain.DeliverWebhooksUseCaseImpl
import run.ostrom.server.events.domain.OpenEventFeedUseCase
import run.ostrom.server.events.domain.OpenEventFeedUseCaseImpl
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.events.domain.WebhookSender
import run.ostrom.server.events.domain.WebhookSigner
import run.ostrom.server.events.domain.WebhookTargets
import run.ostrom.server.events.domain.WebhooksRepository
import run.ostrom.server.events.domain.WebhooksService
import run.ostrom.server.events.domain.WebhooksServiceImpl
import run.ostrom.server.events.infrastructure.EventsMigrations
import run.ostrom.server.events.infrastructure.EventsOrgDeletionListener
import run.ostrom.server.events.infrastructure.EventsPurgeWorker
import run.ostrom.server.events.infrastructure.HmacWebhookSigner
import run.ostrom.server.events.infrastructure.HttpWebhookSender
import run.ostrom.server.events.infrastructure.JournalOrgEventPublisher
import run.ostrom.server.events.infrastructure.OrgEventsRepositoryImpl
import run.ostrom.server.events.infrastructure.WebhooksConfig
import run.ostrom.server.events.infrastructure.WebhooksRepositoryImpl
import run.ostrom.server.events.infrastructure.WebhooksWorker
import run.ostrom.server.events.presentation.EventsRoutesDependencies
import run.ostrom.server.events.presentation.WebhooksRoutesDependencies
import run.ostrom.server.events.presentation.eventsRoutes
import run.ostrom.server.events.presentation.feedJson
import run.ostrom.server.events.presentation.webhooksRoutes
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.network.isPublicAddress
import run.ostrom.server.kernel.network.isReachableAddress
import run.ostrom.server.kernel.network.outboundHttpClient
import run.ostrom.server.kernel.network.pinnedResolver
import kotlin.time.Duration.Companion.seconds

/** The webhooks' outbound client. */
private val WEBHOOKS_CLIENT = named("eventsWebhooksClient")

/** How long a webhook's connection may take. */
private val CONNECT_TIMEOUT = 5.seconds

/** How long a webhook's request may take, answer included. */
private val REQUEST_TIMEOUT = 10.seconds

/**
 * The events module: the organization's event feed of TeamCode (`team_events`), one gap-free sequence per organization
 * in PostgreSQL that every replica writes and reads. Modules publish to it through the kernel's [OrgEventPublisher],
 * which this module binds, preferably in the transaction of the change they report; `GET /api/v1/events` streams it,
 * and the events are kept 7 days. Its outgoing webhooks (TeamCode's `webhooks`) send the feed's events to the
 * organization's own services as signed requests, through the kernel's outbound client only.
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
        single<OrgDeletionListener>(named("events")) { EventsOrgDeletionListener(get(), get()) }
        webhooks()
    }

    override val migrations: List<SchemaMigration> = EventsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.eventsRoutes(koin.get())
        route.webhooksRoutes(koin.get())
    }

    /** The feed and the webhooks are served on every instance: they need nothing but the database. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.EVENTS, ServerFeatures.WEBHOOKS)

    /** Purges the events past their retention, one replica at a time, and delivers the webhooks until it stops. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val purge = koin.get<EventsPurgeWorker>()
        val webhooks = koin.get<WebhooksWorker>()
        val work = koin.scheduledWork()
        scope.launch { purge.run(work) }
        scope.launch { webhooks.run() }
    }

    private fun Module.webhooks() {
        single { WebhooksConfig.from(get()) }
        single { WebhookTargets(get<WebhooksConfig>().allowPrivateTargets) }
        single<WebhooksRepository> { WebhooksRepositoryImpl(get()) }
        single<WebhookSigner> { HmacWebhookSigner() }
        // A target the org's admins typed: checked where the connection is made (no DNS rebinding), no redirect.
        single(WEBHOOKS_CLIENT) {
            outboundHttpClient(allowPrivate = get<WebhooksConfig>().allowPrivateTargets) {
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
                    requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
                    socketTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds
                }
            }
        } onClose { it?.close() }
        single<WebhookSender> {
            val targets = get<WebhookTargets>()
            val allowed: (ByteArray) -> Boolean = if (targets.allowPrivate) ::isReachableAddress else ::isPublicAddress
            HttpWebhookSender(get<HttpClient>(WEBHOOKS_CLIENT), targets, allowed, pinnedResolver({ true }))
        }
        single<WebhooksService> { WebhooksServiceImpl(get(), get(), get(), get(), get(), get()) }
        single<DeliverWebhooksUseCase> { DeliverWebhooksUseCaseImpl(get(), get(), get(), get(), get(), ::feedJson) }
        single { WebhooksWorker(get(), get()) }
        single { WebhooksRoutesDependencies(get(), get()) }
    }
}
