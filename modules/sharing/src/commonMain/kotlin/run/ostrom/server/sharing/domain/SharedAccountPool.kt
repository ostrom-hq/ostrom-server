// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An account taken for one request. The gateway sends [credentials] to the provider (never to a member) within
 * [held], reports the answer with [observe], and always calls [release].
 *
 * @property account The account.
 * @property credentials Fresh tokens.
 */
class AccountLease internal constructor(
    val account: SharedAccount,
    val credentials: AccountCredentials,
    private val pool: SharedAccountPool,
    private val slot: AccountSlot,
) {
    private var released = false

    /**
     * Reports the provider's answer: quota windows from the headers, a cool-down on 429, a forced refresh on 401.
     *
     * @param status The HTTP status.
     * @param headers The response headers, names in lower case.
     */
    suspend fun observe(status: Int, headers: Map<String, String>) = pool.observe(this, status, headers)

    /**
     * Reports that the provider stopped answering on this account and the call was cut: the account rests
     * [SharedAccountPool.STALL_COOLDOWN], like a 429 that names no reset, so the next calls go to another account.
     */
    suspend fun stalled() = pool.observe(this, NO_ANSWER, emptyMap())

    /**
     * Runs [block], the request to the provider, renewing the slot every [AccountSlotRepository.SLOT_RENEWAL] while it
     * runs: a stream longer than the slot's lease keeps counting. A renewal that fails is left to the next one.
     */
    suspend fun <T> held(block: suspend () -> T): T = coroutineScope {
        val renewing = launch {
            while (true) {
                delay(AccountSlotRepository.SLOT_RENEWAL)
                pool.renew(account.orgId, slot)
            }
        }
        block().also { renewing.cancel() }
    }

    /** Gives the slot back; idempotent, and done even when the request is being cancelled. */
    suspend fun release() {
        if (released) return
        released = true
        withContext(NonCancellable) { pool.release(account.orgId, slot) }
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
 * under its own limits (concurrency, utilization ceiling of every current window, monthly and daily budgets) and
 * allowed for the member. Among those, a session sticks to its account (the provider's prompt cache lives there),
 * otherwise the least used account wins, then the higher priority. An account whose tokens cannot be renewed is
 * skipped for the next one.
 *
 * Concurrency and affinity are in the database ([AccountSlotRepository]), shared by every replica: a slot is taken
 * under the candidates' row locks, so two replicas never fill an account past its limit together, and a session finds
 * its account whichever replica it reaches.
 *
 * @param accounts The accounts.
 * @param windows Their observed quota windows.
 * @param tokens Their tokens.
 * @param slots The requests in flight and the sessions' accounts.
 * @param parser Reads quota headers.
 * @param spend What each account served this month.
 * @param clock The time.
 * @param groups The accounts lent to the member through their groups: an account that does not name the member serves
 *   them when one of their groups, where they are not paused, holds it.
 */
@Suppress("LongParameterList") // One port per owner: accounts, windows, tokens, slots, headers, spend, time, groups.
class SharedAccountPool(
    private val accounts: SharedAccountRepository,
    private val windows: QuotaWindowRepository,
    private val tokens: AccountTokens,
    private val slots: AccountSlotRepository,
    private val parser: QuotaHeaderParser,
    private val spend: AccountSpendReader,
    private val clock: Clock,
    private val groups: AccountGroups = NoAccountGroups,
) {
    /**
     * Leases an account.
     *
     * @param orgId The org.
     * @param memberId The member asking.
     * @param provider The subscription the request needs.
     * @param sessionKey The agent session, for affinity, if known.
     */
    suspend fun lease(orgId: Uuid, memberId: Uuid, provider: AccountProvider, sessionKey: String?): LeaseResult {
        // The agent names its session; the repository scopes it to the org and the member.
        val request = Request(orgId, memberId, provider, sessionKey)
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
        val ready = accounts.ready(request.orgId, request.provider).filter { it.id !in tried }
        val usable = groups.allowed(request.orgId, request.memberId, ready, now)
        val candidates = candidates(request.orgId, usable, now)
        // Not cancellable: a slot committed must reach serve(), which gives it back if the request is cancelled.
        val slot = candidates.open.takeIf { it.isNotEmpty() }
            ?.let { open -> withContext(NonCancellable) { take(request, open) } }
        return when {
            usable.isEmpty() -> refused("No shared ${request.provider.wire} account is available to you.", null)
            candidates.open.isEmpty() ->
                refused("Every shared ${request.provider.wire} account is at its limit.", candidates.retryAt)
            slot == null ->
                refused(
                    "Every shared ${request.provider.wire} account is busy.",
                    candidates.retryAt ?: (now + BUSY_RETRY),
                )
            else -> serve(candidates.open.first { it.first.id == slot.accountId }.first, slot, tried)
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
        val daily = account.limits.dailyBudgetMicros
        val dayStart = CivilCalendar.dayStart(now)
        return when {
            cooling != null -> Standing.Closed(cooling)
            full.isNotEmpty() -> Standing.Closed(full.map(QuotaWindow::endsAt).max())
            budget != null && spend.spentMicros(account.id, monthStart) >= budget -> Standing.Closed(null)
            // Its daily allocation spent, the account opens again with the next UTC day.
            daily != null && spend.spentMicros(account.id, dayStart) >= daily -> Standing.Closed(dayStart + 1.days)
            else -> Standing.Open(current.maxOfOrNull { it.utilization } ?: 0.0)
        }
    }

    /** Takes a slot on the session's account if it has room, otherwise on the least used one that has. */
    private suspend fun take(request: Request, open: List<Pair<SharedAccount, Double>>): AccountSlot? {
        val ordered = open.sortedWith(ORDER).map { it.first }
        return slots.take(request.orgId, request.memberId, request.sessionKey, ordered) { view ->
            val free = ordered.filter { it.id in view.serving && (view.active[it.id] ?: 0) < it.limits.maxConcurrent }
            free.firstOrNull { it.id == view.remembered } ?: free.firstOrNull()
        }
    }

    private suspend fun serve(account: SharedAccount, slot: AccountSlot, tried: MutableSet<Uuid>): LeaseResult? {
        var credentials: AccountCredentials? = null
        try {
            credentials = tokens.fresh(account)
        } finally {
            if (credentials == null) withContext(NonCancellable) { release(account.orgId, slot) }
        }
        tried += account.id
        return credentials?.let { LeaseResult.Leased(AccountLease(account, it, this, slot)) }
    }

    internal suspend fun observe(lease: AccountLease, status: Int, headers: Map<String, String>) {
        val account = lease.account
        val now = clock.now()
        val observed = parser.parse(account.provider, headers, now)
        if (observed.isNotEmpty()) windows.put(account.orgId, account.id, observed)
        when (status) {
            TOO_MANY_REQUESTS -> accounts.setCooldown(account.orgId, account.id, cooldownEnd(observed, headers, now))
            NO_ANSWER -> accounts.rest(account, now + STALL_COOLDOWN)
            UNAUTHORIZED -> tokens.expire(account, lease.credentials)
        }
    }

    // A slot the database could not take back frees itself at the end of its lease: the request's answer stands.
    internal suspend fun release(orgId: Uuid, slot: AccountSlot) = quietly { slots.release(orgId, slot) }

    internal suspend fun renew(orgId: Uuid, slot: AccountSlot) = quietly { slots.renew(orgId, slot) }

    private suspend fun quietly(write: suspend () -> Unit) {
        try {
            write()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Left to the slot's lease.
        }
    }

    /** Requests in flight on each of the org's accounts, on every replica (absent: none). */
    suspend fun inFlight(orgId: Uuid): Map<Uuid, Int> = slots.active(orgId)

    /** Timing and the calendar. */
    companion object {
        /** Cool-down after a 429 that names no reset. */
        val DEFAULT_COOLDOWN: Duration = 60.seconds

        /** Rest after the provider stalled on an account and the gateway cut the call. */
        val STALL_COOLDOWN: Duration = 60.seconds

        /** When to try again while every open account is busy. */
        val BUSY_RETRY: Duration = 5.seconds

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

/**
 * The accounts of [ready] that [memberId] may use: lent to the whole org, to them by name, or to a group they are in
 * and not paused in at [now]. The groups are read only when an account names others than the member.
 */
private suspend fun AccountGroups.allowed(
    orgId: Uuid,
    memberId: Uuid,
    ready: List<SharedAccount>,
    now: Instant,
): List<SharedAccount> {
    val named = ready.filter { it.limits.allowedMembers?.contains(memberId) != false }
    if (named.size == ready.size) return ready
    val lent = lentTo(orgId, memberId, now)
    return ready.filter { it in named || it.id in lent }
}
