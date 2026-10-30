// SPDX-License-Identifier: AGPL-3.0-only
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
    val credentialId: String? = null,
    val orgId: String? = null,
) {
    /**
     * Whether the principal may do what [scope] names: the scope is listed, or the principal is a user's browser
     * session ([ALL_USER_SCOPES]), which may do everything its user may. An agent never gets a user's rights (audit
     * A2): it only passes a scope its token lists explicitly.
     *
     * @param scope The scope a route requires, such as `orgs:write`.
     *
     * @return True when allowed.
     */
    fun allows(scope: String): Boolean = scope in scopes || (kind == PrincipalKind.USER && ALL_USER_SCOPES in scopes)
}

/**
 * The scope of a user's browser session: every right of the user. Only the identity module grants it, only to a
 * session cookie; a token can never hold it (token scopes are `resource:action`).
 */
const val ALL_USER_SCOPES = "user:*"

/**
 * The kinds of subject a token can belong to.
 *
 * `USER` is a person (web session, personal or CLI token); `AGENT` is the minimal-scope token of one agent session;
 * `DEVICE` and `SERVICE` are machines.
 */
enum class PrincipalKind { USER, AGENT, DEVICE, SERVICE }

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

    /**
     * The prefix of the browser session's cookie names: [HOST_COOKIE_PREFIX] when the API is served over HTTPS (audit
     * S2-12), so that no sibling subdomain can set or shadow them; empty on a plain-HTTP development server, where a
     * browser refuses a `__Host-` cookie. The kernel reads only the names with this prefix, never the others.
     */
    val sessionCookiePrefix: String get() = ""

    /**
     * Resolves a browser session cookie. A request that changes state must also carry the session's CSRF token
     * in the `X-CSRF-Token` header ([CSRF_HEADER]), equal to the readable [CSRF_COOKIE] (the kernel passes it only
     * then), and come from an allowed `Origin`; the authenticator checks the token against the session and the origin
     * against its list (audit S2-08).
     *
     * @param sessionToken The value of the [SESSION_COOKIE] cookie.
     * @param csrfToken The value of the [CSRF_HEADER] header when it repeats the [CSRF_COOKIE] cookie, else null.
     * @param requireCsrf True for a method that changes state (anything but GET, HEAD and OPTIONS).
     * @param origin The request's `Origin` header, if any.
     *
     * @return The principal, or null when the session is unknown, expired, revoked, the CSRF token is wrong or the
     *   origin is not allowed.
     */
    suspend fun authenticateSession(
        sessionToken: String,
        csrfToken: String?,
        requireCsrf: Boolean,
        origin: String? = null,
    ): AuthenticatedPrincipal? = null
}

/** The cookie that carries a browser session (HttpOnly, Secure, SameSite=Lax; set by the identity module). */
const val SESSION_COOKIE = "ostrom_session"

/**
 * The cookie-name prefix that binds a cookie to the exact host that set it: the browser keeps it only when it is
 * Secure, has `Path=/` and no `Domain` (audit S2-12).
 */
const val HOST_COOKIE_PREFIX = "__Host-"

/** The header that carries the CSRF token of a browser session on state-changing requests. */
const val CSRF_HEADER = "X-CSRF-Token"

/**
 * The readable cookie (not HttpOnly) that carries the CSRF token of a browser session, set with [SESSION_COOKIE]:
 * the console copies it into [CSRF_HEADER]; another site can neither read it nor set the header (double submit).
 */
const val CSRF_COOKIE = "ostrom_csrf"

/** Rejects every token: the default until the identity module is registered, so no route is open by mistake. */
class RejectingTokenAuthenticator : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
}
