// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.routing.Route

/** The name of the token authentication provider. */
const val TOKEN_AUTH = "ostrom-token"

/**
 * Authenticates `Authorization: Bearer <token>` with a [TokenAuthenticator], and answers a missing or rejected
 * token with a 401 in the uniform error format.
 */
class TokenAuthenticationProvider internal constructor(config: Config) : AuthenticationProvider(config) {
    private val authenticator = config.authenticator

    /**
     * Configuration of the provider.
     *
     * @param name The provider name.
     * @param authenticator Resolves tokens.
     */
    class Config internal constructor(name: String?, val authenticator: TokenAuthenticator) :
        AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val token = call.bearerToken()
        val prefix = authenticator.sessionCookiePrefix
        val cookie = call.request.cookies[prefix + SESSION_COOKIE]?.takeIf { it.isNotEmpty() }
        // A bearer token wins over a cookie: a CLI or an API client never depends on a browser cookie.
        val session = if (token == null) cookie else null
        val principal = when {
            token != null -> authenticator.authenticate(token)
            session != null -> authenticator.authenticateSession(
                sessionToken = session,
                // Double submit (S2-08): the header counts only when it repeats the readable CSRF cookie.
                csrfToken = call.request.headers[CSRF_HEADER]
                    ?.takeIf { it == call.request.cookies[prefix + CSRF_COOKIE] },
                requireCsrf = call.request.httpMethod !in SAFE_METHODS,
                origin = call.request.headers[HttpHeaders.Origin],
            )
            else -> null
        }
        if (principal != null) {
            context.principal(principal)
            return
        }
        val cause = if (token == null && session == null) {
            AuthenticationFailedCause.NoCredentials
        } else {
            AuthenticationFailedCause.InvalidCredentials
        }
        context.challenge(TOKEN_AUTH, cause) { challenge, call ->
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respondError(ErrorKind.UNAUTHORIZED, "A valid bearer token or session is required.")
            challenge.complete()
        }
    }
}

/**
 * Registers the token provider under [TOKEN_AUTH].
 *
 * @param authenticator Resolves tokens.
 */
fun AuthenticationConfig.token(authenticator: TokenAuthenticator) {
    register(TokenAuthenticationProvider(TokenAuthenticationProvider.Config(TOKEN_AUTH, authenticator)))
}

/**
 * Routes that require a valid token.
 *
 * @param build The protected routes.
 */
fun Route.authenticated(build: Route.() -> Unit): Route = authenticate(TOKEN_AUTH, build = build)

/**
 * The principal of an authenticated route.
 *
 * @return The principal.
 *
 * @throws OstromException When called outside [authenticated] (a programming error, answered as a 401).
 */
fun ApplicationCall.requirePrincipal(): AuthenticatedPrincipal = principal<AuthenticatedPrincipal>()
    ?: throw OstromException(ErrorKind.UNAUTHORIZED, "A valid bearer token is required.")

/**
 * The principal of an authenticated route, refused unless it may do what [scope] names
 * ([AuthenticatedPrincipal.allows]): an agent token never passes a console route (audit A2).
 *
 * @param scope The scope the route requires, such as `orgs:write`.
 *
 * @return The principal.
 *
 * @throws OstromException A 403 when the credential lacks the scope.
 */
fun ApplicationCall.requireScope(scope: String): AuthenticatedPrincipal {
    val principal = requirePrincipal()
    if (!principal.allows(scope)) throw OstromException(ErrorKind.FORBIDDEN, "This credential does not allow $scope.")
    return principal
}

/** The methods that never change state, and so need no CSRF token. */
private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val scheme = header.substringBefore(' ')
    val token = header.substringAfter(' ', missingDelimiterValue = "").trim()
    return token.takeIf { scheme.equals("Bearer", ignoreCase = true) && it.isNotEmpty() }
}
