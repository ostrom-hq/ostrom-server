package dev.ostrom.server.sharing.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An account taken for one request. The gateway sends [credentials], reports the answer with [observe], and always
 * calls [release].
 *
 * @property account The account.
 * @property credentials Fresh tokens.
 */
class AccountLease internal constructor(
    val account: SharedAccount,
    val credentials: AccountCredentials,
    private val pool: SharedAccountPool,
) {
    private var released = false

    /**
     * Reports the provider's answer: quota windows from the headers, a cool-down on 429, a forced refresh on 401.
     *
     * @param status The HTTP status.
     * @param headers The response headers, names in lower case.
     */
    suspend fun observe(status: Int, headers: Map<String, String>) = pool.observe(account, status, headers)

    /** Gives the slot back; idempotent. */
    suspend fun release() {
        if (released) return
        released = true
        pool.release(account.id)
    }
}

/**
 * Why no account was leased.
 *
 * @property reason A message safe for the agent.
 * @property retryAt When an account frees up, if known.
 */
data class PoolRefusal(val reason: String, val retryAt: Instant?)

/** The result of a lease. */
sealed interface LeaseResult {
    /** An account. */
    data class Leased(val lease: AccountLease) : LeaseResult

    /** None: the org has no usable account of this provider for the member now. */
    data class Refused(val refusal: PoolRefusal) : LeaseResult
}

/**
 * The org's pool of shared personal accounts, as TeamCode runs it: an account serves when READY, out of cool-down,
 * under its own limits (concurrency, utilization ceiling of every current window, monthly budget) and allowed for the
 * member. Among those, a session sticks to its account (the provider's prompt cache lives there), otherwise the least
 * used account wins, then the higher priority. Tokens are refreshed five minutes before they expire, once per account
 * at a time; a refused refresh token marks the account RECONNECT_REQUIRED.
 *
 * Concurrency and affinity are per replica, in memory.
 *
 * @param repository Accounts, sealed tokens and windows.
 * @param cipher Opens and seals the tokens.
 * @param refresher Renews tokens.
 * @param parser Reads quota headers.
 * @param spend What each account served this month.
 * @param clock The time.
 */
