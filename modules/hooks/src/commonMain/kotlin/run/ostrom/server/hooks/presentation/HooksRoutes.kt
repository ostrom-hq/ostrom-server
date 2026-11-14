// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.presentation

import run.ostrom.server.hooks.domain.HookLimits
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.presentation.AssignmentDto
import run.ostrom.server.skills.presentation.AssignmentListDto
import run.ostrom.server.skills.presentation.PinSkillRequest
import run.ostrom.server.skills.presentation.RunnerArtifactRequestDto
import run.ostrom.server.skills.presentation.RunnerExecutionDto
import run.ostrom.server.skills.presentation.SetAssignmentRequest
import run.ostrom.server.skills.presentation.SetAudienceRequest
import run.ostrom.server.skills.presentation.catalogCallerOf
import run.ostrom.server.skills.presentation.toArtifactFiles
import run.ostrom.server.skills.presentation.toDecision
import run.ostrom.server.skills.presentation.runnerToken
import run.ostrom.server.skills.presentation.toDto
import run.ostrom.server.skills.presentation.toSessionId
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The hook catalog under `/api/v1/hooks` (owners and admins in the console; publication also by a CI's service key)
 * and the delivery under `/api/v1/hook-delivery` and `/api/v1/members/{memberId}/hook-delivery` (ostrom-core, tag
 * `delivery`).
 *
 * @param dependencies The use cases.
 */
fun Route.hooksRoutes(dependencies: HooksRoutesDependencies) {
    authenticated {
        catalogRoutes(dependencies)
        deliveryRoutes(dependencies)
    }
    runnerRoutes(dependencies)
}

/** The catalog: publication (the console or a CI's key), and everything else for administrators in the console. */
private fun Route.catalogRoutes(dependencies: HooksRoutesDependencies) = with(dependencies) {
    suspend fun ApplicationCall.caller(): CatalogCaller = orgResolver.catalogCallerOf(requirePrincipal())
    // A version carries up to 1 MiB of files, base64 in JSON.
    bodyLimit(PUBLISH_BODY_LIMIT) {
        post<HooksApi.Name.Versions> { params ->
            val caller = call.caller()
            publish.authorize(caller)
            val request = call.receive<PublishHookVersionRequest>()
            val publication = publish(
                caller, params.parent.name, request.version, request.definition.toDomain(),
                request.files.toArtifactFiles(HookLimits.KIND), request.allMembers,
            )
            val status = if (publication.created) HttpStatusCode.Created else HttpStatusCode.OK
            call.respond(status, publication.toDto())
        }
    }
    get<HooksApi> {
        call.respond(HookCatalogDto(manage.catalog(call.caller()).map { it.toDto() }))
    }
    get<HooksApi.Name.Versions> { params ->
        call.respond(manage.versions(call.caller(), params.parent.name).toDto())
    }
    get<HooksApi.Name.Versions.Version> { params ->
        call.respond(manage.read(call.caller(), params.parent.parent.name, params.version).toDto())
    }
    put<HooksApi.Name.Pin> { params ->
        val caller = call.caller()
        val request = call.receive<PinSkillRequest>()
        call.respond(manage.pin(caller, params.parent.name, request.version).toDto())
    }
    delete<HooksApi.Name.Pin> { params ->
        call.respond(manage.unpin(call.caller(), params.parent.name).toDto())
    }
    put<HooksApi.Name.Audience> { params ->
        val caller = call.caller()
        val request = call.receive<SetAudienceRequest>()
        call.respond(manage.setAudience(caller, params.parent.name, request.allMembers).toDto())
    }
    get<HooksApi.Name.Assignments> { params ->
        val assignments = manage.assignments(call.caller(), params.parent.name)
        call.respond(AssignmentListDto(assignments.map { it.toDto() }))
    }
    put<HooksApi.Name.Assignments.Member> { params ->
        val caller = call.caller()
        val decision = call.receive<SetAssignmentRequest>().toDecision(HookLimits.KIND)
        val assignment = manage.assign(caller, params.parent.parent.name, params.memberId, decision)
        call.respond(AssignmentDto(assignment.memberId, decision.wire, assignment.updatedAt.toString()))
    }
    delete<HooksApi.Name.Assignments.Member> { params ->
        manage.assign(call.caller(), params.parent.parent.name, params.memberId, null)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** What members receive: their own, or a member's for an administrator. */
private fun Route.deliveryRoutes(dependencies: HooksRoutesDependencies) = with(dependencies) {
    suspend fun ApplicationCall.caller(): CatalogCaller = orgResolver.catalogCallerOf(requirePrincipal())
    get<HookDeliveryApi> {
        val caller = call.caller()
        val hooks = delivery.effective(caller, caller.memberId)
        call.respond(HookManifestDto(caller.memberId, hooks.map { it.toDto() }))
    }
    get<MemberHookDeliveryApi> { params ->
        val hooks = delivery.effective(call.caller(), params.memberId)
        call.respond(HookManifestDto(params.memberId, hooks.map { it.toDto() }))
    }
    get<HookDeliveryApi.Download> { params ->
        call.respond(delivery.download(call.caller(), params.name, params.version).toDto())
    }
}

/**
 * What a cloud session's runner installs for its owner: the execution's runner token, checked before the body is
 * read, never a member's.
 */
private fun Route.runnerRoutes(dependencies: HooksRoutesDependencies) = with(dependencies) {
    post<RunnerHooksApi> {
        val token = call.runnerToken()
        runners.check(token)
        val body = call.receive<RunnerExecutionDto>()
        val owner = runners.owner(token, body.sessionId.toSessionId(), body.epoch)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(HookManifestDto(owner.memberId, delivery.forRunner(owner).map { it.toDto() }))
    }
    post<RunnerHooksApi.Artifact> {
        val token = call.runnerToken()
        runners.check(token)
        val body = call.receive<RunnerArtifactRequestDto>()
        val owner = runners.owner(token, body.sessionId.toSessionId(), body.epoch)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(delivery.downloadForRunner(owner, body.name, body.version).toDto())
    }
}

/** 2 MiB: 1 MiB of files in base64, and their JSON. */
private const val PUBLISH_BODY_LIMIT = 2L shl 20
