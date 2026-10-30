// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.MobileUnlockRefusedException
import dev.ostrom.server.identity.domain.NOTIFICATIONS_WRITE_SCOPE
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireScope
import io.ktor.server.plugins.origin
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The phone app's routes. The code exchange is not authenticated (it is how the app gets its token): the code, its
 * PKCE verifier and the deep link prove it. The unlock takes the app's token only. The notification preferences are
 * the caller's own, read with `profile:read` and written with `notifications:write` (a browser session has both).
 *
 * @param dependencies The use cases.
 */
fun Route.mobileRoutes(dependencies: MobileRoutesDependencies) = with(dependencies) {
    post<MobileApi.Token, MobileTokenRequest> { _, request ->
        val source = call.request.origin.remoteAddress
        call.respond(exchangeCode(request.code, request.codeVerifier, request.redirectUri, source).toResponse())
    }
    authenticated {
        post<MobileApi.Unlock, MobileUnlockRequest> { _, request ->
            val principal = call.requirePrincipal()
            // Only the app's own token unlocks: the CLI or a browser has no biometrics to prove.
            if (principal.channel != PrincipalChannel.MOBILE_APP) throw MobileUnlockRefusedException()
            val at = unlock(principal.userId(), principal.credentialUuid(), request.unlockKey)
            call.respond(MobileUnlockResponse(at.toString()))
        }
        get<MySettingsApi.Notifications> {
            val principal = call.requireScope("profile:read")
            call.respond(getNotifications(principal.userId()).toBody())
        }
        put<MySettingsApi.Notifications, NotificationPreferencesBody> { _, body ->
            val principal = call.requireScope(NOTIFICATIONS_WRITE_SCOPE)
            call.respond(setNotifications(principal.userId(), body.toPreferences()).toBody())
        }
    }
}
