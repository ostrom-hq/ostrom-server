// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.admin.presentation

import run.ostrom.server.admin.domain.AdminCaller
import run.ostrom.server.admin.domain.InstanceAdminNotFoundException
import run.ostrom.server.admin.domain.InstanceAdminView
import run.ostrom.server.admin.domain.SettingsChange
import run.ostrom.server.admin.domain.SettingsView
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requirePrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The instance's administration under `/api/v1/admin`: a person who administers the instance, signed in (a browser
 * session or the unlocked app), never a key whatever its scopes (TeamCode: the dashboard's settings are an
 * interactive administrator's). Each use case checks the caller before anything else; a write checks it before its
 * body is even read, so a caller who may not never has it parsed (TeamCode 3b716702: the gate after authentication).
 *
 * @param dependencies The use cases.
 */
fun Route.adminRoutes(dependencies: AdminRoutesDependencies) = with(dependencies) {
    authenticated {
        get<InstanceAdminsApi> {
            val list = admins.list(call.requirePrincipal().adminCaller())
            call.respond(InstanceAdminListResponse(list.map { it.toResponse() }))
        }
        post<InstanceAdminsApi> {
            val caller = call.requirePrincipal().adminCaller()
            admins.requireAdmin(caller)
            val request = call.receive<AddInstanceAdminRequest>()
            call.respond(InstanceAdminListResponse(admins.add(caller, request.email).map { it.toResponse() }))
        }
        delete<InstanceAdminsApi.One> { params ->
            val caller = call.requirePrincipal().adminCaller()
            admins.requireAdmin(caller)
            admins.remove(caller, Uuid.parseOrNull(params.userId) ?: throw InstanceAdminNotFoundException())
            call.respond(HttpStatusCode.NoContent)
        }
        get<InstanceSettingsApi> {
            val sections = settings.list(call.requirePrincipal().adminCaller())
            call.respond(InstanceSettingsListResponse(sections.map { it.toResponse() }))
        }
        get<InstanceSettingsApi.Section> { params ->
            call.respond(settings.get(call.requirePrincipal().adminCaller(), params.section).toResponse())
        }
        put<InstanceSettingsApi.Section> { params ->
            val caller = call.requirePrincipal().adminCaller()
            admins.requireAdmin(caller)
            val request = call.receive<SaveInstanceSettingsRequest>()
            val change = SettingsChange(request.version, request.values, request.secrets)
            call.respond(settings.save(caller, params.section, change).toResponse())
        }
    }
}

/** The caller of the administration: a person only when the credential is a person's own. */
internal fun AuthenticatedPrincipal.adminCaller(): AdminCaller = AdminCaller(
    userId = Uuid.parseOrNull(subjectId) ?: Uuid.NIL,
    person = kind == PrincipalKind.USER && mayWrite(WriteAccess.PERSON),
)

internal fun InstanceAdminView.toResponse(): InstanceAdminResponse = InstanceAdminResponse(
    userId = admin.userId.toString(),
    login = account?.login,
    email = account?.email,
    source = admin.source.wire,
    addedAt = admin.addedAt.toString(),
    addedBy = admin.addedBy?.toString(),
)

internal fun SettingsView.toResponse(): InstanceSettingsResponse = InstanceSettingsResponse(
    section = section.name,
    version = version,
    values = stored?.values ?: emptyMap(),
    secrets = secrets.mapValues { InstanceSecretStateResponse(it.value) },
    fields = section.fields.map { InstanceSettingFieldResponse(it.name, it.secret, it.required) },
    updatedAt = stored?.updatedAt?.toString(),
    updatedBy = stored?.updatedBy?.toString(),
    importedFromEnvironment = stored?.importedFromEnvironment ?: false,
)
