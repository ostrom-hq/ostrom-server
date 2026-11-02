// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.auth.ALL_USER_SCOPES
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The server's [TokenAuthenticator]: resolves API tokens (`Authorization: Bearer`) and browser sessions (cookie +
 * CSRF header). One indexed read per request; the last-use date is written at most every [SESSION_TOUCH_INTERVAL].
 *
 * - A personal or CLI token acts as its user, limited to its scopes, and never to more than [grantedScopes] allows
 *   for its kind: whatever its row says, a token never answers a gate (S2-04, S2-31).
 * - The phone app's token acts as its user on the [PrincipalChannel.MOBILE_APP] channel, limited to [MOBILE_SCOPES],
 *   and answers a gate only within [MOBILE_UNLOCK_WINDOW] of the phone's last biometric unlock ([mobileScopes]);
 *   its `authenticatedAt` is that unlock, never its issuance (S2-54).
 * - A service key is a [PrincipalKind.SERVICE] whose subject is the key, bound to its organization: it never acts as
 *   the person who created it, and no organization resolves it as a member.
 * - An agent token is an [PrincipalKind.AGENT] whose subject is its agent session, with the single [AGENT_SCOPE]:
 *   it never passes a console route (audit A2) and can only act on that session.
 * - A browser session acts as its user with all their rights, and needs the CSRF token and an allowed `Origin` on
 *   state-changing requests (S2-08).
 * - A session secret is never accepted as a bearer token, nor a token as a cookie.
 *
 * @param tokens The API tokens.
 * @param sessions The browser sessions.
 * @param hasher Hashes secrets and derives CSRF tokens.
 * @param clock The clock.
 * @param allowedOrigins The origins a session may change state from (`IdentityConfig.allowedOrigins`).
 * @property sessionCookiePrefix `__Host-` over HTTPS (`IdentityConfig.sessionCookiePrefix`, audit S2-12).
 */
class CredentialAuthenticatorImpl(
    private val tokens: ApiTokensRepository,
    private val sessions: WebSessionsRepository,
    private val hasher: SecretHasher,
    private val clock: Clock,
    private val allowedOrigins: Set<String>,
    override val sessionCookiePrefix: String,
) : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? {
        val stored = liveToken(token) ?: return null
        if (stale(stored.lastUsedAt, clock.now())) tokens.touch(stored.id, clock.nowMillis())
        return when (stored.kind) {
            TokenKind.AGENT -> AuthenticatedPrincipal(
                subjectId = checkNotNull(stored.agentSessionId) { "An agent token has no agent session." },
                kind = PrincipalKind.AGENT,
                scopes = grantedScopes(stored.kind, stored.scopes),
                credentialId = stored.id.toString(),
                orgId = stored.orgId?.toString(),
            )
            // The app's token: held in the phone's keychain; a yes needs its last biometric unlock (S2-31, S2-54).
            TokenKind.MOBILE -> AuthenticatedPrincipal(
                subjectId = stored.userId.toString(),
                kind = PrincipalKind.USER,
                scopes = mobileScopes(stored, clock.now()),
                credentialId = stored.id.toString(),
                channel = PrincipalChannel.MOBILE_APP,
                authenticatedAt = stored.authenticatedAt,
            )
            // An organization's service key: the key itself is the subject, never its creator.
            TokenKind.SERVICE -> AuthenticatedPrincipal(
                subjectId = stored.id.toString(),
                kind = PrincipalKind.SERVICE,
                scopes = grantedScopes(stored.kind, stored.scopes),
                credentialId = stored.id.toString(),
                orgId = stored.orgId?.toString(),
            )
            TokenKind.PERSONAL, TokenKind.CLI -> AuthenticatedPrincipal(
                subjectId = stored.userId.toString(),
                kind = PrincipalKind.USER,
                scopes = grantedScopes(stored.kind, stored.scopes),
                credentialId = stored.id.toString(),
                orgId = stored.orgId?.toString(),
            )
        }
    }

    override suspend fun authenticateSession(
        sessionToken: String,
        csrfToken: String?,
        requireCsrf: Boolean,
        origin: String?,
    ): AuthenticatedPrincipal? {
        val session = liveSession(sessionToken, csrfToken, requireCsrf, origin) ?: return null
        if (stale(session.lastSeenAt, clock.now())) sessions.touch(session.id, clock.nowMillis())
        return AuthenticatedPrincipal(
            subjectId = session.userId.toString(),
            kind = PrincipalKind.USER,
            scopes = setOf(ALL_USER_SCOPES),
            credentialId = session.id.toString(),
            // A session is only started by a sign-in (OAuth, e-mail link, SSO): its start is when the person proved
            // who they are. Signing in again starts a new one, which a yes to a high-risk action asks for (S2-54).
            channel = PrincipalChannel.BROWSER,
            authenticatedAt = session.createdAt,
        )
    }

    /** The live token a bearer secret names: its prefix says its kind, and the stored token must agree. */
    private suspend fun liveToken(token: String): ApiToken? {
        val kind = TokenKind.entries.firstOrNull { token.startsWith(it.prefix + "_") }
        if (kind == null || token.length > MAX_SECRET_LENGTH) return null
        val now = clock.now()
        return tokens.findBySecretHash(hasher.hash(token))
            ?.takeIf { it.kind == kind && it.revokedAt == null && it.expiresAt?.let { end -> end <= now } != true }
    }

    /**
     * The live session a cookie names; a state-changing request must also carry the session's CSRF token and come from
     * an allowed origin (S2-08).
     */
    private suspend fun liveSession(
        sessionToken: String,
        csrfToken: String?,
        requireCsrf: Boolean,
        origin: String?,
    ): WebSession? {
        val wellFormed = sessionToken.startsWith(SESSION_PREFIX + "_") && sessionToken.length <= MAX_SECRET_LENGTH
        if (!wellFormed || requireCsrf && !csrfHolds(sessionToken, csrfToken, origin)) return null
        val now = clock.now()
        return sessions.findBySecretHash(hasher.hash(sessionToken))
            ?.takeIf { it.revokedAt == null && it.expiresAt > now }
    }

    /** The kernel passes the CSRF token only when the header repeats the readable cookie (double submit). */
    private suspend fun csrfHolds(sessionToken: String, csrfToken: String?, origin: String?): Boolean =
        origin in allowedOrigins && csrfToken != null && constantTimeEquals(csrfToken, hasher.csrfToken(sessionToken))

    private fun stale(lastUse: Instant?, now: Instant) = lastUse == null || now - lastUse >= SESSION_TOUCH_INTERVAL

    private companion object {
        /** No secret we issue is longer: a longer value is refused before it is hashed. */
        const val MAX_SECRET_LENGTH = 128
    }
}

/**
 * Whether a principal is a user's browser session (the only credential with [ALL_USER_SCOPES]): account-level actions
 * such as creating tokens or approving a CLI login need one.
 */
val AuthenticatedPrincipal.isBrowserSession: Boolean get() = kind == PrincipalKind.USER && ALL_USER_SCOPES in scopes
