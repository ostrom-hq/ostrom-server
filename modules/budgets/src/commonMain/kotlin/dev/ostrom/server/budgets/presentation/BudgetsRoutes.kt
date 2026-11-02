// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The budgets routes: admins read and set the org's limits (a browser session, or a console token with
 * [BudgetScopes.READ] or [BudgetScopes.WRITE]); any member, and an agent session, reads their own budget. Device
 * tokens are refused (SECURITY-AUDIT S2-03). A change of a limit is written to the organization's audit log.
 *
 * @param dependencies The use cases.
 */
fun Route.budgetsRoutes(dependencies: BudgetsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<BudgetsApi> {
            call.respond(getBudgets(call.requireCaller(callers, BudgetScopes.READ)).toPage())
        }
        put<BudgetsApi> {
            val caller = call.requireCaller(callers, BudgetScopes.WRITE)
            val request = call.receive<BudgetDto>()
            setBudget(caller, request.toTarget(), request.toLimitMicros())
            val saved = request.normalized()
            val details = mapOf("member" to "${saved.subject}", "usd_per_month" to "${saved.usdPerMonth}")
            val principal = call.requirePrincipal()
            audit.record(OrgAuditRecord(caller.orgId, principal, "budget.changed", "budget", saved.scope, details))
            call.respond(saved)
        }
        get<BudgetsApi.Me> {
            val caller = call.requireCaller(callers, BudgetScopes.READ, RelayScopes.AGENT_SESSION)
            call.respond(getMyBudget(caller).toResponse())
        }
    }
}
