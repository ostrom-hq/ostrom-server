// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance

import dev.ostrom.server.governance.domain.AnswerConfirmationUseCase
import dev.ostrom.server.governance.domain.AnswerConfirmationUseCaseImpl
import dev.ostrom.server.governance.domain.ConfirmationAudit
import dev.ostrom.server.governance.domain.ConfirmationPeople
import dev.ostrom.server.governance.domain.ConfirmationRecords
import dev.ostrom.server.governance.domain.ConfirmationSecrets
import dev.ostrom.server.governance.domain.ConfirmationWaits
import dev.ostrom.server.governance.domain.ConfirmationsRepository
import dev.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import dev.ostrom.server.governance.domain.ExpireConfirmationsUseCaseImpl
import dev.ostrom.server.governance.domain.GetConfirmationUseCase
import dev.ostrom.server.governance.domain.GetConfirmationUseCaseImpl
import dev.ostrom.server.governance.domain.GovernanceChanges
import dev.ostrom.server.governance.domain.OpenConfirmationUseCase
import dev.ostrom.server.governance.domain.OpenConfirmationUseCaseImpl
import dev.ostrom.server.governance.infrastructure.AuditLogConfirmationAudit
import dev.ostrom.server.governance.infrastructure.ConfirmationSweeper
import dev.ostrom.server.governance.infrastructure.GovernanceConfirmationsImpl
import dev.ostrom.server.governance.infrastructure.GovernanceMigrations
import dev.ostrom.server.governance.infrastructure.IdentityConfirmationPeople
import dev.ostrom.server.governance.infrastructure.LocalConfirmationWaits
import dev.ostrom.server.governance.infrastructure.PostgresConfirmationsRepository
import dev.ostrom.server.governance.presentation.ConfirmationsRoutesDependencies
import dev.ostrom.server.governance.presentation.confirmationsRoutes
import dev.ostrom.server.identity.presentation.IdentityConfig
import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.auth.GovernanceConfirmations
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
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
