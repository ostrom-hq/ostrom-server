// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

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
            val request = call.receive<TeamCodeImportRequest>()
            val report = importTeamCode(caller, request.toExport(), request.memberIds(), params.dryRun)
            call.respond(report.toDto())
        }
    }
}
