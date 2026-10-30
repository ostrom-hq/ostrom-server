// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.respondError
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
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
        val token = context.call.bearerToken()
        val principal = token?.let { authenticator.authenticate(it) }
        if (principal != null) {
            context.principal(principal)
            return
        }
        val cause = if (token == null) {
            AuthenticationFailedCause.NoCredentials
        } else {
            AuthenticationFailedCause.InvalidCredentials
        }
        context.challenge(TOKEN_AUTH, cause) { challenge, call ->
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respondError(ErrorKind.UNAUTHORIZED, "A valid bearer token is required.")
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

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val scheme = header.substringBefore(' ')
    val token = header.substringAfter(' ', missingDelimiterValue = "").trim()
    return token.takeIf { scheme.equals("Bearer", ignoreCase = true) && it.isNotEmpty() }
}
