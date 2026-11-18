// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.budgets.domain.MemberCapacity
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpHeaders
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * TeamCode's `GET /api/v1/members/{id}/capacity`: an owner or an admin reads any member's capacity today, a member
 * their own (`budgets:read`, or a key's `team:read`). Never cached: the figures move with every call.
 *
 * @param dependencies The use case and who calls.
 */
fun Route.memberCapacityRoutes(dependencies: MemberCapacityRoutesDependencies) = with(dependencies) {
    authenticated {
        get<MemberCapacityApi> { params ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val caller = call.requireCaller(callers, BudgetScopes.READ, ServiceKeyScopes.TEAM_READ)
            call.respond(capacity(caller, params.login).toResponse())
        }
    }
}

/** The contract's `MemberCapacity`. */
internal fun MemberCapacity.toResponse() = MemberCapacityResponse(
    member = login,
    poolId = share.poolId,
    weight = share.weight,
    poolTotalWeight = share.totalWeight,
    poolDailyMicros = share.poolDailyMicros,
    shareMicros = share.micros,
    dailyLimitMicros = dailyLimitMicros,
    spentTodayMicros = spentTodayMicros,
    activeRequests = requests.running,
    waitingRequests = requests.waiting,
    resetsAt = resetsAt.toString(),
)
