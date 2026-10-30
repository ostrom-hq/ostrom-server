// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Normalizes a user code as typed: upper case, without spaces or dashes, then `ABCD-EFGH`.
 *
 * @param raw The code as typed.
 *
 * @return The code, or null when it cannot be one.
 */
fun normalizeUserCode(raw: String): String? {
    val letters = raw.uppercase().filter { it != '-' && !it.isWhitespace() }
    if (letters.length != USER_CODE_LETTERS || letters.any { it !in USER_CODE_ALPHABET }) return null
    return letters.substring(0, USER_CODE_LETTERS / 2) + "-" + letters.substring(USER_CODE_LETTERS / 2)
}

/** The letters of a user code: no vowels (no words), no look-alikes (0/O, 1/I/L). */
const val USER_CODE_ALPHABET = "BCDFGHJKMNPQRSTVWXZ"

/** How many letters a user code has. */
const val USER_CODE_LETTERS = 8

/** Device logins one address may start per [DEVICE_RATE_WINDOW]. */
private const val DEVICE_STARTS_PER_WINDOW = 10

/** Device codes one user may look up, or answer, per [DEVICE_RATE_WINDOW]. */
private const val DEVICE_ANSWERS_PER_WINDOW = 20

/** The window of the device login rate limits. */
private val DEVICE_RATE_WINDOW = 10.minutes

/** Starts an `ostrom login` (RFC 8628 device authorization request). */
interface StartDeviceAuthorizationUseCase {
    /**
     * Starts it.
     *
     * @param clientName What the CLI says it is, shown to the user before they approve (trimmed, 100 characters).
     * @param source Who asks (the client address), for the rate limit.
     *
     * @return The authorization and its device code.
     *
     * @throws RateLimitedException After 10 starts from the same source in 10 minutes.
     */
    suspend operator fun invoke(clientName: String?, source: String): StartedDeviceAuthorization
}

/**
 * [StartDeviceAuthorizationUseCase] on the repository.
 *
 * @param authorizations The authorizations.
 * @param secrets Makes the device and user codes.
 * @param hasher Hashes the device code.
 * @param limiter The rate limit.
 * @param clock The clock.
 */
class StartDeviceAuthorizationUseCaseImpl(
    private val authorizations: DeviceAuthorizationsRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val limiter: RateLimiter,
    private val clock: Clock,
) : StartDeviceAuthorizationUseCase {
    override suspend fun invoke(clientName: String?, source: String): StartedDeviceAuthorization {
        val allowed = limiter.tryAcquire("device-start:$source", DEVICE_STARTS_PER_WINDOW, DEVICE_RATE_WINDOW)
        if (!allowed) throw RateLimitedException()
        val now = clock.nowMillis()
        val name = trimmedOrNull(clientName?.filterNot { it.isISOControl() }, MAX_CLIENT_NAME) ?: DEFAULT_CLIENT_NAME
        val deviceCode = secrets.secret()
        val authorization = DeviceAuthorization(
            id = Uuid.random(),
            userCode = secrets.userCode(),
            clientName = name,
            status = DeviceAuthorizationStatus.PENDING,
            userId = null,
            createdAt = now,
            expiresAt = now + DEVICE_CODE_LIFETIME,
            lastPolledAt = null,
        )
        authorizations.create(authorization, hasher.hash(deviceCode))
        return StartedDeviceAuthorization(authorization, deviceCode)
    }

    private companion object {
        const val MAX_CLIENT_NAME = 100
        const val DEFAULT_CLIENT_NAME = "Ostrom CLI"
    }
}

/** Reads a pending `ostrom login` by its user code, so the console shows what is asking before the user approves. */
interface GetDeviceAuthorizationUseCase {
    /**
     * Reads it.
     *
     * @param userId The signed-in user (rate-limited: user codes are short).
     * @param userCode The code as typed.
     *
     * @return The pending authorization.
     *
     * @throws DeviceCodeNotFoundException When it is unknown, expired or already answered.
     * @throws RateLimitedException After 20 lookups by the same user in 10 minutes.
     */
    suspend operator fun invoke(userId: Uuid, userCode: String): DeviceAuthorization
}

/**
 * [GetDeviceAuthorizationUseCase] on the repository.
 *
 * @param authorizations The authorizations.
 * @param limiter The rate limit.
 * @param clock The clock.
 */
class GetDeviceAuthorizationUseCaseImpl(
    private val authorizations: DeviceAuthorizationsRepository,
    private val limiter: RateLimiter,
    private val clock: Clock,
) : GetDeviceAuthorizationUseCase {
    override suspend fun invoke(userId: Uuid, userCode: String): DeviceAuthorization {
        val allowed = limiter.tryAcquire("device-lookup:$userId", DEVICE_ANSWERS_PER_WINDOW, DEVICE_RATE_WINDOW)
        if (!allowed) throw RateLimitedException()
        val code = normalizeUserCode(userCode) ?: throw DeviceCodeNotFoundException()
        return authorizations.findPendingByUserCode(code, clock.now()) ?: throw DeviceCodeNotFoundException()
    }
}

