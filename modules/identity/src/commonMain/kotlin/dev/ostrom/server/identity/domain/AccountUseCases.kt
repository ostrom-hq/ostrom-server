// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Two sign-ins created the same identity or verified the same e-mail at the same moment. */
class DuplicateIdentityException : RuntimeException("The identity or the verified e-mail already exists.")

/**
 * A provider that is not an authority for the address (an organization's SSO, Google for an address outside Gmail
 * and the account's Workspace) asserts the address of an existing account: nobody is signed in (S2-07). The owner
 * signs in to that account, then links this provider from it ([LinkIdentityUseCase]).
 */
class AccountLinkRequiredException : OstromException(
    ErrorKind.CONFLICT,
    "An account already uses this e-mail. Sign in to it, then link this sign-in method from your account settings.",
)

/** The identity to link already signs another user in: it stays theirs. */
class IdentityTakenException :
    OstromException(ErrorKind.CONFLICT, "This sign-in method already belongs to another account.")

/** The domains whose mailboxes Google itself runs: for them, Google is an authority on the address. */
private val GOOGLE_MAIL_DOMAINS = setOf("gmail.com", "googlemail.com")

/**
 * Whether a provider is an authority for the address it gives (S2-07): only then is the address recorded as verified,
 * and only then does a sign-in merge into the account that verified it. A sign-in link proves the mailbox; GitHub
 * only reports addresses its user confirmed by mail; Google runs Gmail and lets a Workspace administrator assert only
 * the addresses of their own domain (`hd`). An organization's SSO asserts whatever its administrator types: never.
 *
 * @param identity What the provider said.
 * @param email The address, normalized.
 *
 * @return True when the address is proven.
 */
internal fun vouchesFor(identity: VerifiedIdentity, email: String): Boolean {
    if (!identity.emailVerified) return false
    val domain = email.substringAfter('@')
    return when (identity.provider) {
        IdentityProvider.EMAIL, IdentityProvider.GITHUB -> true
        IdentityProvider.GOOGLE -> domain in GOOGLE_MAIL_DOMAINS || identity.hostedDomain?.lowercase() == domain
        IdentityProvider.OIDC -> false
    }
}

/**
 * The current time, cut to the millisecond: what PostgreSQL stores (microseconds) then reads back unchanged, so a
 * value signed before it is stored (an audit event) verifies after it is read.
 *
 * @return The time.
 */
fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())

/**
 * Turns a verified sign-in into a user: the user of that identity if it was seen before; else the user who verified
 * the same e-mail, when the provider is an authority for it ([vouchesFor]); else a new user. Accounts never merge on
 * an address a provider merely asserts (S2-07): an organization's SSO naming the address of an existing account is
 * refused ([AccountLinkRequiredException]), and the address it gives is recorded unverified, so it can never become
 * the anchor a later sign-in merges into.
 */
interface ResolveUserUseCase {
    /**
     * Resolves the user.
     *
     * @param identity What the provider proved.
     *
     * @return The user, existing or created.
     */
    suspend operator fun invoke(identity: VerifiedIdentity): User
}

/**
 * [ResolveUserUseCase] on the repository.
 *
 * @param users The users.
 * @param audit The audit log (account creation and linking are recorded).
 * @param clock The clock.
 */
