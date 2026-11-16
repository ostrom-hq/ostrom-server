// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import run.ostrom.server.sharing.domain.PoolBudget
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The pool budget routes (TeamCode's `GET/PUT /api/v1/pools/budgets`): an owner or an admin reads them
 * (`accounts:read`, or a key's `team:read`) and changes them (`accounts:write`, browser session). Each change is
 * written to the organization's audit log (`pool_budget.updated`, its subject the pool).
 *
 * @param dependencies The use cases.
 */
fun Route.poolBudgetRoutes(dependencies: PoolBudgetRoutesDependencies) = with(dependencies) {
    authenticated {
        get<PoolBudgetsApi> {
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_READ, ServiceKeyScopes.TEAM_READ)
            call.respond(budgets.list(caller).map { it.toDto() })
        }
        put<PoolBudgetsApi> {
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            val request = call.receive<PoolBudgetDto>()
            val saved = budgets.save(caller, PoolBudget(request.poolId, request.dailyMicros, request.sharedOverflow))
            audit.record(
                OrgAuditRecord(
                    caller.orgId,
                    call.requirePrincipal(),
                    "pool_budget.updated",
                    "pool",
                    saved.poolId,
                    auditDetails("daily_micros" to saved.dailyMicros, "shared_overflow" to saved.sharedOverflow),
                ),
            )
            call.respond(saved.toDto())
        }
    }
}

private fun PoolBudget.toDto() = PoolBudgetDto(poolId, dailyMicros, sharedOverflow)
