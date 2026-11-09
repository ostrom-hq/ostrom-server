// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.importer.domain.AccountImporter
import run.ostrom.server.importer.domain.ImportProvenanceRepository
import run.ostrom.server.importer.domain.ImportTeamCodeUseCase
import run.ostrom.server.importer.domain.ImportTeamCodeUseCaseImpl
import run.ostrom.server.importer.domain.McpPorts
import run.ostrom.server.importer.domain.SecretPorts
import run.ostrom.server.importer.domain.SharedAccountImporter
import run.ostrom.server.importer.infrastructure.IdentityImportAudit
import run.ostrom.server.importer.infrastructure.ImportMigrations
import run.ostrom.server.importer.infrastructure.ImportProvenanceRepositoryImpl
import run.ostrom.server.importer.infrastructure.NoMembers
import run.ostrom.server.importer.presentation.ImportRoutesDependencies
import run.ostrom.server.importer.presentation.importRoutes
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import run.ostrom.server.sharing.domain.ShareAccountUseCase
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The import module: `POST /api/v1/import/teamcode` takes what `ostrom import teamcode` read from TeamCode and writes
 * it through the secrets, MCP relay and sharing modules. Its one table records what it created, so a later run updates
 * that and leaves alone what an admin created by hand. Shared accounts go through the sharing module's use cases
 * ([SharedAccountImporter]) when it is installed, or through a bound [AccountImporter]. The cutover run is recorded in
 * the identity module's audit log ([IdentityImportAudit]), and refused without one. A member map is checked against
 * the orgs module's [MemberDirectory]; without one, no member can be mapped ([NoMembers]).
 */
object ImportModule : ServerModule {
    override val name: String = "import"

    override val koinModule: Module = module {
        single { SecretPorts(get(), get(), get(), get()) }
        single { McpPorts(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single<ImportProvenanceRepository> { ImportProvenanceRepositoryImpl(get()) }
        single<ImportTeamCodeUseCase> {
            val accounts = getOrNull<AccountImporter>()
                ?: getOrNull<ShareAccountUseCase>()?.let { share ->
                    SharedAccountImporter(share, get(), get(), get())
                }
            val audit = getOrNull<AuditLog>()?.let(::IdentityImportAudit)
            val members = getOrNull<MemberDirectory>() ?: NoMembers
            ImportTeamCodeUseCaseImpl(get(), get(), get(), members, accounts, audit)
        }
        single { ImportRoutesDependencies(getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()), get()) }
    }

    override val migrations: List<SchemaMigration> = ImportMigrations

    override fun routes(route: Route, koin: Koin) {
        route.importRoutes(koin.get())
    }

    /** The import of a TeamCode instance. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.IMPORT_TEAMCODE)
}
