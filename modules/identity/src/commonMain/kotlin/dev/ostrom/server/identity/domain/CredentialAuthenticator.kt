package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The server's [TokenAuthenticator]: resolves API tokens (`Authorization: Bearer`) and browser sessions (cookie +
 * CSRF header). One indexed read per request; the last-use date is written at most every [SESSION_TOUCH_INTERVAL].
 *
 * - A personal or CLI token acts as its user, limited to its scopes.
 * - An agent token is an [PrincipalKind.AGENT] whose subject is its agent session, with the single [AGENT_SCOPE]:
 *   it never passes a console route (audit A2) and can only act on that session.
 * - A browser session acts as its user with all their rights, and needs the CSRF token on state-changing requests.
 * - A session secret is never accepted as a bearer token, nor a token as a cookie.
 *
 * @param tokens The API tokens.
 * @param sessions The browser sessions.
 * @param hasher Hashes secrets and derives CSRF tokens.
 * @param clock The clock.
 */
class CredentialAuthenticatorImpl(
    private val tokens: ApiTokensRepository,
    private val sessions: WebSessionsRepository,
    private val hasher: SecretHasher,
    private val clock: Clock,
) : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? {
        val kind = TokenKind.entries.firstOrNull { token.startsWith(it.prefix + "_") } ?: return null
        if (token.length > MAX_SECRET_LENGTH) return null
        val stored = tokens.findBySecretHash(hasher.hash(token)) ?: return null
        val now = clock.now()
        if (stored.kind != kind || stored.revokedAt != null || stored.expiresAt?.let { it <= now } == true) return null
        if (stale(stored.lastUsedAt, now)) tokens.touch(stored.id, clock.nowMillis())
        return when (stored.kind) {
            TokenKind.AGENT -> AuthenticatedPrincipal(
                subjectId = checkNotNull(stored.agentSessionId) { "An agent token has no agent session." },
                kind = PrincipalKind.AGENT,
                scopes = setOf(AGENT_SCOPE),
                credentialId = stored.id.toString(),
                orgId = stored.orgId?.toString(),
            )
            TokenKind.PERSONAL, TokenKind.CLI -> AuthenticatedPrincipal(
                subjectId = stored.userId.toString(),
                kind = PrincipalKind.USER,
                scopes = stored.scopes,
                credentialId = stored.id.toString(),
                orgId = stored.orgId?.toString(),
            )
        }
    }

    override suspend fun authenticateSession(
        sessionToken: String,
        csrfToken: String?,
        requireCsrf: Boolean,
    ): AuthenticatedPrincipal? {
        if (!sessionToken.startsWith(SESSION_PREFIX + "_") || sessionToken.length > MAX_SECRET_LENGTH) return null
        if (requireCsrf && (csrfToken == null || !constantTimeEquals(csrfToken, hasher.csrfToken(sessionToken)))) {
            return null
        }
        val session = sessions.findBySecretHash(hasher.hash(sessionToken)) ?: return null
        val now = clock.now()
        if (session.revokedAt != null || session.expiresAt <= now) return null
        if (stale(session.lastSeenAt, now)) sessions.touch(session.id, clock.nowMillis())
        return AuthenticatedPrincipal(
            subjectId = session.userId.toString(),
            kind = PrincipalKind.USER,
            credentialId = session.id.toString(),
        )
    }

    private fun stale(lastUse: Instant?, now: Instant) = lastUse == null || now - lastUse >= SESSION_TOUCH_INTERVAL

    private companion object {
        /** No secret we issue is longer: a longer value is refused before it is hashed. */
        const val MAX_SECRET_LENGTH = 128
    }
}

/**
 * Whether a principal is a user's browser session (the only credential with no scopes): account-level actions such as
 * creating tokens or approving a CLI login need one.
 */
val AuthenticatedPrincipal.isBrowserSession: Boolean get() = kind == PrincipalKind.USER && scopes.isEmpty()
