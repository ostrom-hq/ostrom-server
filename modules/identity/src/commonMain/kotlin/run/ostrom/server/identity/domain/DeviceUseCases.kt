// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.network.formatIpAddress
import run.ostrom.server.kernel.network.parseIpAddress
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

/** The bytes of an IPv4 address [approximateNetwork] keeps: a /24. */
private const val IPV4_NETWORK_BYTES = 3

/** The bytes of an IPv6 address [approximateNetwork] keeps: a /48, what one site is given. */
private const val IPV6_NETWORK_BYTES = 6

/** The bytes of an IPv4 address. */
private const val IPV4_BYTES = 4

/**
 * The network an address belongs to, coarse enough to tell where a request came from without naming the machine:
 * its /24 for IPv4 (`203.0.113.0/24`), its /48 for IPv6 (`2001:db8:abcd::/48`). An IPv4-mapped IPv6 address is read
 * as IPv4.
 *
 * @param address The client address, as the server resolved it behind its trusted proxies.
 *
 * @return The network, or null when [address] is not an IP address.
 */
fun approximateNetwork(address: String): String? {
    val bytes = parseIpAddress(address) ?: return null
    val kept = if (bytes.size == IPV4_BYTES) IPV4_NETWORK_BYTES else IPV6_NETWORK_BYTES
    val network = ByteArray(bytes.size) { if (it < kept) bytes[it] else 0 }
    return formatIpAddress(network) + "/" + kept * Byte.SIZE_BITS
}

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
     * @param source Who asks (the client address), for the rate limit; its network ([approximateNetwork]) is kept
     *   and shown before the yes.
     * @param scopes Null or empty for the ordinary sign-in; every one of [ADMIN_SCOPES] for `ostrom login --admin`.
     *
     * @return The authorization and its device code.
     *
     * @throws RateLimitedException After 10 starts from the same source in 10 minutes.
     * @throws InvalidScopesException When [scopes] are neither none nor exactly [ADMIN_SCOPES].
     */
    suspend operator fun invoke(
        clientName: String?,
        source: String,
        scopes: Collection<String>? = null,
    ): StartedDeviceAuthorization
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
    override suspend fun invoke(
        clientName: String?,
        source: String,
        scopes: Collection<String>?,
    ): StartedDeviceAuthorization {
        val asked = scopes.orEmpty().toSet()
        if (asked.isNotEmpty() && asked != ADMIN_SCOPES) throw InvalidScopesException()
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
            scopes = asked,
            requestedFrom = approximateNetwork(source),
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
     * @param managedOrg For `ostrom login --admin` only, and only to approve it: the organization the user owns or
     *   administers, which the token will be bound to, or null when they are neither in the one they act in.
     *
     * @throws DeviceCodeNotFoundException When it is unknown, expired or already answered.
     * @throws RateLimitedException After 20 answers by the same user in 10 minutes.
     * @throws AdminApprovalForbiddenException When approving `ostrom login --admin` without [managedOrg].
     */
    suspend operator fun invoke(
        userId: Uuid,
        userCode: String,
        approve: Boolean,
        managedOrg: suspend () -> Uuid? = { null },
    )
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
    override suspend fun invoke(userId: Uuid, userCode: String, approve: Boolean, managedOrg: suspend () -> Uuid?) {
        val allowed = limiter.tryAcquire("device-answer:$userId", DEVICE_ANSWERS_PER_WINDOW, DEVICE_RATE_WINDOW)
        if (!allowed) throw RateLimitedException()
        val code = normalizeUserCode(userCode) ?: throw DeviceCodeNotFoundException()
        val now = clock.nowMillis()
        val pending = authorizations.findPendingByUserCode(code, now) ?: throw DeviceCodeNotFoundException()
        // `ostrom login --admin`: only an owner or an admin says yes, and the token is bound to their organization.
        val orgId = if (approve && pending.scopes.isNotEmpty()) {
            managedOrg() ?: throw AdminApprovalForbiddenException()
        } else {
            null
        }
        if (!authorizations.answer(pending.id, userId, approve, now, orgId)) throw DeviceCodeNotFoundException()
        val details = buildMap {
            put("client", pending.clientName)
            if (pending.scopes.isNotEmpty()) put("scopes", pending.scopes.sorted().joinToString(" "))
        }
        val action = if (approve) "device.approved" else "device.denied"
        audit.record(AuditEntry(orgId, userId, action, "device", pending.id.toString(), details))
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
        // `ostrom login --admin`: the governance writes of the approver's organization, for a working day at most.
        val admin = authorization.scopes.isNotEmpty()
        val orgId = authorization.approvingOrgId.takeIf { admin }
        check(!admin || orgId != null) { "An approved admin login has no organization." }
        val kind = if (admin) TokenKind.ADMIN else TokenKind.CLI
        val issued = issue(secrets, kind) { id, hint ->
            val expiresAt = now + if (admin) ADMIN_TOKEN_LIFETIME else CLI_TOKEN_LIFETIME
            val scopes = if (admin) ADMIN_TOKEN_SCOPES else CLI_SCOPES
            ApiToken(id, userId, orgId, authorization.clientName, kind, scopes, hint, null, now, expiresAt, null, null)
        }
        if (!authorizations.consume(authorization.id, issued.token, hasher.hash(issued.secret))) {
            return DevicePollResult.Expired
        }
        val tokenId = issued.token.id.toString()
        val details = mapOf("kind" to kind.name.lowercase())
        audit.record(AuditEntry(orgId, userId, "token.created", "token", tokenId, details))
        return DevicePollResult.Approved(issued)
    }

    private companion object {
        const val MAX_CODE = 128
    }
}
