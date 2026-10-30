// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer.presentation

import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.WriteAccess
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.http.bodyLimit
import dev.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/** The largest export body the import route reads. */
const val MAX_IMPORT_BODY: Long = 8L shl 20

/**
 * The import route: an owner or admin of the org, with a browser session or a console token holding
 * [IMPORT_SCOPE]; agent and device tokens are refused.
 *
 * @param dependencies The use case.
 */
fun Route.importRoutes(dependencies: ImportRoutesDependencies) = with(dependencies) {
    authenticated {
        // A team's export: its members, secrets' names, servers and assignments, well under 8 MiB (S2-46).
        bodyLimit(MAX_IMPORT_BODY) {
            post<TeamCodeImportApi> { params ->
                // The CLI imports with the person's console token (S2-52, declared).
                val caller = call.requireCaller(callers, IMPORT_SCOPE, writers = WriteAccess.tokens(PrincipalKind.USER))
                val request = call.receive<TeamCodeImportRequest>()
                val report = importTeamCode(caller, request.toExport(), request.memberIds(), params.dryRun)
                call.respond(report.toDto())
            }
        }
    }
}
