// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.DeviceNotFoundException
import run.ostrom.server.identity.domain.ServiceKeyNotFoundException
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * The service keys of an organization (its owners and admins) and the caller's signed-in workstations. Every route
 * needs a browser session, like the personal tokens: a token never mints a key nor signs a machine out (S2-52), so a
 * leaked one cannot spread. The organization of the keys is the one `Ostrom-Org` names, or the caller's only one; the
 * tokens of `ostrom login --admin` are listed and signed out only in the one `Ostrom-Org` names, a member's own, any
 * member's for an owner or an admin. A member of another organization, an unknown key or a device the caller may not
 * sign out is answered 404.
 *
 * @param dependencies The use cases.
 */
fun Route.keysAndDevicesRoutes(dependencies: KeysAndDevicesRoutesDependencies) = with(dependencies) {
    authenticated {
        get<ServiceKeysApi> {
            call.requireBrowserSession()
            val member = call.requireOrgPrincipal(orgs, null)
            call.respond(ServiceKeysResponse(listServiceKeys(member).map { it.toServiceKeyResponse() }))
        }
        post<ServiceKeysApi, CreateServiceKeyRequest> { _, request ->
            val userId = call.requireBrowserSession().userId()
            val member = call.requireOrgPrincipal(orgs, null)
            call.requireRecentSignIn("create a service key")
            val issued = createServiceKey(member, userId, request.name, request.scopes, request.expiresInDays?.days)
            call.respond(HttpStatusCode.Created, issued.toCreatedServiceKeyResponse())
        }
        delete<ServiceKeysApi.Id> { params ->
            val userId = call.requireBrowserSession().userId()
            val member = call.requireOrgPrincipal(orgs, null)
            deleteServiceKey(member, userId, Uuid.parseOrNull(params.id) ?: throw ServiceKeyNotFoundException())
            call.respond(HttpStatusCode.NoContent)
        }
        get<DevicesApi> {
            val userId = call.requireBrowserSession().userId()
            val devices = listDevices(userId, call.namedMember(orgs))
            call.respond(DevicesResponse(devices.map { it.toDeviceResponse() }))
        }
        delete<DevicesApi.Id> { params ->
            val userId = call.requireBrowserSession().userId()
            val member = call.namedMember(orgs)
            signOutDevice(userId, Uuid.parseOrNull(params.id) ?: throw DeviceNotFoundException(), member)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The caller in the organization the request names in its `Ostrom-Org` header, where the tokens of
 * `ostrom login --admin` bound to it are read; null when it names none, never the caller's only organization by
 * default. A member of another organization is answered 404.
 */
private suspend fun ApplicationCall.namedMember(orgs: PrincipalOrgResolver): OrgPrincipal? =
    if (requirePrincipal().namedOrg == null) null else requireOrgPrincipal(orgs, null)
