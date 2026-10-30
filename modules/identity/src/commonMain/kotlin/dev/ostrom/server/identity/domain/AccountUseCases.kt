package dev.ostrom.server.identity.domain

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Two sign-ins created the same identity or verified the same e-mail at the same moment. */
class DuplicateIdentityException : RuntimeException("The identity or the verified e-mail already exists.")

/**
 * The current time, cut to the millisecond: what PostgreSQL stores (microseconds) then reads back unchanged, so a
 * value signed before it is stored (an audit event) verifies after it is read.
 *
 * @return The time.
 */
fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())

/**
 * Turns a verified sign-in into a user: the user of that identity if it was seen before; else the user who verified
 * the same e-mail (accounts merge only on a verified e-mail, never on an unverified one, so nobody takes over an
 * account by claiming its address at a provider that does not check it); else a new user.
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
        val userEmail = email?.let { UserEmail(it, identity.emailVerified) }
        val owner = if (email != null && identity.emailVerified) users.findByVerifiedEmail(email) else null
        users.findByIdentity(identity.issuer, identity.subject)?.let { user ->
            // The address is recorded unless another user already verified it: two accounts never share one.
            users.link(user.id, identity, userEmail?.takeIf { owner == null || owner.id == user.id }, now)
            return user
        }
        if (owner != null) {
            users.link(owner.id, identity, userEmail, now)
            audit.record(AuditEntry(null, owner.id, "identity.linked", "user", owner.id.toString(), mapOf("provider" to identity.issuer)))
            return owner
        }
        val user = User(
            id = Uuid.random(),
            displayName = identity.displayName?.trim()?.take(MAX_DISPLAY_NAME)?.takeIf { it.isNotEmpty() }
                ?: email?.substringBefore('@')
                ?: DEFAULT_DISPLAY_NAME,
            primaryEmail = email,
            avatarUrl = identity.avatarUrl?.takeIf { it.startsWith("https://") && it.length <= MAX_URL },
            createdAt = now,
        )
        users.create(user, identity, userEmail, now)
        audit.record(AuditEntry(null, user.id, "user.created", "user", user.id.toString(), mapOf("provider" to identity.issuer)))
        return user
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
        audit.record(AuditEntry(null, userId, "sessions.revoked", "user", userId.toString(), mapOf("count" to count.toString())))
        return count
    }
}
