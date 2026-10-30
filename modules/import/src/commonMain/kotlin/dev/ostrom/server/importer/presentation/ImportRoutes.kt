// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.presentation

import dev.ostrom.server.importer.domain.CutoverOwnerRequiredException
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The import route: an owner or admin of the org, with a browser session or a console token holding
 * [IMPORT_SCOPE]; agent and device tokens are refused.
 *
 * @param dependencies The use case.
 */
fun Route.importRoutes(dependencies: ImportRoutesDependencies) = with(dependencies) {
    authenticated {
        post<TeamCodeImportApi> { params ->
            val caller = call.requireCaller(callers, IMPORT_SCOPE)
            // The cutover is a person's act, recorded in their name: an agent or a service never runs it.
            val cutoverBy = if (params.cutover) call.requirePrincipal().person() else null
            val request = call.receive<TeamCodeImportRequest>()
            val export = request.toExport()
            val report = importTeamCode(caller, export, request.memberIds(), params.dryRun, cutoverBy)
            call.respond(report.toDto())
        }
    }
}

/** The user id of a person's principal; refuses an agent's or a service's, as a cutover needs an owner. */
private fun AuthenticatedPrincipal.person(): Uuid {
    val user = if (kind == PrincipalKind.USER) Uuid.parseOrNull(subjectId) else null
    return user ?: throw CutoverOwnerRequiredException()
}
