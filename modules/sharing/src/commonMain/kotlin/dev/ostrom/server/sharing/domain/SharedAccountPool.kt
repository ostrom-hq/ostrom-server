// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An account taken for one request. The gateway sends [credentials] to the provider (never to a member), reports the
 * answer with [observe], and always calls [release].
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

    /**
     * Reports that the provider stopped answering on this account and the call was cut: the account rests
     * [SharedAccountPool.STALL_COOLDOWN], like a 429 that names no reset, so the next calls go to another account.
     */
    suspend fun stalled() = pool.observe(account, NO_ANSWER, emptyMap())

    /** Gives the slot back; idempotent, and done even when the request is being cancelled. */
    suspend fun release() {
        if (released) return
        released = true
        withContext(NonCancellable) { pool.release(account.id) }
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
 * used account wins, then the higher priority. An account whose tokens cannot be renewed is skipped for the next one.
 *
 * Concurrency and affinity are per replica, in memory.
 *
 * @param accounts The accounts.
 * @param windows Their observed quota windows.
 * @param tokens Their tokens.
 * @param parser Reads quota headers.
 * @param spend What each account served this month.
 * @param clock The time.
 */
class SharedAccountPool(
    private val accounts: SharedAccountRepository,
    private val windows: QuotaWindowRepository,
    private val tokens: AccountTokens,
    private val parser: QuotaHeaderParser,
    private val spend: AccountSpendReader,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<Uuid, Int>()
    private val affinity = mutableMapOf<String, Pair<Uuid, Instant>>()

    /**
     * Leases an account.
     *
     * @param orgId The org.
     * @param memberId The member asking.
     * @param provider The subscription the request needs.
     * @param sessionKey The agent session, for affinity, if known.
     */
    suspend fun lease(orgId: Uuid, memberId: Uuid, provider: AccountProvider, sessionKey: String?): LeaseResult {
        // The agent names its session: scoped to the member, so one org's or member's ids never meet another's.
        val request = Request(orgId, memberId, provider, sessionKey?.let { "$orgId:$memberId:$it" })
        val tried = mutableSetOf<Uuid>()
        // Each turn either answers or adds one account to `tried`: the loop ends within the org's account count.
        while (true) {
            attempt(request, tried)?.let { return it }
        }
    }

    private class Request(val orgId: Uuid, val memberId: Uuid, val provider: AccountProvider, val sessionKey: String?)

    /** The accounts that may serve now with their load, and when a blocked one frees up. */
    private class Candidates(val open: List<Pair<SharedAccount, Double>>, val retryAt: Instant?)

    /** One try: a lease, a refusal, or null when the chosen account could not renew its tokens (now in [tried]). */
    private suspend fun attempt(request: Request, tried: MutableSet<Uuid>): LeaseResult? {
        val now = clock.now()
        val usable = accounts.ready(request.orgId, request.provider).filter {
            it.id !in tried && it.limits.allowedMembers?.contains(request.memberId) != false
        }
        val candidates = candidates(request.orgId, usable, now)
        val chosen = take(candidates.open, request.sessionKey, now)
        return when {
            usable.isEmpty() -> refused("No shared ${request.provider.wire} account is available to you.", null)
            chosen == null && candidates.open.isEmpty() ->
                refused("Every shared ${request.provider.wire} account is at its limit.", candidates.retryAt)
            chosen == null ->
                refused(
                    "Every shared ${request.provider.wire} account is busy.",
                    candidates.retryAt ?: (now + BUSY_RETRY),
                )
            else -> serve(chosen, tried)
        }
    }

    private suspend fun candidates(orgId: Uuid, usable: List<SharedAccount>, now: Instant): Candidates {
        val observed = if (usable.isEmpty()) emptyMap() else windows.byAccount(orgId)
        val monthStart = monthStart(now)
        val open = mutableListOf<Pair<SharedAccount, Double>>()
        var retryAt: Instant? = null
        for (account in usable) {
            val current = observed[account.id].orEmpty().filter { it.isCurrent(now) && !it.window.startsWith("model:") }
            when (val standing = standing(account, current, now, monthStart)) {
                is Standing.Open -> open += account to standing.load
                is Standing.Closed -> retryAt = listOfNotNull(retryAt, standing.until).minOrNull()
            }
        }
        return Candidates(open, retryAt)
    }

    private sealed interface Standing {
        class Open(val load: Double) : Standing

        class Closed(val until: Instant?) : Standing
    }

    private suspend fun standing(
        account: SharedAccount,
        current: List<QuotaWindow>,
        now: Instant,
        monthStart: Instant,
    ): Standing {
        val cooling = account.cooldownUntil?.takeIf { it > now }
        val full = current.filter { it.utilization >= account.limits.utilizationCeiling }
        val budget = account.limits.monthlyBudgetMicros
        return when {
            cooling != null -> Standing.Closed(cooling)
            full.isNotEmpty() -> Standing.Closed(full.map(QuotaWindow::endsAt).max())
            budget != null && spend.spentMicros(account.id, monthStart) >= budget -> Standing.Closed(null)
            else -> Standing.Open(current.maxOfOrNull { it.utilization } ?: 0.0)
        }
    }

    /** Takes a slot on the session's account if it is open, otherwise on the least used one. */
    private suspend fun take(open: List<Pair<SharedAccount, Double>>, sessionKey: String?, now: Instant) =
        mutex.withLock {
            val free = open.filter { (account, _) -> (inFlight[account.id] ?: 0) < account.limits.maxConcurrent }
            val remembered = if (sessionKey == null) null else affinity[sessionKey]
            val sticky = if (remembered != null && remembered.second > now) remembered.first else null
            val pick = (free.firstOrNull { it.first.id == sticky } ?: free.minWithOrNull(ORDER))?.first
            if (pick != null) {
                inFlight[pick.id] = (inFlight[pick.id] ?: 0) + 1
                if (sessionKey != null) remember(sessionKey, pick.id, now)
            }
            pick
        }

    private fun remember(sessionKey: String, accountId: Uuid, now: Instant) {
        // Insertion order is use order (removed then put back): over the bound, the session used longest ago goes, in
        // constant time under the pool's lock, whoever floods the map with new sessions.
        affinity.remove(sessionKey)
        affinity[sessionKey] = accountId to now + AFFINITY
        if (affinity.size > MAX_AFFINITY) affinity.remove(affinity.keys.first())
    }

    private suspend fun serve(account: SharedAccount, tried: MutableSet<Uuid>): LeaseResult? {
        var credentials: AccountCredentials? = null
        try {
            credentials = tokens.fresh(account)
        } finally {
            if (credentials == null) withContext(NonCancellable) { release(account.id) }
        }
        tried += account.id
        return credentials?.let { LeaseResult.Leased(AccountLease(account, it, this)) }
    }

    internal suspend fun observe(account: SharedAccount, status: Int, headers: Map<String, String>) {
        val now = clock.now()
        val observed = parser.parse(account.provider, headers, now)
        if (observed.isNotEmpty()) windows.put(account.orgId, account.id, observed)
        when (status) {
            TOO_MANY_REQUESTS -> accounts.setCooldown(account.orgId, account.id, cooldownEnd(observed, headers, now))
            NO_ANSWER -> accounts.rest(account, now + STALL_COOLDOWN)
            UNAUTHORIZED -> tokens.expire(account)
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

    /** Timing and the calendar. */
    companion object {
        /** How long a session sticks to its account. */
        val AFFINITY: Duration = 1.hours

        /** Cool-down after a 429 that names no reset. */
        val DEFAULT_COOLDOWN: Duration = 60.seconds

        /** Rest after the provider stalled on an account and the gateway cut the call. */
        val STALL_COOLDOWN: Duration = 60.seconds

        /** When to try again while every open account is busy. */
        val BUSY_RETRY: Duration = 5.seconds

        /** Sessions remembered before the expired ones are dropped. */
        const val MAX_AFFINITY: Int = 10_000

        private const val TOO_MANY_REQUESTS = 429
        private const val UNAUTHORIZED = 401
        private val ORDER = compareBy<Pair<SharedAccount, Double>> { it.second }
            .thenByDescending { it.first.priority }
            .thenBy { it.first.id.toString() }

        /** The first instant of the UTC calendar month of [now]. */
        fun monthStart(now: Instant): Instant = CivilCalendar.monthStart(now)
    }
}

/**
 * The status [AccountLease.stalled] reports: no HTTP answer, the gateway cut the call. It goes through
 * [SharedAccountPool.observe] like a provider's status, so that the pool has one place that cools an account down.
 */
private const val NO_ANSWER = 0

/**
 * Cools an account down until [until], unless a longer cool-down (a 429's reset, set by another call meanwhile) is
 * already stored: a short rest never reopens an account early.
 */
private suspend fun SharedAccountRepository.rest(account: SharedAccount, until: Instant) {
    val stored = get(account.orgId, account.id)?.cooldownUntil
    setCooldown(account.orgId, account.id, stored?.takeIf { it > until } ?: until)
}

/** The longest `retry-after` believed: a day. */
private const val MAX_RETRY_AFTER_SECONDS = 86_400L

/** A cool-down ends when the full window resets, else after `retry-after`, else after the default. */
private fun cooldownEnd(observed: List<QuotaWindow>, headers: Map<String, String>, now: Instant): Instant {
    val reset = observed.filter { it.utilization >= 1.0 }.mapNotNull { it.resetsAt }.minOrNull()
    val retryAfter = headers["retry-after"]?.toLongOrNull()?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS }?.seconds
    return reset ?: (now + (retryAfter ?: SharedAccountPool.DEFAULT_COOLDOWN))
}

private fun refused(reason: String, retryAt: Instant?) = LeaseResult.Refused(PoolRefusal(reason, retryAt))
