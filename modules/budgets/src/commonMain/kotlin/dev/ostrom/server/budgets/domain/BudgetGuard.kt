// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.usage.domain.SpendReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Decides whether a member may spend more this month. Bound in Koin by the budgets module; the gateway calls it before
 * each model call, so a decision is cached a few seconds and a cached check costs a map lookup.
 */
interface BudgetGuard {
    /**
     * Checks the org limit, then the member's (their own, or else the default member limit).
     *
     * @param orgId The org.
     * @param memberId The member.
     *
     * @return The decision.
     */
    suspend fun check(orgId: Uuid, memberId: Uuid): BudgetDecision
}

/** Forgets cached decisions when an org's limits change, so this replica applies them at once. */
interface BudgetDecisionCache {
    /**
     * Forgets the org's cached decisions and spend.
     *
     * @param orgId The org.
     */
    suspend fun invalidate(orgId: Uuid)
}

/**
 * [BudgetGuard] on the limits and the ledger. Decisions are cached [cacheTtl] per (org, member), and the org's spend
 * [cacheTtl] per org, so that the gateway's hot path stays far under a millisecond; the price is that spending may
 * overshoot a limit by what is spent within [cacheTtl]. Crossing 80 % and 100 % of a limit publishes a [BudgetAlert],
 * once per limit, threshold and month: a local memo avoids asking the database again, and the database makes it once
 * across replicas.
 *
 * @param repository The limits.
 * @param spend The ledger.
 * @param alerts Where alerts go.
 * @param clock The time.
 * @param logger Where a failed alert is reported.
 * @param cacheTtl How long a decision and a spend are reused.
 * @param maxCached Entries kept per cache before expired ones are dropped.
 */
class BudgetGuardImpl(
    private val repository: BudgetsRepository,
    private val spend: SpendReader,
    private val alerts: BudgetAlerts,
    private val clock: Clock,
    private val logger: Logger,
    private val cacheTtl: Duration = 5.seconds,
    private val maxCached: Int = 100_000,
) : BudgetGuard, BudgetDecisionCache {
    private data class MemberKey(val orgId: Uuid, val memberId: Uuid)

    private class Cached<T>(val value: T, val expiresAt: Instant)

    private data class AlertKey(val orgId: Uuid, val memberId: Uuid?, val scope: BudgetScope, val threshold: Int, val period: Instant)

    private class Measured(val limit: Long, val spent: Long) {
        val exhausted: Boolean get() = spent >= limit
        val remaining: Long get() = (limit - spent).coerceAtLeast(0)
    }

    private val lock = Mutex()
    private val decisions = HashMap<MemberKey, Cached<BudgetDecision>>()
    private val orgSpends = HashMap<Uuid, Cached<Long>>()
    private val alerted = HashSet<AlertKey>()

    override suspend fun check(orgId: Uuid, memberId: Uuid): BudgetDecision {
        val now = clock.now()
        val key = MemberKey(orgId, memberId)
        val cached = lock.withLock { decisions[key] }
        if (cached != null && cached.expiresAt > now) return cached.value
        val decision = evaluate(orgId, memberId, now)
        lock.withLock { decisions.putBounded(key, Cached(decision, now + cacheTtl), now) }
        return decision
    }

    override suspend fun invalidate(orgId: Uuid) {
        lock.withLock {
            decisions.keys.removeAll { it.orgId == orgId }
            orgSpends.remove(orgId)
        }
    }

    private suspend fun evaluate(orgId: Uuid, memberId: Uuid, now: Instant): BudgetDecision {
        val period = BudgetPeriod.of(now)
        val limits = repository.limits(orgId, memberId)
        val org = limits.orgLimitMicros?.let { Measured(it, orgSpent(orgId, period, now)) }
        val member = limits.memberLimitMicros?.let { Measured(it, spend.spentMicros(orgId, memberId, period.start)) }
        if (org != null) alertCrossings(orgId, null, BudgetScope.ORG, org, period, now)
        if (member != null) alertCrossings(orgId, memberId, BudgetScope.MEMBER, member, period, now)
        return when {
            org != null && org.exhausted -> BudgetDecision.Exhausted(BudgetScope.ORG, org.limit, period.end)
            member != null && member.exhausted -> BudgetDecision.Exhausted(BudgetScope.MEMBER, member.limit, period.end)
            else -> BudgetDecision.Allowed(listOfNotNull(org?.remaining, member?.remaining).minOrNull())
        }
    }

    private suspend fun orgSpent(orgId: Uuid, period: BudgetPeriod, now: Instant): Long {
        val cached = lock.withLock { orgSpends[orgId] }
        if (cached != null && cached.expiresAt > now) return cached.value
        val spent = spend.spentMicros(orgId, null, period.start)
        lock.withLock { orgSpends.putBounded(orgId, Cached(spent, now + cacheTtl), now) }
        return spent
    }

    private suspend fun alertCrossings(
        orgId: Uuid,
        memberId: Uuid?,
        scope: BudgetScope,
        measured: Measured,
        period: BudgetPeriod,
        now: Instant,
    ) {
        if (measured.limit <= 0) return
        for (threshold in BudgetRules.ALERT_THRESHOLDS) {
            // limit × threshold stays under 10^17: no overflow for any limit BudgetRules accepts.
            if (measured.spent < measured.limit * threshold / 100) return
            val key = AlertKey(orgId, memberId, scope, threshold, period.start)
            if (!remember(key)) continue
            val alert = BudgetAlert(orgId, memberId, scope, threshold, measured.limit, measured.spent, period.start)
            try {
                if (repository.markAlerted(alert, now)) alerts.publish(alert)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The decision does not depend on the alert: report it and let a later check try again.
                lock.withLock { alerted.remove(key) }
                logger.warn("budget alert failed", mapOf("org_id" to orgId, "threshold" to threshold), error)
            }
        }
    }

    /** Adds the key to the memo of this month's alerts; false if it was there. Older months are forgotten. */
    private suspend fun remember(key: AlertKey): Boolean = lock.withLock {
        alerted.removeAll { it.period != key.period }
        alerted.add(key)
    }

    private fun <K, V : Cached<*>> HashMap<K, V>.putBounded(key: K, value: V, now: Instant) {
        if (size >= maxCached) {
            entries.removeAll { it.value.expiresAt <= now }
            if (size >= maxCached) clear()
        }
        put(key, value)
    }
}