class ResolveUserUseCaseImpl(
    private val users: UsersRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : ResolveUserUseCase {
    override suspend fun invoke(identity: VerifiedIdentity): User = try {
        resolve(identity)
    } catch (_: DuplicateIdentityException) {
        // Another sign-in of the same person won the race: the second pass finds what it created.
        resolve(identity)
    }

    private suspend fun resolve(identity: VerifiedIdentity): User {
        val now = clock.nowMillis()
        val email = identity.email?.let(::normalizeOrNull)
        val vouched = email != null && vouchesFor(identity, email)
        val userEmail = email?.let { UserEmail(it, vouched) }
        val owner = if (email != null && identity.emailVerified) users.findByVerifiedEmail(email) else null
        val known = users.findByIdentity(identity.issuer, identity.subject)
        return when {
            known != null -> known.also { user ->
                // The address is recorded unless another user already verified it: two accounts never share one.
                users.link(user.id, identity, userEmail?.takeIf { owner == null || owner.id == user.id }, now)
            }
            owner != null -> {
                // The address is the owner's: only an authority for it may sign in to their account (S2-07).
                if (!vouched) throw AccountLinkRequiredException()
                users.link(owner.id, identity, userEmail, now)
                val details = mapOf("provider" to identity.issuer)
                audit.record(AuditEntry(null, owner.id, "identity.linked", "user", owner.id.toString(), details))
                owner
            }
            else -> create(identity, email, userEmail, now)
        }
    }

    private suspend fun create(identity: VerifiedIdentity, email: String?, userEmail: UserEmail?, now: Instant): User {
        val user = User(
            id = Uuid.random(),
            login = freeLogin(identity, email),
            displayName = identity.displayName?.trim()?.take(MAX_DISPLAY_NAME)?.takeIf { it.isNotEmpty() }
                ?: email?.substringBefore('@')
                ?: DEFAULT_DISPLAY_NAME,
            primaryEmail = email,
            avatarUrl = identity.avatarUrl?.takeIf { it.startsWith("https://") && it.length <= MAX_URL },
            createdAt = now,
        )
        users.create(user, identity, userEmail, now)
        val details = mapOf("provider" to identity.issuer)
        audit.record(AuditEntry(null, user.id, "user.created", "user", user.id.toString(), details))
        return user
    }

    private suspend fun freeLogin(identity: VerifiedIdentity, email: String?): String {
        val base = listOfNotNull(identity.login, email?.substringBefore('@'), identity.displayName)
            .firstNotNullOfOrNull(::loginOf) ?: DEFAULT_LOGIN
        // ada, then ada-2, ada-3…; past a few, a random suffix (a very common name), then a race is retried above.
        val candidates = listOf(base) +
            (2..NUMBERED_LOGINS).map { number -> "${base.take(MAX_LOGIN - 1 - number.toString().length)}-$number" }
        for (candidate in candidates) if (!users.loginTaken(candidate)) return candidate
        return "${base.take(MAX_LOGIN - RANDOM_SUFFIX - 1)}-${Uuid.random().toHexString().take(RANDOM_SUFFIX)}"
    }

    private fun normalizeOrNull(raw: String): String? = try {
        normalizeEmail(raw)
    } catch (_: InvalidEmailException) {
        // A provider gave something that is not an address: the account simply has no e-mail.
        null
    }

    private companion object {
        const val MAX_DISPLAY_NAME = 100
        const val MAX_URL = 2048
        const val DEFAULT_DISPLAY_NAME = "Ostrom user"
        const val DEFAULT_LOGIN = "user"
        const val NUMBERED_LOGINS = 20
        const val RANDOM_SUFFIX = 8
    }
}

/**
 * Links a sign-in method to the signed-in user (S2-07): the only way an identity that is not an authority for an
 * address joins the account of that address. The person proved the account first (a browser session), then the
 * provider in the same browser (the OAuth state cookie of an attempt started by that session).
 */
interface LinkIdentityUseCase {
    /**
     * Links the identity.
     *
     * @param userId The signed-in user.
     * @param identity What the provider proved.
     *
     * @return The user.
     *
     * @throws UserNotFoundException When the user no longer exists.
     * @throws IdentityTakenException When the identity signs another user in.
     */
    suspend operator fun invoke(userId: Uuid, identity: VerifiedIdentity): User
}

/**
 * [LinkIdentityUseCase] on the repository. The identity's address is not recorded: linking adds a way in, never an
 * address (a provider asserting one proves nothing about it).
 *
 * @param users The users.
 * @param audit The audit log (`identity.linked`).
 * @param clock The clock.
 */
class LinkIdentityUseCaseImpl(
    private val users: UsersRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : LinkIdentityUseCase {
    override suspend fun invoke(userId: Uuid, identity: VerifiedIdentity): User {
        val user = users.get(userId) ?: throw UserNotFoundException()
        val holder = users.findByIdentity(identity.issuer, identity.subject)
        if (holder != null && holder.id != userId) throw IdentityTakenException()
        users.link(userId, identity, null, clock.nowMillis())
        val details = mapOf("provider" to identity.issuer)
        audit.record(AuditEntry(null, userId, "identity.linked", "user", userId.toString(), details))
        return user
    }
}

/**
 * A user as the console shows it to themselves.
 *
 * @property user The user.
 * @property emails Their e-mails.
 * @property issuers The ways they sign in.
 */
data class Profile(
    val user: User,
    val emails: List<UserEmail>,
    val issuers: List<String>,
)

/** Reads the signed-in user's own profile. */
interface GetProfileUseCase {
    /**
     * Reads the profile.
     *
     * @param userId The user.
     *
     * @return The profile.
     *
     * @throws UserNotFoundException When the user no longer exists.
     */
    suspend operator fun invoke(userId: Uuid): Profile
}

/**
 * [GetProfileUseCase] on the repository.
 *
 * @param users The users.
 */
class GetProfileUseCaseImpl(private val users: UsersRepository) : GetProfileUseCase {
    override suspend fun invoke(userId: Uuid): Profile {
        val user = users.get(userId) ?: throw UserNotFoundException()
        return Profile(user, users.listEmails(userId), users.listIssuers(userId))
    }
}

/** Starts a browser session for a signed-in user (after GitHub, Google, a link or SSO proved who they are). */
interface StartWebSessionUseCase {
    /**
     * Starts the session.
     *
     * @param userId The user.
     *
     * @return The session, its cookie value and its CSRF token.
     */
    suspend operator fun invoke(userId: Uuid): StartedSession
}

/**
 * [StartWebSessionUseCase] on the repository.
 *
 * @param sessions The sessions.
 * @param secrets Makes the cookie value.
 * @param hasher Hashes it, derives the CSRF token.
 * @param clock The clock.
 */
class StartWebSessionUseCaseImpl(
    private val sessions: WebSessionsRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val clock: Clock,
) : StartWebSessionUseCase {
    override suspend fun invoke(userId: Uuid): StartedSession {
        val now = clock.nowMillis()
        val secret = SESSION_PREFIX + "_" + secrets.secret()
        val session = WebSession(Uuid.random(), userId, now, now + SESSION_LIFETIME, now, null)
        sessions.create(session, hasher.hash(secret))
        return StartedSession(session, secret, hasher.csrfToken(secret))
    }
}

/** The prefix of a session cookie value. */
const val SESSION_PREFIX = "oss"

/** Ends browser sessions: sign out here, or everywhere. */
interface EndWebSessionUseCase {
    /**
     * Signs out of one session.
     *
     * @param sessionId The session.
     */
    suspend operator fun invoke(sessionId: Uuid)

    /**
     * Signs out of every session of the user.
     *
     * @param userId The user.
     *
     * @return How many sessions ended.
     */
    suspend fun everywhere(userId: Uuid): Long
}

/**
 * [EndWebSessionUseCase] on the repository.
 *
 * @param sessions The sessions.
 * @param audit The audit log (signing out everywhere is recorded).
 * @param clock The clock.
 */
class EndWebSessionUseCaseImpl(
    private val sessions: WebSessionsRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : EndWebSessionUseCase {
    override suspend fun invoke(sessionId: Uuid) = sessions.revoke(sessionId, clock.nowMillis())

    override suspend fun everywhere(userId: Uuid): Long {
        val count = sessions.revokeAllOfUser(userId, clock.nowMillis())
        val details = mapOf("count" to count.toString())
        audit.record(AuditEntry(null, userId, "sessions.revoked", "user", userId.toString(), details))
        return count
    }
}

/** The longest login (GitHub's limit). */
const val MAX_LOGIN = 39

/**
 * Makes a login from a hint: lower case, letters and digits kept, every other run of characters becomes one dash, no
 * dash at either end, at most [MAX_LOGIN] characters.
 *
 * @param hint A GitHub login, an e-mail's local part or a name.
 *
 * @return The login, or null when nothing usable is left.
 */
fun loginOf(hint: String): String? {
    val login = hint.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(MAX_LOGIN).trimEnd('-')
    return login.takeIf { it.isNotEmpty() }
}
