package dev.ostrom.server.kernel.auth

/**
 * Who made an authenticated request. The identity module decides what a token maps to; other modules only read
 * the principal (`call.principal<AuthenticatedPrincipal>()`, or `call.requirePrincipal()`).
 *
 * @property subjectId The stable id of the user, device or service the token belongs to.
 * @property kind What the subject is.
 * @property scopes What the token allows; empty means the subject's full rights.
 */
data class AuthenticatedPrincipal(
    val subjectId: String,
    val kind: PrincipalKind,
    val scopes: Set<String> = emptySet(),
    val credentialId: String? = null,
    val orgId: String? = null,
) {
    /**
     * Whether the principal may do what [scope] names. A user's web session (no scopes) may do everything its user
     * may; any other credential only what its scopes list. An agent never gets a user's rights (audit A2): it only
     * passes a scope its token lists explicitly.
     *
     * @param scope The scope a route requires, such as `orgs:write`.
     *
     * @return True when allowed.
     */
    fun allows(scope: String): Boolean = when (kind) {
        PrincipalKind.USER -> scopes.isEmpty() || scope in scopes
        else -> scope in scopes
    }
}

/**
 * The kinds of subject a token can belong to.
 *
 * `USER` is a person (web session, personal or CLI token); `AGENT` is the minimal-scope token of one agent session;
 * `DEVICE` and `SERVICE` are machines.
 */
enum class PrincipalKind { USER, AGENT, DEVICE, SERVICE }

/**
 * Turns a bearer token into a principal. The kernel binds [RejectingTokenAuthenticator]; the identity module
 * replaces it with its own implementation in its Koin module (docs/MODULES.md).
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

    /**
     * Resolves a browser session cookie. A request that changes state must also carry the session's CSRF token
     * in the `X-CSRF-Token` header ([CSRF_HEADER]); the authenticator checks it against the session.
     *
     * @param sessionToken The value of the [SESSION_COOKIE] cookie.
     * @param csrfToken The value of the [CSRF_HEADER] header, if any.
     * @param requireCsrf True for a method that changes state (anything but GET, HEAD and OPTIONS).
     *
     * @return The principal, or null when the session is unknown, expired, revoked or the CSRF token is wrong.
     */
    suspend fun authenticateSession(sessionToken: String, csrfToken: String?, requireCsrf: Boolean): AuthenticatedPrincipal? =
        null
}

/** The cookie that carries a browser session (HttpOnly, Secure, SameSite=Lax; set by the identity module). */
const val SESSION_COOKIE = "ostrom_session"

/** The header that carries the CSRF token of a browser session on state-changing requests. */
const val CSRF_HEADER = "X-CSRF-Token"

/** Rejects every token: the default until the identity module is registered, so no route is open by mistake. */
class RejectingTokenAuthenticator : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
}