class SharedAccountPool(
    private val repository: SharedAccountRepository,
    private val cipher: AccountTokenCipher,
    private val refresher: OAuthRefresher,
    private val parser: QuotaHeaderParser,
    private val spend: AccountSpendReader,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<Uuid, Int>()
    private val affinity = mutableMapOf<String, Pair<Uuid, Instant>>()
    private val refreshLocks = mutableMapOf<Uuid, Mutex>()

    /**
     * Leases an account.
     *
     * @param orgId The org.
     * @param memberId The member asking.
     * @param provider The subscription the request needs.
     * @param sessionKey The agent session, for affinity, if known.
     */
    suspend fun lease(orgId: Uuid, memberId: Uuid, provider: AccountProvider, sessionKey: String?): LeaseResult =
        lease(orgId, memberId, provider, sessionKey, emptySet())

    private suspend fun lease(orgId: Uuid, memberId: Uuid, provider: AccountProvider, sessionKey: String?, tried: Set<Uuid>): LeaseResult {
        val now = clock.now()
        val accounts = repository.ready(orgId, provider)
            .filter { it.id !in tried && (it.limits.allowedMembers?.contains(memberId) ?: true) }
        if (accounts.isEmpty()) return refused("No shared ${provider.wire} account is available to you in this organization.", null)
        val windows = repository.windows(orgId)
        val monthStart = monthStart(now)
        val eligible = mutableListOf<Pair<SharedAccount, Double>>()
        var retryAt: Instant? = null
        for (account in accounts) {
            val current = windows[account.id].orEmpty().filter { it.isCurrent(now) && !it.window.startsWith("model:") }
            val cooling = account.cooldownUntil?.takeIf { it > now }
            val full = current.filter { it.utilization >= account.limits.utilizationCeiling }
            val budget = account.limits.monthlyBudgetMicros
            val overBudget = budget != null && spend.spentMicros(account.id, monthStart) >= budget
            when {
                cooling != null -> retryAt = minOf(retryAt ?: cooling, cooling)
                full.isNotEmpty() -> full.mapNotNull { it.resetsAt }.maxOrNull()?.let { retryAt = minOf(retryAt ?: it, it) }
                overBudget -> Unit
                else -> eligible += account to (current.maxOfOrNull { it.utilization } ?: 0.0)
            }
        }
        val chosen = mutex.withLock {
            val open = eligible.filter { (account, _) -> (inFlight[account.id] ?: 0) < account.limits.maxConcurrent }
            val sticky = sessionKey?.let { key -> affinity[key]?.takeIf { it.second > now }?.first }
            val pick = open.firstOrNull { it.first.id == sticky }?.first
                ?: open.sortedWith(compareBy<Pair<SharedAccount, Double>> { it.second }.thenByDescending { it.first.priority }.thenBy { it.first.id.toString() })
                    .firstOrNull()?.first
            pick?.also { account ->
                inFlight[account.id] = (inFlight[account.id] ?: 0) + 1
                if (sessionKey != null) affinity[sessionKey] = account.id to now + AFFINITY
                if (affinity.size > MAX_AFFINITY) affinity.entries.removeAll { it.value.second <= now }
            }
        }
        if (chosen == null) {
            val reason = if (eligible.isNotEmpty()) "Every shared ${provider.wire} account is busy." else "Every shared ${provider.wire} account is at its limit."
            return refused(reason, retryAt ?: if (eligible.isNotEmpty()) now + 5.seconds else null)
        }
        return try {
            when (val credentials = credentials(chosen)) {
                null -> {
                    release(chosen.id)
                    // This account just failed to refresh: try the others.
                    lease(orgId, memberId, provider, sessionKey, tried + chosen.id)
                }
                else -> LeaseResult.Leased(AccountLease(chosen, credentials, this))
            }
        } catch (error: CancellationException) {
            release(chosen.id)
            throw error
        }
    }

    /** Fresh tokens of the account, refreshed when close to expiry; null when the account can no longer serve. */
    private suspend fun credentials(account: SharedAccount): AccountCredentials? {
        val lock = mutex.withLock { refreshLocks.getOrPut(account.id) { Mutex() } }
        return lock.withLock {
            val envelope = repository.envelope(account.orgId, account.id) ?: return@withLock null
            val stored = cipher.open(envelope, account.orgId, account.id)
            if (stored == null) {
                repository.setStatus(account.orgId, account.id, AccountStatus.RECONNECT_REQUIRED)
                return@withLock null
            }
            if (stored.expiresAt > clock.now() + REFRESH_MARGIN) return@withLock stored
            when (val result = refresher.refresh(account.provider, stored)) {
                is RefreshResult.Renewed -> {
                    repository.putEnvelope(account.orgId, account.id, cipher.seal(result.credentials, account.orgId, account.id))
                    result.credentials
                }
                RefreshResult.Revoked -> {
                    repository.setStatus(account.orgId, account.id, AccountStatus.RECONNECT_REQUIRED)
                    null
                }
                // Still valid for a few minutes: serve with it; expired: let the provider's 401 speak.
                RefreshResult.Unavailable -> stored.takeIf { it.expiresAt > clock.now() }
            }
        }
    }

    internal suspend fun observe(account: SharedAccount, status: Int, headers: Map<String, String>) {
        val now = clock.now()
        val windows = parser.parse(account.provider, headers, now)
        if (windows.isNotEmpty()) repository.putWindows(account.orgId, account.id, windows)
        when (status) {
            429 -> {
                val retryAfter = headers["retry-after"]?.toLongOrNull()?.takeIf { it in 1..86_400 }?.seconds
                val reset = windows.filter { it.utilization >= 1.0 }.mapNotNull { it.resetsAt }.minOrNull()
                repository.setCooldown(account.orgId, account.id, reset ?: (now + (retryAfter ?: DEFAULT_COOLDOWN)))
            }
            401 -> {
                // The access token was refused before its expiry: force a refresh next time.
                val envelope = repository.envelope(account.orgId, account.id) ?: return
                val stored = cipher.open(envelope, account.orgId, account.id) ?: return
                val expired = AccountCredentials(stored.accessToken, stored.refreshToken, Instant.fromEpochMilliseconds(0))
                repository.putEnvelope(account.orgId, account.id, cipher.seal(expired, account.orgId, account.id))
            }
        }
    }

    internal suspend fun release(accountId: Uuid) {
        mutex.withLock {
            val left = (inFlight[accountId] ?: 1) - 1
            if (left <= 0) inFlight.remove(accountId) else inFlight[accountId] = left
        }
    }

    /** Requests in flight on the account, on this replica. */
    suspend fun inFlight(accountId: Uuid): Int = mutex.withLock { inFlight[accountId] ?: 0 }

    private fun refused(reason: String, retryAt: Instant?) = LeaseResult.Refused(PoolRefusal(reason, retryAt))

    companion object {
        /** How long a session sticks to its account. */
        val AFFINITY: Duration = 1.hours

        /** Tokens are renewed this long before they expire. */
        val REFRESH_MARGIN: Duration = 5.minutes

        /** Cool-down after a 429 that names no reset. */
        val DEFAULT_COOLDOWN: Duration = 60.seconds

        private const val MAX_AFFINITY = 10_000

        /** The first instant of the UTC calendar month of [now] (civil calendar from epoch days, H. Hinnant). */
        fun monthStart(now: Instant): Instant {
            val days = now.epochSeconds.floorDiv(86_400L)
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val mp = (5 * dayOfYear + 2) / 153
            val dayOfMonth = dayOfYear - (153 * mp + 2) / 5 + 1
            return Instant.fromEpochSeconds((days - dayOfMonth + 1) * 86_400L)
        }
    }
}
