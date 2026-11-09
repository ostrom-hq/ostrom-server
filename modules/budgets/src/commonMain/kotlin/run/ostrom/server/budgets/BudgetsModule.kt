// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.AuditedBudgetAlerts
import run.ostrom.server.budgets.domain.BudgetAlerts
import run.ostrom.server.budgets.domain.BudgetDecisionCache
import run.ostrom.server.budgets.domain.BudgetGuard
import run.ostrom.server.budgets.domain.BudgetGuardImpl
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.domain.GetBudgetsUseCase
import run.ostrom.server.budgets.domain.GetBudgetsUseCaseImpl
import run.ostrom.server.budgets.domain.GetMyBudgetUseCase
import run.ostrom.server.budgets.domain.GetMyBudgetUseCaseImpl
import run.ostrom.server.budgets.domain.LedgerSpendGuard
import run.ostrom.server.budgets.domain.NoBudgetAlerts
import run.ostrom.server.budgets.domain.SetBudgetUseCase
import run.ostrom.server.budgets.domain.SetBudgetUseCaseImpl
import run.ostrom.server.budgets.domain.WorkflowBudgetsRemoval
import run.ostrom.server.budgets.infrastructure.BudgetsMigrations
import run.ostrom.server.budgets.infrastructure.BudgetsRepositoryImpl
import run.ostrom.server.budgets.presentation.BudgetsRoutesDependencies
import run.ostrom.server.budgets.presentation.budgetsRoutes
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.workflows.WorkflowRemovals
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * The budgets module: daily and monthly limits per org and per member, that stop the calls or warn, and the gateway's
 * [SpendGuard] ([LedgerSpendGuard]), asked before each model call. It reads the spend from the usage module's
 * `SpendReader` and admits calls through its `SpendAdmission`. It does not bind [BudgetAlerts]: the notifications
 * module binds it (Koin refuses two bindings of one type), and until then alerts go to [NoBudgetAlerts].
 */
object BudgetsModule : ServerModule {
    override val name: String = "budgets"

    override val koinModule: Module = module {
        single<BudgetsRepository> { BudgetsRepositoryImpl(get()) }
        single {
            val alerts = AuditedBudgetAlerts(getOrNull<BudgetAlerts>() ?: NoBudgetAlerts(), get<OrgAuditTrail>())
            BudgetGuardImpl(get(), get(), alerts, get(), get())
        } binds arrayOf(BudgetGuard::class, BudgetDecisionCache::class)
        // The gateway asks it before each model call (GatewayModule falls back to allowing every call without it).
        single<SpendGuard> { LedgerSpendGuard(get(), get(), get(), get()) }
        single<GetBudgetsUseCase> { GetBudgetsUseCaseImpl(get(), get(), get()) }
        single<GetMyBudgetUseCase> { GetMyBudgetUseCaseImpl(get(), get(), get(), get()) }
        single<SetBudgetUseCase> { SetBudgetUseCaseImpl(get(), get(), get(), get(), workflows = getOrNull()) }
        single<WorkflowRemovals> { WorkflowBudgetsRemoval(get(), get()) }
        single { BudgetsRoutesDependencies(callerResolver(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = BudgetsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.budgetsRoutes(koin.get())
    }
}

/**
 * The [CallerResolver] a test binds, or else the one on the kernel's `PrincipalOrgResolver` (which refuses everyone
 * until the identity and orgs modules bind theirs).
 */
internal fun Scope.callerResolver(): CallerResolver = getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get())
