// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.presentation

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.presentation.requireAnswering
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.notifications.domain.PushDevice
import dev.ostrom.server.notifications.domain.PushRegistration
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The phones routes: a person registers and removes their own, from the app or the console (a credential that may
 * answer requests, SECURITY-AUDIT S2-03).
 *
 * @param dependencies The use cases.
 */
fun Route.notificationsRoutes(dependencies: NotificationsRoutesDependencies) = with(dependencies) {
    authenticated {
        post<PushDevicesApi> {
            val actor = personOf(call)
            val body = call.receive<PushDeviceRequest>()
            val registration = PushRegistration(body.pushToken, body.provider, body.platform, body.locale)
            val device = devices.register(actor, registration)
            call.respond(HttpStatusCode.Created, device.toDto())
        }
        delete<PushDevicesApi.Id> { params ->
            val actor = personOf(call)
            val id = Uuid.parseOrNull(params.id)
                ?: throw OstromException(ErrorKind.NOT_FOUND, "This phone is not registered.")
            devices.remove(actor, id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun NotificationsRoutesDependencies.personOf(call: ApplicationCall): GateActor {
    val principal = call.requirePrincipal()
    // The same credentials as a yes: a browser session or the unlocked app, never an API token (S2-03, S2-31).
    principal.requireAnswering()
    return actors.resolve(principal)
        ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")
}

private fun PushDevice.toDto() = PushDeviceDto(id.toString(), provider, platform, locale, createdAt.toString())
