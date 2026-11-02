// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.reports.domain.ActivityCursor
import dev.ostrom.server.reports.domain.SpendingPeriod
import dev.ostrom.server.reports.domain.Viewer
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * `GET /api/v1/spending` and `GET /api/v1/activity`, for any member of the organization with the scope: a browser
 * or app session, or a token with the read scope. The organization comes from the credential, never the request.
 *
 * @param dependencies The use cases.
 */
fun Route.reportsRoutes(dependencies: ReportsRoutesDependencies) = with(dependencies) {
    authenticated {
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

// The scope first (SECURITY-AUDIT S2-03): a credential that may not read learns nothing about its organization.
private suspend fun ApplicationCall.viewer(dependencies: ReportsRoutesDependencies, accepted: Set<String>): Viewer {
    val principal = requirePrincipal()
    if (accepted.none(principal::allows)) {
        throw OstromException(ErrorKind.FORBIDDEN, "This credential may not read this report.")
    }
    return Viewer.of(requireOrgPrincipal(dependencies.orgs, null))
}

/** The scopes the reports accept, any one of them; a person's browser or app session (`user:*`) passes them all. */
object ReportsScopes {
    /** Reads the activity feed. */
    const val ACTIVITY_READ: String = "activity:read"

    /** Who reads the spending: the usage and budgets readers. */
    val spending: Set<String> = setOf("usage:read", "budgets:read")

    /** Who reads the activity. */
    val activity: Set<String> = setOf(ACTIVITY_READ)
}
