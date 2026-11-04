// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.APP_REDIRECT_URI
import dev.ostrom.server.identity.domain.AccountLinkRequiredException
import dev.ostrom.server.identity.domain.CompleteMobileSignInUseCase
import dev.ostrom.server.identity.domain.CompleteOAuthSignInUseCase
import dev.ostrom.server.identity.domain.IdentityTakenException
import dev.ostrom.server.identity.domain.InvalidSignInStateException
import dev.ostrom.server.identity.domain.LauncherLoginUseCase
import dev.ostrom.server.identity.domain.MAGIC_LINK_LIFETIME
import dev.ostrom.server.identity.domain.MobileCallback
import dev.ostrom.server.identity.domain.OAUTH_STATE_LIFETIME
import dev.ostrom.server.identity.domain.ProviderNotConfiguredException
import dev.ostrom.server.identity.domain.ProviderRejectedException
import dev.ostrom.server.identity.domain.RateLimitedException
import dev.ostrom.server.identity.domain.RequestMagicLinkUseCase
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.StartOAuthSignInUseCase
import dev.ostrom.server.identity.domain.VerifyMagicLinkUseCase
import dev.ostrom.server.identity.domain.constantTimeEquals
import dev.ostrom.server.identity.domain.mobileClientOf
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.formUrlEncode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route

/** The cookie that binds an OAuth sign-in to the browser that started it. */
const val OAUTH_STATE_COOKIE = "ostrom_oauth_state"

/**
 * The cookie that binds a sign-in the phone app started to its in-app browser: the callback that matches it returns a
 * one-time code to the app instead of opening a browser session.
 */
const val MOBILE_OAUTH_STATE_COOKIE = "ostrom_mobile_oauth_state"

/**
 * The cookie that binds an e-mailed sign-in link to the browser that asked for it (web audit n°2 M4), as the OAuth
 * state does: the link is used only where this nonce is presented.
 */
const val EMAIL_LINK_COOKIE = "ostrom_email_link"

private const val SIGN_IN_COOKIE_PATH = "/api/v1/auth"

/**
 * What the sign-in routes need.
 *
 * @property config The public URLs and cookie settings.
 * @property methods The offered methods.
 * @property startOAuth Starts a provider sign-in.
 * @property completeOAuth Completes it.
 * @property requestMagicLink E-mails a sign-in link.
 * @property verifyMagicLink Signs in with it.
 * @property launcherLogin Exchanges a GitHub token of Ostrom's app for a CLI token.
 * @property completeMobile Completes a sign-in the phone app started.
 */
data class SignInRoutesDependencies(
    val config: IdentityConfig,
    val methods: SignInMethods,
    val startOAuth: StartOAuthSignInUseCase,
    val completeOAuth: CompleteOAuthSignInUseCase,
    val requestMagicLink: RequestMagicLinkUseCase,
    val verifyMagicLink: VerifyMagicLinkUseCase,
    val launcherLogin: LauncherLoginUseCase,
    val completeMobile: CompleteMobileSignInUseCase,
)

/**
 * The sign-in routes. GitHub and Google are browser navigations: their failures send the browser back to the
 * console's login page with an `error` code (`unavailable`, `rate_limited`, `access_denied`, `expired`, `refused`,
 * `link_required` when the provider names the address of another account, `identity_taken` when a link meets an
 * identity of another account) instead of a JSON error.
 *
 * @param dependencies The use cases.
 */
fun Route.signInRoutes(dependencies: SignInRoutesDependencies) = with(dependencies) {
    get<SignInApi.Providers> {
        call.respond(ProvidersResponse(methods.keys))
    }
    get<SignInApi.Provider.Start> { params ->
        // Invalid mobile parameters answer 400 here: nothing is redirected to a deep link that is not the app's.
        val mobile = mobileClientOf(params.redirectUri, params.state, params.codeChallenge, params.codeChallengeMethod)
        val started = try {
            val source = call.request.origin.remoteAddress
            val provider = params.parent.provider
            if (mobile != null) {
                startOAuth.mobile(provider, source, mobile)
            } else {
                startOAuth(provider, params.redirect, source, reauthenticate = params.reauth)
            }
        } catch (error: OstromException) {
            return@get call.redirectToLogin(config, error)
        }
        val cookie = if (mobile == null) OAUTH_STATE_COOKIE else MOBILE_OAUTH_STATE_COOKIE
        val lifetime = OAUTH_STATE_LIFETIME.inWholeSeconds.toInt()
        call.response.cookies.append(stateCookie(started.state, lifetime, config, cookie))
        call.respondRedirect(started.authorizationUrl)
    }
    get<SignInApi.Provider.Callback> { params ->
        if (call.answeredTheApp(params, completeMobile, config)) return@get
        // The state cookie served its purpose whatever happens next.
        call.response.cookies.append(stateCookie("", 0, config))
        if (params.error != null) return@get call.redirectToLogin(config, "access_denied")
        val signedIn = try {
            completeOAuth(params.parent.provider, params.state, call.request.cookies[OAUTH_STATE_COOKIE], params.code)
        } catch (error: OstromException) {
            return@get call.redirectToLogin(config, error)
        }
        call.setSessionCookie(signedIn.session, config)
        call.respondRedirect(config.consoleUrl + signedIn.redirectPath)
    }
    emailRoutes(dependencies)
    post<LauncherApi.Login, LauncherLoginRequest> { _, request ->
        val login = launcherLogin(request.githubToken, request.via, call.request.origin.remoteAddress)
        val token = login.token
        call.respond(LauncherLoginResponse(login.user.login, token.secret, token.token.expiresAt.toString()))
    }
    authenticated {
        post<ProviderLinkApi> { params ->
            // A browser session only (and its CSRF token): a token on the developer's machine never adds a way in.
            val principal = call.requireBrowserSession()
            val source = call.request.origin.remoteAddress
            val started = startOAuth(params.parent.provider, params.redirect, source, principal.userId())
            val lifetime = OAUTH_STATE_LIFETIME.inWholeSeconds.toInt()
            call.response.cookies.append(stateCookie(started.state, lifetime, config))
            call.respond(LinkStartedResponse(started.authorizationUrl))
        }
    }
}


