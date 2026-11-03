// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.presentation

import dev.ostrom.server.identity.domain.NOTIFICATIONS_WRITE_SCOPE
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.notifications.domain.PushCredential
import dev.ostrom.server.notifications.domain.PushDeviceNotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The phones routes: a person registers and removes their own phones in the organization they act in (`Ostrom-Org`,
 * or their only one), with the phone app's token or a browser session, the credentials that hold
 * `notifications:write`. A personal token never does (an agent must not silence its person's approvals), nor an
 * agent, a device or a service key.
 *
 * @param dependencies The use cases.
 */
fun Route.pushDevicesRoutes(dependencies: PushDevicesRoutesDependencies) = with(dependencies) {
    authenticated {
        post<PushDevicesApi, PushDeviceRequest> { _, body ->
            val member = call.phoneOwner(orgs)
            val credential = call.requirePrincipal().pushCredential()
            val device = registerPhone(member.orgId, member.memberId, body.toRegistration(), credential)
            call.respond(HttpStatusCode.Created, device.toDto())
        }
        delete<PushDevicesApi.Id> { params ->
            val member = call.phoneOwner(orgs)
            val id = Uuid.parseOrNull(params.id) ?: throw PushDeviceNotFoundException()
            removePhone(member.orgId, member.memberId, id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// The credential the phone is notified through: a person's app token or browser session. A principal without one, or
// not a person's, registers a phone nothing is ever pushed to.
internal fun AuthenticatedPrincipal.pushCredential(): PushCredential? {
    val user = Uuid.parseOrNull(subjectId)
    val id = credentialId?.let(Uuid::parseOrNull)
    if (kind != PrincipalKind.USER || user == null || id == null) return null
    return PushCredential(user, id, channel == PrincipalChannel.BROWSER)
}

// The scope first (SECURITY-AUDIT S2-03): a credential that may not register learns nothing about its organization.
private suspend fun ApplicationCall.phoneOwner(orgs: PrincipalOrgResolver): OrgPrincipal {
    requireScope(NOTIFICATIONS_WRITE_SCOPE)
    return requireOrgPrincipal(orgs, null)
}
