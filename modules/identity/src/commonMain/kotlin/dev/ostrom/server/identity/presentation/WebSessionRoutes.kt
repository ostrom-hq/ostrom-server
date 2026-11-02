// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.WebSessionNotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/** The person's browser sessions (the console's settings), from a browser session only. */
internal fun Route.webSessionRoutes(dependencies: IdentityRoutesDependencies) = with(dependencies) {
    get<AuthApi.Sessions> {
        val principal = call.requireBrowserSession()
        val current = principal.credentialUuid()
        val sessions = webSessions.list(principal.userId()).map { it.toAuthSessionResponse(current) }
        call.respond(AuthSessionsResponse(sessions))
    }
    delete<AuthApi.Sessions.Id> { params ->
        val principal = call.requireBrowserSession()
        val sessionId = Uuid.parseOrNull(params.id) ?: throw WebSessionNotFoundException()
        webSessions.revoke(principal.userId(), sessionId)
        // Ending the session making the request is signing out here.
        if (sessionId == principal.credentialUuid()) call.clearSessionCookie(config)
        signOutListeners.forEach { it.signedOut(principal.subjectId, sessionId.toString()) }
        call.respond(HttpStatusCode.NoContent)
    }
}
