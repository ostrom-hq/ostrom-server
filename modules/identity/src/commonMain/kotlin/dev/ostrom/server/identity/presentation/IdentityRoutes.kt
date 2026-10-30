// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.DEVICE_POLL_INTERVAL
import dev.ostrom.server.identity.domain.SessionRequiredException
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.TokenNotFoundException
import dev.ostrom.server.identity.domain.isBrowserSession
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * The identity routes.
 *
 * @param dependencies The use cases.
 */
fun Route.identityRoutes(dependencies: IdentityRoutesDependencies) {
    deviceLoginRoutes(dependencies)
    authenticated {
        accountRoutes(dependencies)
        tokenRoutes(dependencies)
        deviceAnswerRoutes(dependencies)
    }
}

/** The CLI side of `ostrom login` (RFC 8628), before it holds any credential. */
private fun Route.deviceLoginRoutes(dependencies: IdentityRoutesDependencies) = with(dependencies) {
    post<AuthApi.Device> {
        // The body is optional: a CLI that sends none gets the default client name.
        val hasBody = call.request.headers[HttpHeaders.ContentType] != null
        val request = if (hasBody) call.receive<DeviceCodeRequest>() else null
        val started = startDeviceAuthorization(request?.clientName, call.request.origin.remoteAddress)
        val authorization = started.authorization
        val page = config.consoleUrl + "/device"
        call.respond(
            DeviceCodeResponse(
                deviceCode = started.deviceCode,
                userCode = authorization.userCode,
                verificationUri = page,
                verificationUriComplete = page + "?code=" + authorization.userCode,
                expiresIn = (authorization.expiresAt - authorization.createdAt).inWholeSeconds,
                interval = DEVICE_POLL_INTERVAL.inWholeSeconds,
            ),
        )
    }
    post<AuthApi.Device.Token, DeviceTokenRequest> { _, request ->
        call.respond(pollDeviceAuthorization(request.deviceCode).toDeviceTokenResponse())
    }
}

/** The signed-in user: profile, account audit log, sign-out. */
private fun Route.accountRoutes(dependencies: IdentityRoutesDependencies) = with(dependencies) {
    get<MeApi> {
        val principal = call.requireScope("profile:read")
        val session = call.request.cookies[SESSION_COOKIE]?.takeIf { principal.isBrowserSession }
        val csrf = session?.let { hasher.csrfToken(it) }
        call.respond(getProfile(principal.userId()).toProfileResponse(csrf))
    }
    get<MeApi.Audit> { params ->
        val principal = call.requireScope("profile:read")
        val events = listAuditEvents(null, principal.userId(), params.before, params.limit)
        call.respond(events.toAuditEventsResponse(params.limit))
    }
    post<AuthApi.Logout> {
        val principal = call.requirePrincipal()
        val userId = principal.userId()
        val credential = principal.credentialId?.let(::parseId) ?: throw SessionRequiredException()
        if (principal.isBrowserSession) {
            endWebSession(credential)
            call.clearSessionCookie(config)
        } else {
            revokeToken(userId, credential)
        }
        call.respond(HttpStatusCode.NoContent)
    }
    post<AuthApi.Logout.Everywhere> {
        val principal = call.requireBrowserSession()
        endWebSession.everywhere(principal.userId())
        call.clearSessionCookie(config)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Personal and CLI tokens, managed from a browser session only. */
private fun Route.tokenRoutes(dependencies: IdentityRoutesDependencies) = with(dependencies) {
    get<TokensApi> {
        val principal = call.requireBrowserSession()
        call.respond(TokensResponse(listTokens(principal.userId()).map { it.toTokenResponse() }))
    }
    post<TokensApi, CreateTokenRequest> { _, request ->
        val principal = call.requireBrowserSession()
        val lifetime = request.expiresInDays?.days
        val issued = createPersonalToken(principal.userId(), request.name, request.scopes, lifetime)
        call.respond(HttpStatusCode.Created, issued.toCreatedTokenResponse())
    }
    delete<TokensApi.Id> { params ->
        val principal = call.requireBrowserSession()
        revokeToken(principal.userId(), parseId(params.id) ?: throw TokenNotFoundException())
        call.respond(HttpStatusCode.NoContent)
    }
    post<TokensApi.Id.Rotate> { params ->
        val principal = call.requireBrowserSession()
        val issued = rotateToken(principal.userId(), parseId(params.parent.id) ?: throw TokenNotFoundException())
        call.respond(HttpStatusCode.Created, issued.toCreatedTokenResponse())
    }
}

/** The console side of `ostrom login`: the user sees the request and answers it. */
private fun Route.deviceAnswerRoutes(dependencies: IdentityRoutesDependencies) = with(dependencies) {
    get<AuthApi.Device> { params ->
        val principal = call.requireBrowserSession()
        val code = params.userCode ?: throw OstromException(ErrorKind.INVALID_REQUEST, "userCode is required.")
        val authorization = getDeviceAuthorization(principal.userId(), code)
        val expiresAt = authorization.expiresAt.toString()
        call.respond(DeviceAuthorizationResponse(authorization.userCode, authorization.clientName, expiresAt))
    }
    post<AuthApi.Device.Answer, AnswerDeviceRequest> { _, request ->
        val principal = call.requireBrowserSession()
        answerDeviceAuthorization(principal.userId(), request.userCode, request.approve)
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Sets the session cookie after a sign-in: HttpOnly (no script reads it), Secure when the API is served over HTTPS,
 * SameSite=Lax (sent on the console's own requests and top-level navigations, not on another site's forms), for
 * the whole API, until the session expires.
 *
 * @param started The session.
 * @param config Where the API is served.
 */
fun ApplicationCall.setSessionCookie(started: StartedSession, config: IdentityConfig) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = started.secret,
            maxAge = (started.session.expiresAt - started.session.createdAt).inWholeSeconds.toInt(),
            path = "/",
            secure = config.secureCookies,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

/**
 * Removes the session cookie (sign-out).
 *
 * @param config Where the API is served.
 */
fun ApplicationCall.clearSessionCookie(config: IdentityConfig) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = "",
            maxAge = 0,
            path = "/",
            secure = config.secureCookies,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

/**
 * The principal, refused unless it is a user's browser session: managing tokens and approving a CLI login are
 * never done with a token, so a leaked token cannot mint others.
 *
 * @return The principal.
 *
 * @throws SessionRequiredException Otherwise.
 */
fun ApplicationCall.requireBrowserSession(): AuthenticatedPrincipal {
    val principal = requirePrincipal()
    if (!principal.isBrowserSession) throw SessionRequiredException()
    return principal
}

/**
 * The user id of a user principal.
 *
 * @return The id.
 *
 * @throws SessionRequiredException When the principal is not a user (an agent).
 */
fun AuthenticatedPrincipal.userId(): Uuid {
    if (kind != PrincipalKind.USER) throw SessionRequiredException()
    return parseId(subjectId) ?: throw SessionRequiredException()
}

private fun parseId(raw: String): Uuid? = Uuid.parseOrNull(raw)
