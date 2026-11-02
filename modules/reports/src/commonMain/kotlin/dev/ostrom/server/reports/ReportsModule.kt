// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.reports.domain.GetSpendingUseCase
import dev.ostrom.server.reports.domain.GetSpendingUseCaseImpl
import dev.ostrom.server.reports.domain.ListActivityUseCase
import dev.ostrom.server.reports.domain.ListActivityUseCaseImpl
import dev.ostrom.server.reports.domain.ReportsRepository
import dev.ostrom.server.reports.domain.SpendingBudgets
import dev.ostrom.server.reports.infrastructure.BudgetsSpendingBudgets
import dev.ostrom.server.reports.infrastructure.ReportsMigrations
import dev.ostrom.server.reports.infrastructure.ReportsRepositoryImpl
import dev.ostrom.server.reports.presentation.ReportsRoutesDependencies
import dev.ostrom.server.reports.presentation.reportsRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The reports module: the spending report and the activity feed, read across the usage, sessions, gates and
 * compiler tables. Comes after those modules (its migration indexes their tables) and needs the budgets module.
 */
object ReportsModule : ServerModule {
    override val name: String = "reports"

    override val koinModule: Module = module {
        single<ReportsRepository> { ReportsRepositoryImpl(get()) }
        single<SpendingBudgets> { BudgetsSpendingBudgets(get()) }
        single<GetSpendingUseCase> { GetSpendingUseCaseImpl(get(), get(), get()) }
        single<ListActivityUseCase> { ListActivityUseCaseImpl(get()) }
        single { ReportsRoutesDependencies(get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = ReportsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.reportsRoutes(koin.get())
    }
}
