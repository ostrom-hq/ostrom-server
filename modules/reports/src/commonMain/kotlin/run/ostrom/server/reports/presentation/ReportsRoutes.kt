// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.reports.presentation

import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.http.ReadThrottle
import run.ostrom.server.kernel.http.throttledReads
import run.ostrom.server.reports.domain.ActivityCursor
import run.ostrom.server.reports.domain.SpendingPeriod
import run.ostrom.server.reports.domain.Viewer
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * `GET /api/v1/spending` and `GET /api/v1/activity`, for any member of the organization with the scope: a browser
 * or app session, or a token with the read scope. The organization comes from the credential, never the request.
 *
 * @param dependencies The use cases.
 * @param throttle Bounds both reports per caller (AUDIT-SECU-LECTURES M1): each reads a lot.
 */
fun Route.reportsRoutes(dependencies: ReportsRoutesDependencies, throttle: ReadThrottle) = with(dependencies) {
    authenticated {
        throttledReads(throttle) {
            get<SpendingApi> { params ->
                val viewer = call.viewer(this@with, ReportsScopes.spending)
                call.respond(getSpending(viewer, SpendingPeriod.of(params.period)).toDto())
            }
            get<ActivityApi> { params ->
                val viewer = call.viewer(this@with, ReportsScopes.activity)
                val before = params.before?.let(ActivityCursor::of)
                call.respond(listActivity(viewer, before, params.repo).toDto())
            }
        }
    }
}

// The scope first (SECURITY-AUDIT S2-03): a credential that may not read learns nothing about its organization.
private suspend fun ApplicationCall.viewer(dependencies: ReportsRoutesDependencies, accepted: Set<String>): Viewer {
    val principal = requirePrincipal()
    if (accepted.none(principal::allows)) {
        throw OstromException(ErrorKind.FORBIDDEN, "This credential may not read this report.")
    }
    val member = requireOrgPrincipal(dependencies.orgs, null)
    // A service key sees the whole organization only through `team:read`: any other scope reads as a plain member.
    val asMember = principal.kind == PrincipalKind.SERVICE && ServiceKeyScopes.TEAM_READ !in principal.scopes
    return Viewer.of(if (asMember) member.copy(role = OrgRole.MEMBER) else member)
}

/** The scopes the reports accept, any one of them; a person's browser or app session (`user:*`) passes them all. */
object ReportsScopes {
    /** Reads the activity feed. */
    const val ACTIVITY_READ: String = "activity:read"

    /** Who reads the spending: the usage and budgets readers. */
    val spending: Set<String> = setOf("usage:read", "budgets:read", ServiceKeyScopes.TEAM_READ)

    /** Who reads the activity. */
    val activity: Set<String> = setOf(ACTIVITY_READ)
}
