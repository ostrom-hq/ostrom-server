// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.CreateServiceKeyUseCase
import dev.ostrom.server.identity.domain.DeleteServiceKeyUseCase
import dev.ostrom.server.identity.domain.DeviceNotFoundException
import dev.ostrom.server.identity.domain.ListDevicesUseCase
import dev.ostrom.server.identity.domain.ListServiceKeysUseCase
import dev.ostrom.server.identity.domain.ServiceKeyNotFoundException
import dev.ostrom.server.identity.domain.SignOutDeviceUseCase
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * The dependencies of the service-key and device routes.
 *
 * @property orgs Resolves the organization a request acts in and the caller's role there.
 * @property createServiceKey Creates a service key.
 * @property listServiceKeys Lists them.
 * @property deleteServiceKey Deletes one.
 * @property listDevices Lists the caller's workstations.
 * @property signOutDevice Signs one out.
 */
data class KeysAndDevicesRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val createServiceKey: CreateServiceKeyUseCase,
    val listServiceKeys: ListServiceKeysUseCase,
    val deleteServiceKey: DeleteServiceKeyUseCase,
    val listDevices: ListDevicesUseCase,
    val signOutDevice: SignOutDeviceUseCase,
)

/**
 * The service keys of an organization (its owners and admins) and the caller's signed-in workstations. Every route
 * needs a browser session, like the personal tokens: a token never mints a key nor signs a machine out (S2-52), so a
 * leaked one cannot spread. The organization is the one `Ostrom-Org` names, or the caller's only one; a member of
 * another, an unknown key or someone else's device is answered 404.
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
            call.respond(DevicesResponse(listDevices(userId).map { it.toDeviceResponse() }))
        }
        delete<DevicesApi.Id> { params ->
            val userId = call.requireBrowserSession().userId()
            signOutDevice(userId, Uuid.parseOrNull(params.id) ?: throw DeviceNotFoundException())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
