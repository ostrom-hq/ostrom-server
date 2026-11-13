// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.presentation

import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.EnvironmentActor
import run.ostrom.server.secrets.domain.EnvironmentVariantNotFoundException
import run.ostrom.server.secrets.domain.MemberNotFoundException
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.ScopeRequiredException
import run.ostrom.server.secrets.domain.SecretValue
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The environment routes: the administrators' variants, assignments and exclusions (scopes `secrets:read` and
 * `secrets:write`, console writes with a recent sign-in), the members' personal variants, and the caller's own values.
 *
 * @param dependencies The use cases.
 */
fun Route.environmentRoutes(dependencies: EnvironmentRoutesDependencies) = with(dependencies) {
    authenticated {
        get<EnvironmentApi> {
            val caller = call.requirePerson(callers, RelayScopes.ENVIRONMENT_READ)
            val values = resolve(caller.orgId, caller.memberId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                EnvironmentValuesResponse(values.values.mapValues { it.value.reveal() }, values.unavailable),
            )
        }
        adminVariantRoutes(dependencies)
        assignmentRoutes(dependencies)
        personalRoutes(dependencies)
    }
}

private fun Route.adminVariantRoutes(dependencies: EnvironmentRoutesDependencies) = with(dependencies) {
    get<EnvironmentApi.Variants> {
        val caller = call.requireCaller(callers, RelayScopes.SECRETS_READ)
        call.respond(EnvironmentVariantsPageResponse(variants.list(caller, personal = false).map { it.toResponse() }))
    }
    get<EnvironmentApi.Variants.Personal> {
        val caller = call.requireCaller(callers, RelayScopes.SECRETS_READ)
        call.respond(EnvironmentVariantsPageResponse(variants.list(caller, personal = true).map { it.toResponse() }))
    }
    post<EnvironmentApi.Variants> {
        val actor = call.writer(dependencies)
        val request = call.receive<EnvironmentVariantCreateRequest>()
        call.respond(HttpStatusCode.Created, variants.create(actor, request.toNew()).toResponse())
    }
    patch<EnvironmentApi.Variants.Id> { params ->
        val actor = call.writer(dependencies)
        val request = call.receive<EnvironmentVariantUpdateRequest>()
        call.respond(variants.update(actor, variantId(params.id), request.toUpdate()).toResponse())
    }
    delete<EnvironmentApi.Variants.Id> { params ->
        val actor = call.writer(dependencies)
        variants.delete(actor, variantId(params.id))
        call.respond(HttpStatusCode.NoContent)
    }
    post<EnvironmentApi.Variants.Id.BulkAssign> { params ->
        val actor = call.writer(dependencies)
        val result = variants.bulkAssign(actor, variantId(params.parent.id))
        call.respond(EnvironmentBulkAssignmentResponse(result.assigned, result.keptDirect, result.excluded))
    }
    post<EnvironmentApi.Variants.Id.Promote> { params ->
        val actor = call.writer(dependencies)
        call.respond(variants.promote(actor, variantId(params.parent.id)).toResponse())
    }
}

private fun Route.assignmentRoutes(dependencies: EnvironmentRoutesDependencies) = with(dependencies) {
    get<EnvironmentApi.Assignments> { params ->
        val caller = call.requireCaller(callers, RelayScopes.SECRETS_READ)
        val (items, exclusions) = assignments.list(caller, params.member?.let(::memberId))
        call.respond(
            EnvironmentAssignmentsPageResponse(
                items.map { it.toResponse() },
                exclusions.map { EnvironmentExclusionResponse(it.memberId.toString(), it.variable) },
            ),
        )
    }
    put<EnvironmentApi.Assignments.Slot> { params ->
        val actor = call.writer(dependencies)
        val request = call.receive<EnvironmentAssignRequest>()
        val assignment = assignments.assign(
            actor,
            memberId(params.member),
            params.variable,
            variantId(request.variantId),
            request.value?.let(::SecretValue),
        )
        call.respond(assignment.toResponse())
    }
    delete<EnvironmentApi.Assignments.Slot> { params ->
        val actor = call.writer(dependencies)
        assignments.unassign(actor, memberId(params.member), params.variable)
        call.respond(HttpStatusCode.NoContent)
    }
    put<EnvironmentApi.Exclusions.Slot> { params ->
        val actor = call.writer(dependencies)
        assignments.exclude(actor, memberId(params.member), params.variable)
        call.respond(HttpStatusCode.NoContent)
    }
    delete<EnvironmentApi.Exclusions.Slot> { params ->
        val actor = call.writer(dependencies)
        assignments.include(actor, memberId(params.member), params.variable)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.personalRoutes(dependencies: EnvironmentRoutesDependencies) = with(dependencies) {
    get<MyEnvironmentVariantsApi> {
        val caller = call.requirePerson(callers, RelayScopes.SECRETS_READ)
        call.respond(EnvironmentVariantsPageResponse(personal.list(caller).map { it.toResponse() }))
    }
    post<MyEnvironmentVariantsApi> {
        val actor = call.writer(dependencies)
        val request = call.receive<EnvironmentVariantCreateRequest>()
        call.respond(HttpStatusCode.Created, personal.create(actor, request.toNew()).toResponse())
    }
    patch<MyEnvironmentVariantsApi.Id> { params ->
        val actor = call.writer(dependencies)
        val request = call.receive<EnvironmentVariantUpdateRequest>()
        call.respond(personal.update(actor, variantId(params.id), request.toUpdate()).toResponse())
    }
    delete<MyEnvironmentVariantsApi.Id> { params ->
        val actor = call.writer(dependencies)
        personal.delete(actor, variantId(params.id))
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * A person's own call: a service key is refused even when it holds the scope (a member's values are theirs, never a
 * robot's), and so are agent and device tokens ([requireCaller] admits neither).
 */
private suspend fun ApplicationCall.requirePerson(callers: CallerResolver, scope: String): Caller {
    if (requirePrincipal().kind == PrincipalKind.SERVICE) throw ScopeRequiredException("a person's sign-in")
    return requireCaller(callers, scope)
}

/**
 * A console write: a browser session (no token, a service key's included, may write here) signed in within five
 * minutes (step-up), journaled with its credential.
 */
private suspend fun ApplicationCall.writer(dependencies: EnvironmentRoutesDependencies): EnvironmentActor {
    val caller = requireCaller(dependencies.callers, RelayScopes.SECRETS_WRITE)
    requireRecentSignIn("change an environment value")
    return EnvironmentActor(caller, requirePrincipal())
}

private fun variantId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw EnvironmentVariantNotFoundException()

private fun memberId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw MemberNotFoundException()
