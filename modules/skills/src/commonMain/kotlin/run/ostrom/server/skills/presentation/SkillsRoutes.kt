// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.Publication
import run.ostrom.server.skills.domain.SkillChange
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The skill catalog under `/api/v1/skills` (owners and admins in the console; publication and pinning also by a CI's
 * service key), the members' personal skills under `/api/v1/me/skills`, and the delivery under
 * `/api/v1/skill-delivery` and `/api/v1/members/{memberId}/...` (ostrom-core, tag `delivery`).
 *
 * @param dependencies The use cases.
 */
@Suppress("LongMethod") // One route per contract operation, each a few lines.
fun Route.skillsRoutes(dependencies: SkillsRoutesDependencies) = with(dependencies) {
    suspend fun ApplicationCall.caller(): CatalogCaller = orgResolver.catalogCallerOf(requirePrincipal())
    authenticated {
        // A version carries up to 5 MiB of files, base64 in JSON.
        bodyLimit(PUBLISH_BODY_LIMIT) {
            post<SkillsApi.Name.Versions> { params ->
                val caller = call.caller()
                publish.authorize(caller)
                val request = call.receive<PublishSkillVersionRequest>()
                val publication = publish(
                    caller, params.parent.name, request.version, request.files.toArtifactFiles(KIND), request.pin,
                    request.allMembers,
                )
                call.respondPublication(publication)
            }
            post<MySkillsApi.Name.Versions> { params ->
                val caller = call.caller()
                publish.authorizePersonal(caller)
                val request = call.receive<PublishSkillVersionRequest>()
                val publication = publish.personal(
                    caller, params.parent.name, request.version, request.files.toArtifactFiles(KIND), request.pin,
                )
                call.respondPublication(publication)
            }
            put<SkillDeliveryApi.Installations> {
                val caller = call.caller()
                delivery.report(caller, call.receive<ReportSkillInstallationsRequest>().toReports())
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // The team catalog.
        get<SkillsApi> {
            call.respond(SkillCatalogDto(manage.catalog(call.caller()).map { it.toDto() }))
        }
        delete<SkillsApi.Name> { params ->
            call.respond(manage.change(call.caller(), params.name, SkillChange.RETIRE).toDto())
        }
        post<SkillsApi.Name.Restore> { params ->
            call.respond(manage.change(call.caller(), params.parent.name, SkillChange.RESTORE).toDto())
        }
        get<SkillsApi.Name.Versions> { params ->
            call.respond(manage.versions(call.caller(), params.parent.name).toDto())
        }
        delete<SkillsApi.Name.Versions.Version> { params ->
            val version = manage.retireVersion(call.caller(), params.parent.parent.name, params.version)
            call.respond(version.toDto(pinned = false))
        }
        put<SkillsApi.Name.Pin> { params ->
            val caller = call.caller()
            manage.authorizePin(caller)
            val request = call.receive<PinSkillRequest>()
            call.respond(manage.pin(caller, params.parent.name, request.version).toDto())
        }
        delete<SkillsApi.Name.Pin> { params ->
            call.respond(manage.change(call.caller(), params.parent.name, SkillChange.UNPIN).toDto())
        }
        put<SkillsApi.Name.Audience> { params ->
            val caller = call.caller()
            val request = call.receive<SetAudienceRequest>()
            call.respond(manage.setAudience(caller, params.parent.name, request.allMembers).toDto())
        }
        get<SkillsApi.Name.Assignments> { params ->
            val assignments = manage.assignments(call.caller(), params.parent.name)
            call.respond(AssignmentListDto(assignments.map { it.toDto() }))
        }
        put<SkillsApi.Name.Assignments.Member> { params ->
            val caller = call.caller()
            val decision = call.receive<SetAssignmentRequest>().toDecision(KIND)
            val assignment = manage.assign(caller, params.parent.parent.name, params.memberId, decision)
            call.respond(assignment.toDto(decision.wire))
        }
        delete<SkillsApi.Name.Assignments.Member> { params ->
            manage.assign(call.caller(), params.parent.parent.name, params.memberId, null)
            call.respond(HttpStatusCode.NoContent)
        }
        post<SkillsApi.Name.Promote> { params ->
            call.respond(manage.promote(call.caller(), params.parent.name).toDto())
        }

        // The caller's personal skills.
        get<MySkillsApi> {
            call.respond(SkillCatalogDto(manage.mine(call.caller()).map { it.toDto() }))
        }
        delete<MySkillsApi.Name> { params ->
            call.respond(manage.changeMine(call.caller(), params.name, SkillChange.RETIRE).toDto())
        }
        post<MySkillsApi.Name.Restore> { params ->
            call.respond(manage.changeMine(call.caller(), params.parent.name, SkillChange.RESTORE).toDto())
        }
        get<MySkillsApi.Name.Versions> { params ->
            call.respond(manage.myVersions(call.caller(), params.parent.name).toDto())
        }
        put<MySkillsApi.Name.Pin> { params ->
            val caller = call.caller()
            val request = call.receive<PinSkillRequest>()
            call.respond(manage.pinMine(caller, params.parent.name, request.version).toDto())
        }
        delete<MySkillsApi.Name.Pin> { params ->
            call.respond(manage.changeMine(call.caller(), params.parent.name, SkillChange.UNPIN).toDto())
        }

        // Delivery.
        get<SkillDeliveryApi> {
            val caller = call.caller()
            val skills = delivery.effective(caller, caller.memberId)
            call.respond(SkillManifestDto(caller.memberId, skills.map { it.toDto() }))
        }
        get<MemberSkillDeliveryApi> { params ->
            val skills = delivery.effective(call.caller(), params.memberId)
            call.respond(SkillManifestDto(params.memberId, skills.map { it.toDto() }))
        }
        get<SkillDeliveryApi.Download> { params ->
            call.respond(delivery.download(call.caller(), params.name, params.version).toDto())
        }
        get<SkillDeliveryApi.Installations> {
            val caller = call.caller()
            call.respond(SkillInstallationListDto(delivery.installations(caller, caller.memberId).map { it.toDto() }))
        }
        get<MemberSkillInstallationsApi> { params ->
            val installations = delivery.installations(call.caller(), params.memberId)
            call.respond(SkillInstallationListDto(installations.map { it.toDto() }))
        }
    }
}

private const val KIND = "skill"

/** 201 for a new version, 200 when the label was already published with the same digest. */
private suspend fun ApplicationCall.respondPublication(publication: Publication) =
    respond(if (publication.created) HttpStatusCode.Created else HttpStatusCode.OK, publication.toDto())

/** 8 MiB: 5 MiB of files in base64, and their JSON. */
private const val PUBLISH_BODY_LIMIT = 8L shl 20
