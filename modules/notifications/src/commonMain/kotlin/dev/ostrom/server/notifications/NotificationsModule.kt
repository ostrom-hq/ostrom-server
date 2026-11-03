// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.OrgDeletionListener
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.notifications.domain.DeletePushDeviceUseCase
import dev.ostrom.server.notifications.domain.DeletePushDeviceUseCaseImpl
import dev.ostrom.server.notifications.domain.PushDevicesRepository
import dev.ostrom.server.notifications.domain.RegisterPushDeviceUseCase
import dev.ostrom.server.notifications.domain.RegisterPushDeviceUseCaseImpl
import dev.ostrom.server.notifications.infrastructure.NotificationsMigrations
import dev.ostrom.server.notifications.infrastructure.PushDevicesOrgDeletionListener
import dev.ostrom.server.notifications.infrastructure.PushDevicesRepositoryImpl
import dev.ostrom.server.notifications.presentation.PushDevicesRoutesDependencies
import dev.ostrom.server.notifications.presentation.pushDevicesRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The notifications module: the phones a person registers for push notifications, one per push token in each
 * organization, forgotten when the organization is deleted. Sending the pushes is not here yet.
 */
object NotificationsModule : ServerModule {
    override val name: String = "notifications"

    override val koinModule: Module = module {
        single<PushDevicesRepository> { PushDevicesRepositoryImpl(get()) }
        single<RegisterPushDeviceUseCase> { RegisterPushDeviceUseCaseImpl(get(), get()) }
        single<DeletePushDeviceUseCase> { DeletePushDeviceUseCaseImpl(get()) }
        single { PushDevicesRoutesDependencies(get(), get(), get()) }
        // A deleted organization's phones are forgotten at once (OrgsModule calls every bound listener).
        single<OrgDeletionListener>(named("notifications")) { PushDevicesOrgDeletionListener(get()) }
    }

    override val migrations: List<SchemaMigration> = NotificationsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.pushDevicesRoutes(koin.get())
    }
}