/** The e-mailed link: asked for by a browser, which keeps a nonce (a cookie) that the link needs to sign in (M4). */
private fun Route.emailRoutes(dependencies: SignInRoutesDependencies) = with(dependencies) {
    post<SignInApi.Email, MagicLinkRequest> { _, request ->
        val source = call.request.origin.remoteAddress
        val nonce = requestMagicLink(request.email, request.redirect, source, call.request.cookies[EMAIL_LINK_COOKIE])
        val lifetime = MAGIC_LINK_LIFETIME.inWholeSeconds.toInt()
        call.response.cookies.append(stateCookie(nonce, lifetime, config, EMAIL_LINK_COOKIE))
        call.respond(HttpStatusCode.Accepted)
    }
    post<SignInApi.Email.Verify, VerifyMagicLinkRequest> { _, request ->
        val signedIn = verifyMagicLink(request.token, call.request.cookies[EMAIL_LINK_COOKIE])
        // Signed in: the browser's nonce served its purpose.
        call.response.cookies.append(stateCookie("", 0, config, EMAIL_LINK_COOKIE))
        call.setSessionCookie(signedIn.session, config)
        call.respond(SignedInResponse(signedIn.redirectPath, signedIn.session.csrfToken))
    }
}

/**
 * Ends a sign-in the phone app started, when the provider's state is the one of the mobile cookie: an error goes
 * straight to the app's deep link; a code waits on a page the person taps to go back to the app ([confirmPage]). When
 * the attempt is unknown (expired, used), the error goes to [APP_REDIRECT_URI] without a state: the only deep link
 * there is, so the in-app browser always closes.
 *
 * @param params The callback's parameters.
 * @param completeMobile Completes the app's sign-in.
 * @param config The console URL and the cookie settings.
 *
 * @return Whether the callback was the app's, and is answered.
 */
private suspend fun ApplicationCall.answeredTheApp(
    params: SignInApi.Provider.Callback,
    completeMobile: CompleteMobileSignInUseCase,
    config: IdentityConfig,
): Boolean {
    val mobileState = request.cookies[MOBILE_OAUTH_STATE_COOKIE]
    val state = params.state
    val theApps = mobileState != null && state != null && constantTimeEquals(state, mobileState)
    if (theApps) {
        response.cookies.append(stateCookie("", 0, config, MOBILE_OAUTH_STATE_COOKIE))
        try {
            val back = completeMobile(params.parent.provider, state, mobileState, params.code, params.error)
            if (back.code == null) respondRedirect(back.toUrl()) else respondConfirmPage(back)
        } catch (error: OstromException) {
            respondRedirect(APP_REDIRECT_URI + "?" + listOf("error" to signInError(error)).formUrlEncode())
        }
    }
    return theApps
}

/**
 * Answers the page that asks before a code goes back to the app ([confirmPage]): never framed, cached or referred.
 *
 * @param back The code and where it goes.
 */
private suspend fun ApplicationCall.respondConfirmPage(back: MobileCallback) {
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    )
    response.header("Referrer-Policy", "no-referrer")
    // The page carries a one-time code: never kept by a cache.
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(back.confirmPage(), ContentType.Text.Html)
}

private fun stateCookie(value: String, maxAge: Int, config: IdentityConfig, name: String = OAUTH_STATE_COOKIE) = Cookie(
    name = name,
    value = value,
    maxAge = maxAge,
    path = SIGN_IN_COOKIE_PATH,
    secure = config.secureCookies,
    httpOnly = true,
    // Lax: the provider sends the browser back by a top-level GET, which carries it; the console's own calls carry it.
    extensions = mapOf("SameSite" to "Lax"),
)

private suspend fun ApplicationCall.redirectToLogin(config: IdentityConfig, error: OstromException) =
    redirectToLogin(config, signInError(error))

/**
 * The `error` a failed sign-in shows, on the console's login page or in the app.
 *
 * @param error The refusal.
 *
 * @return Its stable key.
 */
private fun signInError(error: OstromException): String = when (error) {
    is ProviderNotConfiguredException -> "unavailable"
    is RateLimitedException -> "rate_limited"
    is InvalidSignInStateException -> "expired"
    is ProviderRejectedException -> "refused"
    is AccountLinkRequiredException -> "link_required"
    is IdentityTakenException -> "identity_taken"
    else -> throw error
}

private suspend fun ApplicationCall.redirectToLogin(config: IdentityConfig, code: String) {
    respondRedirect(URLBuilder(config.consoleUrl + "/login").apply { parameters.append("error", code) }.buildString())
}
