// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.notifications.domain.ApprovalNotices
import dev.ostrom.server.notifications.domain.EmailGateChannel
import dev.ostrom.server.notifications.domain.EmailSender
import dev.ostrom.server.notifications.domain.NoPersonEmails
import dev.ostrom.server.notifications.domain.PersonEmails
import dev.ostrom.server.notifications.domain.PushDevicesRepository
import dev.ostrom.server.notifications.domain.PushDevicesUseCase
import dev.ostrom.server.notifications.domain.PushDevicesUseCaseImpl
import dev.ostrom.server.notifications.domain.PushGateChannel
import dev.ostrom.server.notifications.domain.PushSender
import dev.ostrom.server.notifications.infrastructure.ExpoPushSender
import dev.ostrom.server.notifications.infrastructure.NotificationSettings
import dev.ostrom.server.notifications.infrastructure.NotificationsMigrations
import dev.ostrom.server.notifications.infrastructure.PostgresPushDevicesRepository
import dev.ostrom.server.notifications.infrastructure.ResendEmailSender
import dev.ostrom.server.notifications.presentation.NotificationsRoutesDependencies
import dev.ostrom.server.notifications.presentation.notificationsRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose

/** Koin name of the HTTP client to Expo and Resend. */
val NOTIFICATIONS_HTTP = named("notifications-http")

/**
 * The notifications module: each pending approver of a gate hears of it on the phones they registered (Expo push)
 * and by email when their address is known (Resend), as [GateChannel]s. Its settings come from the server's
 * configuration (`OSTROM_EXPO_*`, `OSTROM_RESEND_*`, `OSTROM_EMAIL_FROM`); the addresses from the identity module's
 * [PersonEmails], none until it binds them.
 */
object NotificationsModule : ServerModule {
    override val name: String = "notifications"

    override val koinModule: Module = module {
        single(NOTIFICATIONS_HTTP) {
            HttpClient {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = TIMEOUT_MS
                    requestTimeoutMillis = TIMEOUT_MS
                }
            }
        } onClose { it?.close() }
        single { NotificationSettings.from(get()) }
        single { ApprovalNotices(get<NotificationSettings>().webOrigin) }
        single<PushDevicesRepository> { PostgresPushDevicesRepository(get()) }
        single<PushSender> {
            val settings = get<NotificationSettings>()
            ExpoPushSender(get(NOTIFICATIONS_HTTP), settings.expoUrl, settings.expoToken)
        }
        single<EmailSender> {
            val settings = get<NotificationSettings>()
            ResendEmailSender(get(NOTIFICATIONS_HTTP), settings.resendUrl, settings.resendKey, settings.emailFrom)
        }
        single { PushGateChannel(get(), get(), get()) } bind GateChannel::class
        // The addresses are read at the first request, after every module bound its definitions.
        single {
            val koin = getKoin()
            EmailGateChannel(koin.getOrNull<PersonEmails>() ?: NoPersonEmails, get(), get())
        } bind GateChannel::class
        single<PushDevicesUseCase> { PushDevicesUseCaseImpl(get(), get()) }
        single { NotificationsRoutesDependencies(get(), get()) }
    }

    override val migrations: List<SchemaMigration> = NotificationsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.notificationsRoutes(koin.get())
    }

    private const val TIMEOUT_MS = 10_000L
}
