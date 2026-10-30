// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.presentation

import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetOverview
import dev.ostrom.server.budgets.domain.DeleteMemberBudgetUseCase
import dev.ostrom.server.budgets.domain.GetBudgetsUseCase
import dev.ostrom.server.budgets.domain.GetMyBudgetUseCase
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.budgets.domain.MemberBudgetNotFoundException
import dev.ostrom.server.budgets.domain.MyBudget
import dev.ostrom.server.budgets.domain.PutBudgetsUseCase
import dev.ostrom.server.budgets.domain.PutMemberBudgetUseCase
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.presentation.requireCaller
import dev.ostrom.server.secrets.presentation.toIdOr
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * What the budgets routes need.
 *
 * @property callers Resolves the member of each call.
 * @property getBudgets Reads an org's budgets.
 * @property putBudgets Replaces the org and default limits.
 * @property putMemberBudget Sets a member's limit.
 * @property deleteMemberBudget Removes it.
 * @property getMyBudget Reads the caller's.
 */
data class BudgetsRoutesDependencies(
    val callers: CallerResolver,
    val getBudgets: GetBudgetsUseCase,
    val putBudgets: PutBudgetsUseCase,
    val putMemberBudget: PutMemberBudgetUseCase,
    val deleteMemberBudget: DeleteMemberBudgetUseCase,
    val getMyBudget: GetMyBudgetUseCase,
)

/**
 * The budgets routes: authenticated; admins manage the limits, any member reads their own budget.
 *
 * @param dependencies The use cases.
 */
fun Route.budgetsRoutes(dependencies: BudgetsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<BudgetsApi> { params ->
            call.respond(getBudgets(call.requireCaller(callers, params.orgId)).toResponse())
        }
        put<BudgetsApi> { params ->
            val caller = call.requireCaller(callers, params.orgId)
            val request = call.receive<PutBudgetsRequest>()
            call.respond(putBudgets(caller, request.orgLimitMicros, request.defaultMemberLimitMicros).toResponse())
        }
        put<BudgetsApi.Member> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            val memberId = params.memberId.toIdOr(::MemberBudgetNotFoundException)
            val request = call.receive<PutMemberBudgetRequest>()
            call.respond(putMemberBudget(caller, memberId, request.limitMicros).toResponse())
        }
        delete<BudgetsApi.Member> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            deleteMemberBudget(caller, params.memberId.toIdOr(::MemberBudgetNotFoundException))
            call.respond(HttpStatusCode.NoContent)
        }
        get<BudgetsApi.Me> { params ->
            call.respond(getMyBudget(call.requireCaller(callers, params.parent.orgId)).toResponse())
        }
    }
}

/** Maps a member's limit to the API. */
fun MemberBudget.toResponse() = MemberBudgetResponse(memberId.toString(), limitMicros, updatedAt.toString())

/** Maps an org's budgets to the API. */
fun BudgetOverview.toResponse() = BudgetsResponse(
    orgLimitMicros = settings.orgLimitMicros,
    defaultMemberLimitMicros = settings.defaultMemberLimitMicros,
    overrides = settings.overrides.map { it.toResponse() },
    orgSpentMicros = orgSpentMicros,
    periodStart = period.start.toString(),
    resetsAt = period.end.toString(),
)

/** Maps the caller's budget to the API. */
fun MyBudget.toResponse() = MyBudgetResponse(
    limitMicros = limitMicros,
    spentMicros = spentMicros,
    remainingMicros = remainingMicros,
    resetsAt = resetsAt.toString(),
    exhausted = decision is BudgetDecision.Exhausted,
    exhaustedScope = (decision as? BudgetDecision.Exhausted)?.scope?.name?.lowercase(),
)
