// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The name of a browser session, which has no device (TeamCode's "Web dashboard"). */
const val WEB_SESSION_NAME: String = "Web console"

/**
 * The kinds of token a person signs in with, listed and ended with their browser sessions as TeamCode's sessions are:
 * the CLI's (`ostrom login`) and the phone app's. A personal token, a service key, an agent's token and a token of
 * `ostrom login --admin` are not a sign-in.
 */
private val SIGN_IN_TOKEN_KINDS: Set<TokenKind> = setOf(TokenKind.CLI, TokenKind.MOBILE)

/**
 * One of a person's sign-ins (TeamCode's `MemberSession`): a browser session, or a token the CLI or the phone app
 * signed in with.
 *
 * @property id The browser session's id, or the token's.
 * @property name The device's name for a token of a registered device (the CLI's, `devices.v1`), the token's name for
 *   any other token, [WEB_SESSION_NAME] for a browser session.
 * @property deviceId The registered device a token belongs to; null for a browser session and a token of none.
 * @property createdAt When it started.
 * @property lastSeenAt When it was last used (precise to five minutes); its start when it never was.
 */
data class AuthSession(
    val id: Uuid,
    val name: String,
    val deviceId: Uuid?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

/**
 * A person's own sign-ins, as the console's settings and `ostrom auth sessions` show them: listed, and ended one at a
 * time.
 */
interface AuthSessionsOfUserUseCase {
    /**
     * Lists the live sign-ins of a user: their browser sessions and their CLI and phone tokens, the last used first.
     *
     * @param userId The user.
     *
     * @return At most [MAX_WEB_SESSIONS_LISTED] browser sessions and [MAX_LISTED_TOKENS] tokens.
     */
    suspend fun list(userId: Uuid): List<AuthSession>

    /**
     * Ends one sign-in of a user, which is recorded in their audit log: a browser session, or a CLI or phone token,
     * which stops working at once.
     *
     * @param userId The user.
     * @param sessionId The browser session or the token.
     *
     * @throws WebSessionNotFoundException When it is not a live sign-in of the user.
     */
    suspend fun revoke(userId: Uuid, sessionId: Uuid)
}

/**
 * [AuthSessionsOfUserUseCase] on the repositories.
 *
 * @param sessions The browser sessions.
 * @param tokens The account-level tokens.
 * @param devices The registered devices, for a token's name.
 * @param audit The audit log.
 * @param clock The clock.
 */
class AuthSessionsOfUserUseCaseImpl(
    private val sessions: WebSessionsRepository,
    private val tokens: ApiTokensRepository,
    private val devices: DevicesRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : AuthSessionsOfUserUseCase {
    override suspend fun list(userId: Uuid): List<AuthSession> {
        val now = clock.now()
        val browsers = sessions.listLive(userId, now, MAX_WEB_SESSIONS_LISTED).map {
            AuthSession(it.id, WEB_SESSION_NAME, null, it.createdAt, it.lastSeenAt)
        }
        val signIns = tokens.listLive(userId, SIGN_IN_TOKEN_KINDS, now)
        val deviceIds = signIns.mapNotNullTo(mutableSetOf()) { it.deviceId }
        // The person's own devices only, as TeamCode's names: a device of someone else never names a line here.
        val names = if (deviceIds.isEmpty()) emptyMap() else devices.list(userId).associate { it.id to it.name }
        val held = signIns.map { token ->
            val name = token.deviceId?.let(names::get) ?: token.name
            AuthSession(token.id, name, token.deviceId, token.createdAt, token.lastUsedAt ?: token.createdAt)
        }
        return (browsers + held).sortedByDescending { it.lastSeenAt }
    }

    override suspend fun revoke(userId: Uuid, sessionId: Uuid) {
        val now = clock.nowMillis()
        if (sessions.revokeOfUser(userId, sessionId, now)) {
            audit.record(AuditEntry(null, userId, "session.revoked", "session", sessionId.toString()))
            return
        }
        // The kind is checked first, so no other token of the person is ended here.
        // Only a live sign-in is ended, as only a live one is listed: an expired token is already over.
        val token = tokens.get(userId, sessionId)?.takeIf { it.expiresAt == null || it.expiresAt > now }
        if (token == null || token.kind !in SIGN_IN_TOKEN_KINDS || !tokens.revoke(userId, sessionId, now)) {
            throw WebSessionNotFoundException()
        }
        val details = mapOf("kind" to token.kind.name.lowercase())
        audit.record(AuditEntry(null, userId, "token.revoked", "token", sessionId.toString(), details))
    }
}