/** Approves or denies an `ostrom login` from the console. */
interface AnswerDeviceAuthorizationUseCase {
    /**
     * Answers it.
     *
     * @param userId The signed-in user; an approval logs the CLI in as them.
     * @param userCode The code as typed.
     * @param approve True to approve, false to deny.
     *
     * @throws DeviceCodeNotFoundException When it is unknown, expired or already answered.
     * @throws RateLimitedException After 20 answers by the same user in 10 minutes.
     */
    suspend operator fun invoke(userId: Uuid, userCode: String, approve: Boolean)
}

/**
 * [AnswerDeviceAuthorizationUseCase] on the repository.
 *
 * @param authorizations The authorizations.
 * @param limiter The rate limit.
 * @param audit The audit log.
 * @param clock The clock.
 */
class AnswerDeviceAuthorizationUseCaseImpl(
    private val authorizations: DeviceAuthorizationsRepository,
    private val limiter: RateLimiter,
    private val audit: AuditLog,
    private val clock: Clock,
) : AnswerDeviceAuthorizationUseCase {
    override suspend fun invoke(userId: Uuid, userCode: String, approve: Boolean) {
        val allowed = limiter.tryAcquire("device-answer:$userId", DEVICE_ANSWERS_PER_WINDOW, DEVICE_RATE_WINDOW)
        if (!allowed) throw RateLimitedException()
        val code = normalizeUserCode(userCode) ?: throw DeviceCodeNotFoundException()
        val now = clock.nowMillis()
        val pending = authorizations.findPendingByUserCode(code, now) ?: throw DeviceCodeNotFoundException()
        if (!authorizations.answer(pending.id, userId, approve, now)) throw DeviceCodeNotFoundException()
        audit.record(
            AuditEntry(
                null, userId, if (approve) "device.approved" else "device.denied", "device", pending.id.toString(),
                mapOf("client" to pending.clientName),
            ),
        )
    }
}

/** What a CLI poll gets (RFC 8628 § 3.5). */
sealed interface DevicePollResult {
    /** The user has not answered yet: poll again after the interval. */
    data object Pending : DevicePollResult

    /** The CLI polled too fast: wait longer. */
    data object SlowDown : DevicePollResult

    /** The user denied the login. */
    data object Denied : DevicePollResult

    /** The code expired or was already used: start again. */
    data object Expired : DevicePollResult

    /**
     * The user approved: here is the CLI token, once.
     *
     * @property token The token and its secret.
     */
    data class Approved(val token: IssuedToken) : DevicePollResult
}

/** The CLI's poll for its token. */
interface PollDeviceAuthorizationUseCase {
    /**
     * Polls.
     *
     * @param deviceCode The device code the CLI got at the start.
     *
     * @return Where the login stands; a token only once.
     *
     * @throws DeviceCodeNotFoundException When the device code is unknown.
     */
    suspend operator fun invoke(deviceCode: String): DevicePollResult
}

/**
 * [PollDeviceAuthorizationUseCase] on the repository.
 *
 * @param authorizations The authorizations.
 * @param secrets Makes the CLI token.
 * @param hasher Hashes codes and secrets.
 * @param audit The audit log.
 * @param clock The clock.
 */
class PollDeviceAuthorizationUseCaseImpl(
    private val authorizations: DeviceAuthorizationsRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val audit: AuditLog,
    private val clock: Clock,
) : PollDeviceAuthorizationUseCase {
    override suspend fun invoke(deviceCode: String): DevicePollResult {
        if (deviceCode.isEmpty() || deviceCode.length > MAX_CODE) throw DeviceCodeNotFoundException()
        val authorization = authorizations.findByDeviceCodeHash(hasher.hash(deviceCode))
            ?: throw DeviceCodeNotFoundException()
        val now = clock.nowMillis()
        if (authorization.status == DeviceAuthorizationStatus.CONSUMED || authorization.expiresAt <= now) {
            return DevicePollResult.Expired
        }
        val tooSoon = authorization.lastPolledAt?.let { now - it < DEVICE_POLL_INTERVAL } == true
        authorizations.markPolled(authorization.id, now)
        return when (authorization.status) {
            DeviceAuthorizationStatus.DENIED -> DevicePollResult.Denied
            DeviceAuthorizationStatus.PENDING -> if (tooSoon) DevicePollResult.SlowDown else DevicePollResult.Pending
            else -> approve(authorization, now)
        }
    }

    private suspend fun approve(authorization: DeviceAuthorization, now: kotlin.time.Instant): DevicePollResult {
        val userId = checkNotNull(authorization.userId) { "An approved device authorization has no user." }
        val issued = issue(secrets, TokenKind.CLI) { id, hint ->
            val expiresAt = now + CLI_TOKEN_LIFETIME
            val name = authorization.clientName
            ApiToken(id, userId, null, name, TokenKind.CLI, CLI_SCOPES, hint, null, now, expiresAt, null, null)
        }
        if (!authorizations.consume(authorization.id, issued.token, hasher.hash(issued.secret))) {
            return DevicePollResult.Expired
        }
        val tokenId = issued.token.id.toString()
        audit.record(AuditEntry(null, userId, "token.created", "token", tokenId, mapOf("kind" to "cli")))
        return DevicePollResult.Approved(issued)
    }

    private companion object {
        const val MAX_CODE = 128
    }
}
