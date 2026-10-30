package dev.ostrom.server.kernel.auth

/**
 * Who made an authenticated request. The identity module decides what a token maps to; other modules only read
 * the principal (`call.principal<AuthenticatedPrincipal>()`, or `call.requirePrincipal()`).
 *
 * @property subjectId The stable id of the user, device or service the token belongs to.
 * @property kind What the subject is.
 * @property scopes What the token allows, as decided by the identity module. Empty grants no scope: a route that
 *   requires a scope refuses it. There is no implicit "everything".
 */
data class AuthenticatedPrincipal(
    val subjectId: String,
    val kind: PrincipalKind,
    val scopes: Set<String>,
)

/** The kinds of subject a token can belong to. */
enum class PrincipalKind { USER, DEVICE, SERVICE }

/**
 * Turns a bearer token into a principal. The identity module binds its implementation in its Koin module; until
 * then [serverKoin][dev.ostrom.server.kernel.module.serverKoin] binds [RejectingTokenAuthenticator] (docs/MODULES.md).
 */
interface TokenAuthenticator {
    /**
     * Resolves a token.
     *
     * @param token The bearer token as sent by the client, without the `Bearer ` prefix.
     *
     * @return The principal, or null when the token is unknown, expired or revoked.
     */
    suspend fun authenticate(token: String): AuthenticatedPrincipal?
}

/** Rejects every token: the default until the identity module is registered, so no route is open by mistake. */
class RejectingTokenAuthenticator : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
}
