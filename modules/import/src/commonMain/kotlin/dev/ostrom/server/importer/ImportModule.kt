// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.domain.AccountImporter
import dev.ostrom.server.importer.domain.ImportProvenanceRepository
import dev.ostrom.server.importer.domain.ImportTeamCodeUseCase
import dev.ostrom.server.importer.domain.ImportTeamCodeUseCaseImpl
import dev.ostrom.server.importer.domain.McpPorts
import dev.ostrom.server.importer.domain.SecretPorts
import dev.ostrom.server.importer.infrastructure.ImportMigrations
import dev.ostrom.server.importer.infrastructure.ImportProvenanceRepositoryImpl
import dev.ostrom.server.importer.presentation.ImportRoutesDependencies
import dev.ostrom.server.importer.presentation.importRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The import module: `POST /api/v1/import/teamcode` takes what `ostrom import teamcode` read from TeamCode and writes
 * it through the secrets, MCP relay and sharing modules. Its one table records what it created, so a later run updates
 * that and leaves alone what an admin created by hand. Shared accounts are imported when the sharing module binds an
 * [AccountImporter].
 */
object ImportModule : ServerModule {
    override val name: String = "import"

    override val koinModule: Module = module {
        single { SecretPorts(get(), get(), get(), get()) }
        single { McpPorts(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single<ImportProvenanceRepository> { ImportProvenanceRepositoryImpl(get()) }
        single<ImportTeamCodeUseCase> { ImportTeamCodeUseCaseImpl(get(), get(), get(), getOrNull()) }
        single { ImportRoutesDependencies(getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()), get()) }
    }

    override val migrations: List<SchemaMigration> = ImportMigrations

    override fun routes(route: Route, koin: Koin) {
        route.importRoutes(koin.get())
    }
}
