// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The budgets routes: admins read and set the org's limits, by the day or the month, that stop or warn; any member,
 * and an agent session, reads their own budget. Reading takes [BudgetScopes.READ]: a browser session, a console
 * token, or the CLI token (`ostrom login`), which holds it for `GET /budgets/me` since #587, so an owner's or an
 * admin's CLI token also reads `GET /budgets` (SECURITY-AUDIT S2-03 kept every member's limits off the CLI's tokens
 * until then: they now read them, only for an owner or an admin, and never write them, which takes
 * [BudgetScopes.WRITE]). Device tokens are refused. A change of a limit is written to the organization's audit log.
 *
 * @param dependencies The use cases.
 */
fun Route.budgetsRoutes(dependencies: BudgetsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<BudgetsApi> {
            val caller = call.requireCaller(callers, BudgetScopes.READ, ServiceKeyScopes.TEAM_READ)
            call.respond(getBudgets(caller).toPage())
        }
        put<BudgetsApi> {
            val caller = call.requireCaller(callers, BudgetScopes.WRITE)
            val principal = call.requirePrincipal()
            val request = call.receive<BudgetDto>()
            setBudget(caller, request.toTarget(), request.toLimits())
            val saved = request.normalized()
            // No amount is a removed limit: the detail is then absent, never the text "null"; so is the action of a
            // budget that stops, as before daily budgets and warnings.
            val details = auditDetails(
                "scope" to saved.scope,
                "usd_per_month" to saved.usdPerMonth,
                "usd_per_day" to saved.usdPerDay,
                "action" to "warn".takeUnless { saved.hardStop },
            )
            val subject = saved.subject ?: saved.scope
            audit.record(OrgAuditRecord(caller.orgId, principal, "budget.changed", "budget", subject, details))
            call.respond(saved)
        }
        get<BudgetsApi.Me> {
            val caller = call.requireCaller(callers, BudgetScopes.READ, RelayScopes.AGENT_SESSION)
            call.respond(getMyBudget(caller).toResponse())
        }
        budgetOverrideRoutes(dependencies)
    }
}
