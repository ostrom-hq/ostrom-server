// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance

import run.ostrom.server.governance.domain.AnswerConfirmationUseCase
import run.ostrom.server.governance.domain.AnswerConfirmationUseCaseImpl
import run.ostrom.server.governance.domain.ConfirmationAudit
import run.ostrom.server.governance.domain.ConfirmationPeople
import run.ostrom.server.governance.domain.ConfirmationRecords
import run.ostrom.server.governance.domain.ConfirmationSecrets
import run.ostrom.server.governance.domain.ConfirmationWaits
import run.ostrom.server.governance.domain.ConfirmationsRepository
import run.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import run.ostrom.server.governance.domain.ExpireConfirmationsUseCaseImpl
import run.ostrom.server.governance.domain.GetConfirmationUseCase
import run.ostrom.server.governance.domain.GetConfirmationUseCaseImpl
import run.ostrom.server.governance.domain.GovernanceChanges
import run.ostrom.server.governance.domain.OpenConfirmationUseCase
import run.ostrom.server.governance.domain.OpenConfirmationUseCaseImpl
import run.ostrom.server.governance.infrastructure.AuditLogConfirmationAudit
import run.ostrom.server.governance.infrastructure.ConfirmationSweeper
import run.ostrom.server.governance.infrastructure.GovernanceConfirmationsImpl
import run.ostrom.server.governance.infrastructure.GovernanceMigrations
import run.ostrom.server.governance.infrastructure.IdentityConfirmationPeople
import run.ostrom.server.governance.infrastructure.LocalConfirmationWaits
import run.ostrom.server.governance.infrastructure.PostgresConfirmationsRepository
import run.ostrom.server.governance.presentation.ConfirmationsRoutesDependencies
import run.ostrom.server.governance.presentation.confirmationsRoutes
import run.ostrom.server.identity.presentation.IdentityConfig
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.GovernanceConfirmations
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The governance module: a governance write made with a person's token (the CLI's, elevated or not) is not applied; it
 * opens a confirmation an owner or an admin answers on the console, with a sign-in less than 5 minutes old (audit CLI
 * H1). Binds the kernel's [GovernanceConfirmations]; the rules, compiler and gateway modules bind their
 * [GovernanceChange]s. Without this module, a token's governance write is refused.
 */
object GovernanceModule : ServerModule {
    override val name: String = "governance"

    override val koinModule: Module = module {
        single<ConfirmationsRepository> { PostgresConfirmationsRepository(get()) }
        single<ConfirmationAudit> { AuditLogConfirmationAudit(get()) }
        single<ConfirmationPeople> { IdentityConfirmationPeople(get()) }
        single<ConfirmationWaits> { LocalConfirmationWaits() }
        single { GovernanceChanges(getAll<GovernanceChange>()) }
        single<ExpireConfirmationsUseCase> { ExpireConfirmationsUseCaseImpl(get(), get(), get(), get()) }
        single<OpenConfirmationUseCase> { OpenConfirmationUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single { ConfirmationSecrets(get(), get()) }
        single<GetConfirmationUseCase> { GetConfirmationUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<AnswerConfirmationUseCase> {
            AnswerConfirmationUseCaseImpl(ConfirmationRecords(get(), get(), get()), get(), get(), get(), get(), get())
        }
        single<GovernanceConfirmations> { GovernanceConfirmationsImpl(get(), get<IdentityConfig>().consoleUrl) }
        single { ConfirmationsRoutesDependencies(get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = GovernanceMigrations

    override fun routes(route: Route, koin: Koin) {
        route.confirmationsRoutes(koin.get())
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val sweeper = ConfirmationSweeper(koin.get(), koin.get())
        scope.launch { sweeper.run() }
    }
}
