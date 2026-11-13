// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.admin.domain.ImportLegacySettingsUseCase
import run.ostrom.server.admin.domain.ImportLegacySettingsUseCaseImpl
import run.ostrom.server.admin.domain.InstanceAdminsRepository
import run.ostrom.server.admin.domain.InstanceAdminsUseCase
import run.ostrom.server.admin.domain.InstanceAdminsUseCaseImpl
import run.ostrom.server.admin.domain.InstanceSettingsReader
import run.ostrom.server.admin.domain.InstanceSettingsRepository
import run.ostrom.server.admin.domain.InstanceSettingsUseCase
import run.ostrom.server.admin.domain.InstanceSettingsUseCaseImpl
import run.ostrom.server.admin.domain.SeedInstanceAdminsUseCase
import run.ostrom.server.admin.domain.SeedInstanceAdminsUseCaseImpl
import run.ostrom.server.admin.infrastructure.AdminMigrations
import run.ostrom.server.admin.infrastructure.InstanceAdminsRepositoryImpl
import run.ostrom.server.admin.infrastructure.InstanceSettingsRepositoryImpl
import run.ostrom.server.admin.presentation.AdminRoutesDependencies
import run.ostrom.server.admin.presentation.adminRoutes
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.settings.InstanceAdminGrants
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * The instance's administration (TeamCode's dashboard settings, docs/MODULES.md "Settings in the console"): its
 * administrators and the settings sections the modules declare ([SettingsSection] bound in Koin), stored in
 * PostgreSQL, secrets sealed with the secrets key, served under `/api/v1/admin`. At start it records the setup link's
 * people as administrators, and imports once what a manual deployment still sets in the former variables
 * (`OSTROM_INSTANCE_ADMINS`, each section's legacy variables), saying they are deprecated.
 */
object AdminModule : ServerModule {
    override val name: String = "admin"

    override val koinModule: Module = module {
        single<InstanceAdminsRepository> { InstanceAdminsRepositoryImpl(get()) }
        single<InstanceSettingsRepository> { InstanceSettingsRepositoryImpl(get()) }
        single { SeedInstanceAdminsUseCaseImpl(get(), get(), get(), get(), get()) } binds
            arrayOf(SeedInstanceAdminsUseCase::class, InstanceAdminGrants::class, InstanceAdministrators::class)
        single { InstanceSettingsReader(get(), get(), get(), get()) } bind InstanceSettings::class
        single<InstanceAdminsUseCase> { InstanceAdminsUseCaseImpl(get(), get(), get(), get()) }
        single<InstanceSettingsUseCase> {
            InstanceSettingsUseCaseImpl(getAll<SettingsSection>(), get(), get(), get(), get(), get(), get())
        }
        single<ImportLegacySettingsUseCase> {
            ImportLegacySettingsUseCaseImpl(getAll<SettingsSection>(), get(), get(), get(), get(), get())
        }
        single { AdminRoutesDependencies(get(), get()) }
    }

    override val migrations: List<SchemaMigration> = AdminMigrations

    override suspend fun initialize(koin: Koin) {
        val source = koin.get<ConfigSource>()
        koin.get<SeedInstanceAdminsUseCase>()(source[LEGACY_ADMINS])
        koin.get<ImportLegacySettingsUseCase>()(source::get)
    }

    override fun routes(route: Route, koin: Koin) {
        route.adminRoutes(koin.get())
    }

    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.ADMIN_SETTINGS)

    /** The former variable of the instance's administrators, imported once. */
    const val LEGACY_ADMINS: String = "OSTROM_INSTANCE_ADMINS"
}
