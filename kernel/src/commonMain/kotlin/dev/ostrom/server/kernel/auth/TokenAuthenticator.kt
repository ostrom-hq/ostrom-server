// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

import kotlin.time.Instant

/**
 * Who made an authenticated request. The identity module decides what a token maps to; other modules only read
 * the principal (`call.principal<AuthenticatedPrincipal>()`, or `call.requirePrincipal()`).
 *
 * @property subjectId The stable id of the user, device or service the token belongs to.
 * @property kind What the subject is.
 * @property scopes What the token allows, as decided by the identity module. Empty grants no scope: a route that
 *   requires a scope refuses it. There is no implicit "everything".
 * @property credentialId The token or browser session it presented.
 * @property orgId The organization an agent token is confined to.
 * @property channel Where the credential is held, as the identity module knows it: an API token unless it says
 *   otherwise (SECURITY-AUDIT S2-31, a yes is only given in the browser or the unlocked app).
 * @property authenticatedAt When the person last proved who they are, as the identity module knows it: the sign-in of
 *   a browser session (OAuth `max_age`/`prompt=login`, a fresh e-mail link), the app's biometric unlock; null when
 *   unknown. A yes to a high-risk action needs it within five minutes (S2-54).
 */
data class AuthenticatedPrincipal(
    val subjectId: String,
    val kind: PrincipalKind,
    val scopes: Set<String>,
    val credentialId: String? = null,
    val orgId: String? = null,
    val channel: PrincipalChannel = PrincipalChannel.API_TOKEN,
    val authenticatedAt: Instant? = null,
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

/** Where a credential is held: what a person did to present it, which no agent can do in their place. */
enum class PrincipalChannel {
    /** The session cookie of a person signed in to the console. */
    BROWSER,

    /** The token of the mobile app, presented only after the phone's biometric unlock. */
    MOBILE_APP,

    /** A token any program can present: the CLI's, a personal or a service token. */
    API_TOKEN,
}

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
     * Resolves a browser session cookie. A request that changes state must also carry the session's CSRF token
     * in the `X-CSRF-Token` header ([CSRF_HEADER]); the authenticator checks it against the session.
     *
     * @param sessionToken The value of the [SESSION_COOKIE] cookie.
     * @param csrfToken The value of the [CSRF_HEADER] header, if any.
     * @param requireCsrf True for a method that changes state (anything but GET, HEAD and OPTIONS).
     *
     * @return The principal, or null when the session is unknown, expired, revoked or the CSRF token is wrong.
     */
    suspend fun authenticateSession(
        sessionToken: String,
        csrfToken: String?,
        requireCsrf: Boolean,
    ): AuthenticatedPrincipal? = null
}

/** The cookie that carries a browser session (HttpOnly, Secure, SameSite=Lax; set by the identity module). */
const val SESSION_COOKIE = "ostrom_session"

/** The header that carries the CSRF token of a browser session on state-changing requests. */
const val CSRF_HEADER = "X-CSRF-Token"

/** Rejects every token: the default until the identity module is registered, so no route is open by mistake. */
class RejectingTokenAuthenticator : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
}
