// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.CreateSignInLinkUseCase
import dev.ostrom.server.identity.domain.ExchangeSignInLinkUseCase
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import kotlinx.serialization.Serializable

/** The browser-session links of the API (audit S2-08). */
@Serializable
@Resource("/api/v1/auth")
class SessionLinkApi {
    /**
     * `POST /api/v1/auth/link`: exchanges a sign-in link's token (`opk_…`) for the session cookies, once.
     *
     * @property parent The auth resource.
     */
    @Serializable
    @Resource("link")
    class Exchange(val parent: SessionLinkApi = SessionLinkApi())

    /**
     * `POST /api/v1/auth/links`: the signed-in person (browser session) makes a sign-in link for another browser.
     *
     * @property parent The auth resource.
     */
    @Serializable
    @Resource("links")
    class Create(val parent: SessionLinkApi = SessionLinkApi())
}

/**
 * Body of `POST /api/v1/auth/link`.
 *
 * @property token The link's token (`opk_…`).
 */
@Serializable
data class ExchangeSignInLinkRequest(val token: String)

/**
 * A sign-in link just made.
 *
 * @property url The link to open (the token is in the fragment, which no server or log ever receives).
 * @property token The token alone, for a QR code.
 * @property expiresAt When it stops working (ISO 8601).
 */
@Serializable
data class SignInLinkResponse(val url: String, val token: String, val expiresAt: String)

/** A request without the console's `Origin` tried to open a browser session. */
class OriginNotAllowedException :
    OstromException(ErrorKind.FORBIDDEN, "This request does not come from the Ostrom console.")

/** Where the console goes after a sign-in link: the approval page it was made for. */
const val SIGN_IN_LINK_LANDING = "/yes"

/**
 * The paths whose answers no cache keeps (sign-in, the profile, tokens, service keys, devices): `Cache-Control:
 * no-store` (S2-08).
 */
private val NO_STORE_PATHS = listOf("/api/v1/auth", "/api/v1/tokens", "/api/v1/service-keys", "/api/v1/devices")

/**
 * The dependencies of the session routes.
 *
 * @property config Where the API and the console are served, and the allowed origins.
 * @property createSignInLink Makes a sign-in link.
 * @property exchangeSignInLink Exchanges one for a session.
 */
data class SessionRoutesDependencies(
    val config: IdentityConfig,
    val createSignInLink: CreateSignInLinkUseCase,
    val exchangeSignInLink: ExchangeSignInLinkUseCase,
)

/**
 * The browser-session routes: making and exchanging sign-in links. The exchange is not authenticated (it is how the
 * session starts), so it checks the `Origin` itself: another site cannot sign a visitor in to someone's account.
 *
 * @param dependencies The use cases.
 */
fun Route.sessionRoutes(dependencies: SessionRoutesDependencies) = with(dependencies) {
    post<SessionLinkApi.Exchange, ExchangeSignInLinkRequest> { _, request ->
        if (call.request.headers[HttpHeaders.Origin] !in config.allowedOrigins) throw OriginNotAllowedException()
        val started = exchangeSignInLink(request.token, call.request.origin.remoteAddress)
        call.setSessionCookie(started, config)
        call.respond(SignedInResponse(SIGN_IN_LINK_LANDING, started.csrfToken))
    }
    authenticated {
        post<SessionLinkApi.Create> {
            val principal = call.requireBrowserSession()
            val created = createSignInLink(principal.userId())
            val url = config.consoleUrl + SIGN_IN_LINK_LANDING + "#signin=" + created.token
            val expiresAt = created.link.expiresAt.toString()
            call.respond(HttpStatusCode.Created, SignInLinkResponse(url, created.token, expiresAt))
        }
    }
}

/** Marks every answer of the sign-in, profile and token routes `Cache-Control: no-store` (S2-08). */
private val NoStoreOnIdentityRoutes = createApplicationPlugin("OstromIdentityNoStore") {
    onCall { call ->
        val path = call.request.path()
        if (NO_STORE_PATHS.any { path.startsWith(it) }) call.response.header(HttpHeaders.CacheControl, "no-store")
    }
}

/**
 * Installs `Cache-Control: no-store` on the answers of the sign-in, profile and token routes (S2-08): no browser or
 * proxy cache keeps a profile, a CSRF token or a token secret.
 */
fun Route.noStoreOnIdentityRoutes() {
    application.install(NoStoreOnIdentityRoutes)
}
