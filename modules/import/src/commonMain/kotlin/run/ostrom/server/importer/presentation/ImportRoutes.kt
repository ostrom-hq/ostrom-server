// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.presentation

import run.ostrom.server.importer.domain.CutoverOwnerRequiredException
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

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
                // The cutover is a person's act, recorded in their name: an agent or a service never runs it.
                val cutoverBy = if (params.cutover) call.requirePrincipal().person() else null
                val request = call.receive<TeamCodeImportRequest>()
                val export = request.toExport()
                val report = importTeamCode(
                    caller,
                    export,
                    request.memberIds(),
                    params.dryRun,
                    cutoverBy,
                    params.accessOnly,
                    call.requirePrincipal(),
                )
                call.respond(report.toDto())
            }
        }
    }
}

/**
 * The user id of a person's principal, as a cutover needs an owner. The route's write check (S2-52) already refused an
 * agent's or a service's token, so the principal here is a user's; a subject that is not a user id is refused too.
 */
private fun AuthenticatedPrincipal.person(): Uuid =
    Uuid.parseOrNull(subjectId) ?: throw CutoverOwnerRequiredException()
