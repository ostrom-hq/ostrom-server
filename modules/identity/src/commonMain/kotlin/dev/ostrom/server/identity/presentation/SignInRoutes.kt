package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.CompleteOAuthSignInUseCase
import dev.ostrom.server.identity.domain.InvalidSignInStateException
import dev.ostrom.server.identity.domain.OAUTH_STATE_LIFETIME
import dev.ostrom.server.identity.domain.ProviderNotConfiguredException
import dev.ostrom.server.identity.domain.ProviderRejectedException
import dev.ostrom.server.identity.domain.RateLimitedException
import dev.ostrom.server.identity.domain.RequestMagicLinkUseCase
import dev.ostrom.server.identity.domain.SignInMethods
import dev.ostrom.server.identity.domain.StartOAuthSignInUseCase
import dev.ostrom.server.identity.domain.VerifyMagicLinkUseCase
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route

/** The cookie that binds an OAuth sign-in to the browser that started it. */
const val OAUTH_STATE_COOKIE = "ostrom_oauth_state"

private const val SIGN_IN_COOKIE_PATH = "/v1/auth"

/**
 * What the sign-in routes need.
 *
 * @property config The public URLs and cookie settings.
 * @property methods The offered methods.
 * @property startOAuth Starts a provider sign-in.
 * @property completeOAuth Completes it.
 * @property requestMagicLink E-mails a sign-in link.
 * @property verifyMagicLink Signs in with it.
 */
data class SignInRoutesDependencies(
    val config: IdentityConfig,
    val methods: SignInMethods,
    val startOAuth: StartOAuthSignInUseCase,
    val completeOAuth: CompleteOAuthSignInUseCase,
    val requestMagicLink: RequestMagicLinkUseCase,
    val verifyMagicLink: VerifyMagicLinkUseCase,
)

/**
 * The sign-in routes. GitHub and Google are browser navigations: their failures send the browser back to the
 * console's login page with an `error` code (`unavailable`, `rate_limited`, `access_denied`, `expired`, `refused`)
 * instead of a JSON error.
 *
 * @param dependencies The use cases.
 */
fun Route.signInRoutes(dependencies: SignInRoutesDependencies) = with(dependencies) {
    get<SignInApi.Providers> {
        call.respond(ProvidersResponse(methods.keys))
    }
    get<SignInApi.Provider.Start> { params ->
        val started = try {
            startOAuth(params.parent.provider, params.redirect, call.request.origin.remoteAddress)
        } catch (error: OstromException) {
            return@get call.redirectToLogin(config, error)
        }
        call.response.cookies.append(stateCookie(started.state, OAUTH_STATE_LIFETIME.inWholeSeconds.toInt(), config))
        call.respondRedirect(started.authorizationUrl)
    }
    get<SignInApi.Provider.Callback> { params ->
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
    post<SignInApi.Email, MagicLinkRequest> { _, request ->
        requestMagicLink(request.email, request.redirect, call.request.origin.remoteAddress)
        call.respond(HttpStatusCode.Accepted)
    }
    post<SignInApi.Email.Verify, VerifyMagicLinkRequest> { _, request ->
        val signedIn = verifyMagicLink(request.token)
        call.setSessionCookie(signedIn.session, config)
        call.respond(SignedInResponse(signedIn.redirectPath, signedIn.session.csrfToken))
    }
}

private fun stateCookie(value: String, maxAge: Int, config: IdentityConfig) = Cookie(
    name = OAUTH_STATE_COOKIE,
    value = value,
    maxAge = maxAge,
    path = SIGN_IN_COOKIE_PATH,
    secure = config.secureCookies,
    httpOnly = true,
    // Lax: the provider sends the browser back by a top-level GET, which carries it.
    extensions = mapOf("SameSite" to "Lax"),
)

private suspend fun ApplicationCall.redirectToLogin(config: IdentityConfig, error: OstromException) {
    val code = when (error) {
        is ProviderNotConfiguredException -> "unavailable"
        is RateLimitedException -> "rate_limited"
        is InvalidSignInStateException -> "expired"
        is ProviderRejectedException -> "refused"
        else -> throw error
    }
    redirectToLogin(config, code)
}

private suspend fun ApplicationCall.redirectToLogin(config: IdentityConfig, code: String) {
    respondRedirect(URLBuilder(config.consoleUrl + "/login").apply { parameters.append("error", code) }.buildString())
}
