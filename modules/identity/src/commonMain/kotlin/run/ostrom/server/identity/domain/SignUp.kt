// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.settings.IgnoringInstanceAdminGrants
import run.ostrom.server.kernel.settings.InstanceAdminGrants
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Who may get a new account on this instance (the `sign-up` settings of the console). [key] is the configured and
 * published name.
 *
 * TeamCode has no public registration: its first administrator is created once, then administrators add members. An
 * Ostrom instance is [INVITE] unless its operator opens it ([OPEN], ostrom.run): a sign-in then creates an account
 * only with the instance's setup link, an invitation or the organization's SSO ([SignUpPolicy]).
 */
enum class SignUpMode(val key: String) {
    /** Anyone who signs in gets an account and may create organizations. */
    OPEN("open"),

    /** Only the setup link's person, invited people and an organization's SSO get an account. */
    INVITE("invite"),
    ;

    /** Reads a configured mode. */
    companion object {
        /**
         * Reads a configured mode.
         *
         * @param key The configured name, or null when unset.
         *
         * @return The mode: [INVITE] when unset, or null when the name is unknown.
         */
        fun of(key: String?): SignUpMode? =
            if (key == null) INVITE else entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** How long the setup link `ostrom-server bootstrap` prints works. */
val SETUP_LINK_LIFETIME: Duration = 1.hours

/** The prefix of a setup link's token, which tells it from an invitation's. */
const val SETUP_TOKEN_PREFIX = "osb"

/** The longest token a sign-up grant accepts. */
const val MAX_GRANT_LENGTH = 256

/** Why a sign-in may create an account on an invite-only instance. */
sealed interface SignUpGrant {
    /**
     * What the browser presents: the token of the setup link or of an invitation it gave first (`grantSignUp`), or
     * none.
     *
     * @property token The token, or null.
     */
    data class Presented(val token: String?) : SignUpGrant

    /** The instance's operator creates the account from the host (`ostrom-server seed-test-account`). */
    data object Operator : SignUpGrant
}

/**
 * The instance's one-time setup link, stored hashed until used.
 *
 * @property id The id.
 * @property createdAt When `ostrom-server bootstrap` made it.
 * @property expiresAt When it stops working.
 */
data class SetupLink(val id: Uuid, val createdAt: Instant, val expiresAt: Instant)

/**
 * A setup link just made: the only time its token is shown.
 *
 * @property link The link.
 * @property token Its token, `osb_…`.
 */
data class IssuedSetupLink(val link: SetupLink, val token: String)

/** The instance's setup links. */
interface SetupLinksRepository {
    /**
     * Stores a new link; the links not used yet stop working.
     *
     * @param link The link.
     * @param tokenHash The SHA-256 of its token.
     */
    suspend fun replace(link: SetupLink, tokenHash: String)

    /**
     * Uses a link for the account it creates, atomically, unless it is unknown, used or expired.
     *
     * @param tokenHash The SHA-256 of its token.
     * @param userId The account it creates.
     * @param now The current time.
     *
     * @return Whether it was usable and is now used.
     */
    suspend fun consume(tokenHash: String, userId: Uuid, now: Instant): Boolean

    /**
     * Whether a link is still usable.
     *
     * @param tokenHash The SHA-256 of its token.
     * @param now The current time.
     *
     * @return True when known, unused and unexpired.
     */
    suspend fun pending(tokenHash: String, now: Instant): Boolean

    /**
     * Whether a person's account was created by a setup link: they may create the instance's organizations.
     *
     * @param userId The person.
     *
     * @return True when so.
     */
    suspend fun usedBy(userId: Uuid): Boolean

    /**
     * Whether the instance has an account yet: until it has, `ostrom-server bootstrap` makes setup links.
     *
     * @return True once anyone signed up.
     */
    suspend fun anyAccount(): Boolean

    /**
     * The people who used a setup link, and when: the instance's first administrators.
     *
     * @return Each person, with the time they used it.
     */
    suspend fun owners(): Map<Uuid, Instant>
}

/** The organizations' invitations (the orgs module): a live one lets its person get an account. */
interface SignUpInvitations {
    /**
     * Whether an invitation is live (not revoked, expired or used up).
     *
     * @param token The invitation's token.
     *
     * @return True when so.
     */
    suspend fun isLive(token: String): Boolean
}

/** Who may get an account, and who may create organizations, on this instance. */
interface SignUpPolicy {
    /**
     * The instance's mode now, as an administrator set it in the console.
     *
     * @return The mode.
     */
    suspend fun mode(): SignUpMode

    /**
     * Whether a sign-in may create an account: always on an open instance; on an invite-only one, for an
     * organization's SSO, the operator, a usable setup link or a live invitation. Nothing is used yet ([claim]).
     *
     * @param identity What the provider proved.
     * @param grant What the browser presents, or the operator.
     *
     * @return True when the account may be created.
     */
    suspend fun admits(identity: VerifiedIdentity, grant: SignUpGrant): Boolean

    /**
     * Once the account exists, uses the setup link it was created with (once, recording who), so a failed creation
     * never spends it. An invitation is used when its person accepts it, not here.
     *
     * @param grant What the browser presented.
     * @param userId The account created.
     */
    suspend fun claim(grant: SignUpGrant, userId: Uuid)

    /**
     * Whether a token is a usable setup link or live invitation, without using it (`grantSignUp`).
     *
     * @param token The token.
     *
     * @return True when so.
     */
    suspend fun accepts(token: String): Boolean

    /**
     * Whether the instance lets a person create organizations whatever their roles: on an open instance anyone, on an
     * invite-only one the setup link's person (owners and admins of an organization may too: the orgs module checks).
     *
     * @param userId The person.
     *
     * @return True when so.
     */
    suspend fun mayCreateOrgs(userId: Uuid): Boolean
}

/**
 * [SignUpPolicy].
 *
 * @param readMode Reads the instance's mode now.
 * @param setupLinks The setup links.
 * @param invitations The organizations' invitations, when the orgs module runs.
 * @param hasher Hashes the tokens.
 * @param clock The clock.
 * @param grants Makes the setup link's person an administrator of the instance.
 */
class SignUpPolicyImpl(
    private val readMode: suspend () -> SignUpMode,
    private val setupLinks: SetupLinksRepository,
    private val invitations: SignUpInvitations?,
    private val hasher: SecretHasher,
    private val clock: Clock,
    private val grants: InstanceAdminGrants = IgnoringInstanceAdminGrants(),
) : SignUpPolicy {
    override suspend fun admits(identity: VerifiedIdentity, grant: SignUpGrant): Boolean {
        val token = (grant as? SignUpGrant.Presented)?.token
        // An organization's SSO provisions its own people: its administrators configured it.
        val vouched = mode() == SignUpMode.OPEN || grant == SignUpGrant.Operator || identity.connectionId != null
        return vouched || token != null && accepts(token)
    }

    override suspend fun claim(grant: SignUpGrant, userId: Uuid) {
        val token = (grant as? SignUpGrant.Presented)?.token?.takeIf { isSetupToken(it) } ?: return
        // Lost only to a simultaneous sign-up with the same link: the account stays, without the setup's rights.
        val now = clock.nowMillis()
        if (setupLinks.consume(hasher.hash(token), userId, now)) grants.grantSetupOwner(userId, now)
    }

    override suspend fun accepts(token: String): Boolean {
        if (token.length !in 1..MAX_GRANT_LENGTH) return false
        return if (isSetupToken(token)) {
            setupLinks.pending(hasher.hash(token), clock.nowMillis())
        } else {
            invitations?.isLive(token) == true
        }
    }

    override suspend fun mode(): SignUpMode = readMode()

    override suspend fun mayCreateOrgs(userId: Uuid): Boolean = mode() == SignUpMode.OPEN || setupLinks.usedBy(userId)

    private fun isSetupToken(token: String) = token.startsWith(SETUP_TOKEN_PREFIX + "_")
}

/** Checks the token a browser gives before signing in on an invite-only instance (`grantSignUp`). */
interface GrantSignUpUseCase {
    /**
     * Checks it, without using it: the sign-in that follows uses it.
     *
     * @param token The setup link's or invitation's token.
     * @param source Who is asking (the client address), for the rate limit.
     *
     * @throws RateLimitedException When the address tries too often.
     * @throws SignUpGrantNotFoundException When the token is unknown, used or expired.
     */
    suspend operator fun invoke(token: String, source: String)
}

/**
 * [GrantSignUpUseCase].
 *
 * @param signUp Knows the setup links and invitations.
 * @param limiter Limits the checks per address.
 */
class GrantSignUpUseCaseImpl(private val signUp: SignUpPolicy, private val limiter: RateLimiter) : GrantSignUpUseCase {
    override suspend fun invoke(token: String, source: String) {
        if (!limiter.tryAcquire("signup-grant:$source", LIMIT, WINDOW_MINUTES.minutes)) throw RateLimitedException()
        if (!signUp.accepts(token)) throw SignUpGrantNotFoundException()
    }

    private companion object {
        const val LIMIT = 30
        const val WINDOW_MINUTES = 10
    }
}

/** Makes the instance's setup link (`ostrom-server bootstrap`). */
interface BootstrapInstanceUseCase {
    /**
     * Makes a new setup link, valid [SETUP_LINK_LIFETIME] and once; an earlier one stops working.
     *
     * @return The link and its token, shown once; null when the instance already has an account (its owners and
     *   admins invite people instead).
     */
    suspend operator fun invoke(): IssuedSetupLink?
}

/**
 * [BootstrapInstanceUseCase].
 *
 * @param setupLinks The setup links, and whether the instance has an account.
 * @param secrets Makes the token.
 * @param hasher Hashes it.
 * @param clock The clock.
 */
class BootstrapInstanceUseCaseImpl(
    private val setupLinks: SetupLinksRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val clock: Clock,
) : BootstrapInstanceUseCase {
    override suspend fun invoke(): IssuedSetupLink? {
        if (setupLinks.anyAccount()) return null
        val token = SETUP_TOKEN_PREFIX + "_" + secrets.secret()
        val now = clock.nowMillis()
        val link = SetupLink(Uuid.random(), now, now + SETUP_LINK_LIFETIME)
        setupLinks.replace(link, hasher.hash(token))
        return IssuedSetupLink(link, token)
    }
}

/** The instance is invite-only and nothing lets this person get an account. */
class SignUpClosedException : OstromException(
    ErrorKind.FORBIDDEN,
    "This Ostrom instance is invite-only: ask an owner or an admin of your organization for an invitation.",
    "signup_closed",
)

/** The setup link or invitation presented before signing in is unknown, used or expired. */
class SignUpGrantNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "This setup link or invitation is unknown, used or expired. Ask for a new one.",
)
