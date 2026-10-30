// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.DEVICE_POLL_INTERVAL
import dev.ostrom.server.identity.domain.SessionRequiredException
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.TokenNotFoundException
import dev.ostrom.server.identity.domain.isBrowserSession
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
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
        val cookie = call.request.cookies[config.sessionCookiePrefix + SESSION_COOKIE]
        val session = cookie?.takeIf { principal.isBrowserSession }
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
        val credential = principal.credentialUuid()
        if (principal.isBrowserSession) {
            endWebSession(credential)
            call.clearSessionCookie(config)
        } else {
            revokeToken(userId, credential)
        }
        // After the revocation: a listener that fails cannot keep the credential alive (S2-08, APP 06:10).
        signOutListeners.forEach { it.signedOut(principal.subjectId, principal.credentialId) }
        call.respond(HttpStatusCode.NoContent)
    }
    post<AuthApi.Logout.Everywhere> {
        val principal = call.requireBrowserSession()
        endWebSession.everywhere(principal.userId())
        call.clearSessionCookie(config)
        signOutListeners.forEach { it.signedOut(principal.subjectId, null) }
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
        revokeToken(principal.userId(), Uuid.parseOrNull(params.id) ?: throw TokenNotFoundException())
        call.respond(HttpStatusCode.NoContent)
    }
    post<TokensApi.Id.Rotate> { params ->
        val principal = call.requireBrowserSession()
        val parent = Uuid.parseOrNull(params.parent.id) ?: throw TokenNotFoundException()
        val issued = rotateToken(principal.userId(), parent)
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
 * Sets the session cookies after a sign-in (audit S2-08): [SESSION_COOKIE], HttpOnly (no script reads it), and
 * [CSRF_COOKIE], readable by the console's script, which copies it into the `X-CSRF-Token` header of every request
 * that changes state. Both are Secure when the API is served over HTTPS, SameSite=Lax (sent on the console's own
 * requests and top-level navigations, not on another site's forms), for the whole API, until the session expires. The
 * session's secret is never in a response body. Over HTTPS both names carry the `__Host-` prefix (S2-12).
 *
 * @param started The session.
 * @param config Where the API is served.
 */
fun ApplicationCall.setSessionCookie(started: StartedSession, config: IdentityConfig) {
    sessionCookies(started, config).forEach(response.cookies::append)
}

/**
 * Expires both session cookies (sign-out).
 *
 * @param config Where the API is served.
 */
fun ApplicationCall.clearSessionCookie(config: IdentityConfig) {
    sessionCookies(null, config).forEach(response.cookies::append)
}

/**
 * The two session cookies, named `__Host-ostrom_session` and `__Host-ostrom_csrf` over HTTPS (Secure, `Path=/`, no
 * `Domain`: audit S2-12), without the prefix on a plain-HTTP development server.
 *
 * @param started The session, or null to expire both.
 * @param config Where the API is served.
 *
 * @return The session cookie, then the CSRF cookie.
 */
internal fun sessionCookies(started: StartedSession?, config: IdentityConfig): List<Cookie> {
    val prefix = config.sessionCookiePrefix
    val maxAge = started?.let { (it.session.expiresAt - it.session.createdAt).inWholeSeconds.toInt() } ?: 0
    return listOf(
        sessionCookie(prefix + SESSION_COOKIE, started?.secret.orEmpty(), maxAge, config, httpOnly = true),
        sessionCookie(prefix + CSRF_COOKIE, started?.csrfToken.orEmpty(), maxAge, config, httpOnly = false),
    )
}

private fun sessionCookie(name: String, value: String, maxAge: Int, config: IdentityConfig, httpOnly: Boolean) = Cookie(
    name = name,
    value = value,
    maxAge = maxAge,
    path = "/",
    secure = config.secureCookies,
    httpOnly = httpOnly,
    extensions = mapOf("SameSite" to "Lax"),
)

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
