// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.domain

import kotlin.time.Duration

/** Makes the secrets of sessions, tokens and device codes. */
interface SecretGenerator {
    /**
     * Makes a high-entropy secret (at least 256 bits from a cryptographically secure source).
     *
     * @return The secret, URL-safe base64 without padding.
     */
    fun secret(): String

    /**
     * Makes a short code a person can read and type: 8 letters from an alphabet without look-alikes, `ABCD-EFGH`.
     *
     * @return The code.
     */
    fun userCode(): String
}

/** Hashes secrets before they are stored or compared. */
interface SecretHasher {
    /**
     * Hashes a secret. Secrets are high-entropy, so a fast hash (SHA-256) is enough: no salt, no stretching, and a
     * lookup by hash stays one indexed read.
     *
     * @param secret The secret.
     *
     * @return The hash, lower-case hex.
     */
    suspend fun hash(secret: String): String

    /**
     * Derives the CSRF token of a browser session from its secret: the console gets it in the session's answers, a
     * page of another site cannot compute it without the HttpOnly cookie.
     *
     * @param sessionSecret The session's cookie value.
     *
     * @return The CSRF token.
     */
    suspend fun csrfToken(sessionSecret: String): String

    /**
     * The PKCE `S256` challenge of a verifier (RFC 7636 § 4.2): base64url, without padding, of its SHA-256.
     *
     * @param verifier The code verifier.
     *
     * @return The challenge.
     */
    suspend fun pkceChallenge(verifier: String): String
}

/** Limits how often something can be tried (sign-in links, code polls, token creation). */
interface RateLimiter {
    /**
     * Counts an attempt and says whether it is allowed.
     *
     * @param key What is limited, such as `device-start:<ip>`.
     * @param limit How many attempts the window allows.
     * @param window The length of the window.
     *
     * @return True when this attempt is within the limit.
     */
    suspend fun tryAcquire(key: String, limit: Int, window: Duration): Boolean
}

/** The stored windows of [RateLimiter], shared by every replica. */
interface RateLimitWindows {
    /**
     * Forgets the windows already over: no attempt reads them again.
     *
     * @return How many were forgotten.
     */
    suspend fun purgeExpired(): Long
}

/** Records sensitive actions in the signed audit log; other modules use it through Koin. */
interface AuditLog {
    /**
     * Records an action.
     *
     * @param entry What happened.
     *
     * @return The signed, chained event.
     */
    suspend fun record(entry: AuditEntry): AuditEvent

    /**
     * The signed event of [entry], built once its place in its chain is known: for a writer that appends it to the
     * chain itself, inside the transaction of the action it tells of
     * ([run.ostrom.server.identity.infrastructure.TransactionalAuditLog]).
     *
     * @param entry What happened.
     *
     * @return Builds the event from the previous event's hash (null for the first) and the next sequence.
     */
    fun sign(entry: AuditEntry): suspend (previousHash: String?, sequence: Long) -> AuditEvent
}

/**
 * Compares two strings in time that depends only on their lengths, so a CSRF token cannot be guessed byte by byte
 * from response times.
 *
 * @param a The first string.
 * @param b The second string.
 *
 * @return True when equal.
 */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
    return difference == 0
}
