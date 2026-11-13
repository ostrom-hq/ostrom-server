// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The prefix of a sign-in link's token (`opk_…`): it is never a bearer token, only exchanged for a session. */
const val SIGN_IN_LINK_PREFIX = "opk"

/** How long a sign-in link works: once, within 15 minutes (decision 30/10 06:00). */
val SIGN_IN_LINK_LIFETIME: Duration = 15.minutes

/**
 * A sign-in link a signed-in person makes to open a browser session elsewhere (the approval page on their phone),
 * stored hashed until used. The token travels in the link, never in a cookie or the browser's storage: the page that
 * opens it exchanges it at once for the session cookies (`POST /api/v1/auth/link`, audit S2-08).
 *
 * @property id The link's id.
 * @property userId Whose session it opens.
 * @property createdAt When it was made.
 * @property expiresAt When it stops working.
 */
data class SignInLink(val id: Uuid, val userId: Uuid, val createdAt: Instant, val expiresAt: Instant)

/**
 * A link just made, with its token, given once.
 *
 * @property link The stored link.
 * @property token Its token (`opk_…`).
 */
data class CreatedSignInLink(val link: SignInLink, val token: String)

/** Where sign-in links are kept. */
interface SignInLinksRepository {
    /**
     * Stores a link.
     *
     * @param link The link.
     * @param tokenHash The hash of its token.
     */
    suspend fun create(link: SignInLink, tokenHash: String)

    /**
     * Uses a link: marks it used and returns it, in one statement, so two exchanges of one token cannot both win.
     *
     * @param tokenHash The hash of the token.
     * @param now The current time.
     *
     * @return The link, or null when unknown, used or expired.
     */
    suspend fun consume(tokenHash: String, now: Instant): SignInLink?
}

/** Makes a sign-in link for the signed-in person. */
interface CreateSignInLinkUseCase {
    /**
     * Makes the link.
     *
     * @param userId The signed-in person.
     *
     * @return The link and its token.
     *
     * @throws RateLimitedException When the person makes too many links.
     */
    suspend operator fun invoke(userId: Uuid): CreatedSignInLink
}

/**
 * [CreateSignInLinkUseCase].
 *
 * @param links Where links are kept.
 * @param secrets Makes the token.
 * @param hasher Hashes it.
 * @param limiter Limits links per person.
 * @param audit The audit log (`sign_in_link.created`).
 * @param clock The clock.
 */
class CreateSignInLinkUseCaseImpl(
    private val links: SignInLinksRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val limiter: RateLimiter,
    private val audit: AuditLog,
    private val clock: Clock,
) : CreateSignInLinkUseCase {
    override suspend fun invoke(userId: Uuid): CreatedSignInLink {
        val allowed = limiter.tryAcquire("sign-in-link:$userId", LIMIT, LIMIT_WINDOW_MINUTES.minutes)
        if (!allowed) throw RateLimitedException()
        val now = clock.nowMillis()
        val token = SIGN_IN_LINK_PREFIX + "_" + secrets.secret()
        val link = SignInLink(Uuid.random(), userId, now, now + SIGN_IN_LINK_LIFETIME)
        links.create(link, hasher.hash(token))
        audit.record(AuditEntry(null, userId, "sign_in_link.created", "sign_in_link", link.id.toString()))
        return CreatedSignInLink(link, token)
    }

    private companion object {
        const val LIMIT = 10
        const val LIMIT_WINDOW_MINUTES = 10
    }
}

/** Exchanges a sign-in link for a browser session. */
interface ExchangeSignInLinkUseCase {
    /**
     * Uses the link, once.
     *
     * @param token The link's token (`opk_…`).
     * @param source Who is asking (the client address), for the rate limit.
     *
     * @return The new browser session.
     *
     * @throws InvalidSignInLinkException When the token is malformed, unknown, used or expired.
     * @throws RateLimitedException When the address tries too often.
     */
    suspend operator fun invoke(token: String, source: String): StartedSession
}

/**
 * [ExchangeSignInLinkUseCase].
 *
 * @param links Where links are kept.
 * @param hasher Hashes the token.
 * @param limiter Limits attempts per address.
 * @param startSession Opens the session.
 * @param audit The audit log (`sign_in_link.used`).
 * @param clock The clock.
 */
class ExchangeSignInLinkUseCaseImpl(
    private val links: SignInLinksRepository,
    private val hasher: SecretHasher,
    private val limiter: RateLimiter,
    private val startSession: StartWebSessionUseCase,
    private val audit: AuditLog,
    private val clock: Clock,
) : ExchangeSignInLinkUseCase {
    override suspend fun invoke(token: String, source: String): StartedSession {
        if (!token.startsWith(SIGN_IN_LINK_PREFIX + "_") || token.length > MAX_TOKEN) throw InvalidSignInLinkException()
        val key = "sign-in-link-use:${rateLimitSource(source)}"
        val allowed = limiter.tryAcquire(key, LIMIT, LIMIT_WINDOW_MINUTES.minutes)
        if (!allowed) throw RateLimitedException()
        val link = links.consume(hasher.hash(token), clock.nowMillis()) ?: throw InvalidSignInLinkException()
        val started = startSession(link.userId)
        audit.record(AuditEntry(null, link.userId, "sign_in_link.used", "sign_in_link", link.id.toString()))
        return started
    }

    private companion object {
        /** No token we issue is longer: a longer value is refused before it is hashed. */
        const val MAX_TOKEN = 128
        const val LIMIT = 20
        const val LIMIT_WINDOW_MINUTES = 10
    }
}

/** The sign-in link is malformed, unknown, used or expired. */
class InvalidSignInLinkException :
    OstromException(ErrorKind.UNAUTHORIZED, "This sign-in link has expired or was already used. Ask for a new one.")
