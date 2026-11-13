// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.reports.domain.GetSpendingUseCase
import run.ostrom.server.reports.domain.GetSpendingUseCaseImpl
import run.ostrom.server.reports.domain.ListActivityUseCase
import run.ostrom.server.reports.domain.ListActivityUseCaseImpl
import run.ostrom.server.reports.domain.ReportsRepository
import run.ostrom.server.reports.domain.SpendingBudgets
import run.ostrom.server.reports.infrastructure.BudgetsSpendingBudgets
import run.ostrom.server.reports.infrastructure.ReportsMigrations
import run.ostrom.server.reports.infrastructure.ReportsRepositoryImpl
import run.ostrom.server.reports.presentation.ReportsRoutesDependencies
import run.ostrom.server.reports.presentation.reportsRoutes
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
        route.reportsRoutes(koin.get(), koin.get())
    }

    /** The activity feed and the spending report. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.REPORTS)
}
