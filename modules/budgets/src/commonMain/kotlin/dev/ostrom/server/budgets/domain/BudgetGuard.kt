// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.usage.domain.SpendReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * How [BudgetGuardImpl] caches.
 *
 * @property ttl How long a decision and a spend are reused.
 * @property maxEntries Entries kept per cache before expired ones are dropped.
 */
data class BudgetCacheSettings(val ttl: Duration = 5.seconds, val maxEntries: Int = 100_000)

/**
 * [BudgetGuard] on the limits and the ledger. Decisions are cached for the [BudgetCacheSettings.ttl] per (org, member),
 * and the org's spend as long per org, so that the gateway's hot path stays far under a millisecond; the price is that
 * spending may overshoot a limit by what is spent within that time. Crossing 80 % and 100 % of a limit publishes a
 * [BudgetAlert], once per limit value, threshold and month: a local memo avoids asking the database again, and the
 * database makes it once across replicas.
 *
 * @param repository The limits.
 * @param spend The ledger.
 * @param alerts Where alerts go.
 * @param clock The time.
 * @param logger Where a failed alert is reported.
 * @param cache How decisions and spends are cached.
 */
class BudgetGuardImpl(
    private val repository: BudgetsRepository,
    private val spend: SpendReader,
    private val alerts: BudgetAlerts,
    private val clock: Clock,
    private val logger: Logger,
    private val cache: BudgetCacheSettings = BudgetCacheSettings(),
) : BudgetGuard,
    BudgetDecisionCache {
    private data class MemberKey(val orgId: Uuid, val memberId: Uuid)

    private class Cached<T>(val value: T, val expiresAt: Instant)

    /** Whose limit: the org's (no member) or a member's. */
    private data class Subject(val orgId: Uuid, val memberId: Uuid?, val scope: BudgetScope)

    private data class AlertKey(val subject: Subject, val threshold: Int, val limit: Long, val period: Instant)

    private class Measured(val limit: Long, val spent: Long) {
        val exhausted: Boolean get() = spent >= limit
        val remaining: Long get() = (limit - spent).coerceAtLeast(0)
    }

    private val lock = Mutex()
    private val decisions = HashMap<MemberKey, Cached<BudgetDecision>>()
    private val orgSpends = HashMap<Uuid, Cached<Long>>()
    private val alerted = HashSet<AlertKey>()
    private var alertedPeriod: Instant? = null

    /**
     * Counts invalidations: a value read before one is not cached after it, or a check running while an admin changes
     * a limit would keep the old decision for the whole TTL.
     */
    private var generation = 0L

    override suspend fun check(orgId: Uuid, memberId: Uuid): BudgetDecision {
        val now = clock.now()
        val key = MemberKey(orgId, memberId)
        val (cached, readAt) = lock.withLock { decisions[key] to generation }
        if (cached != null && cached.expiresAt > now) return cached.value
        val decision = evaluate(orgId, memberId, now, readAt)
        lock.withLock {
            if (generation == readAt) decisions.putBounded(key, Cached(decision, now + cache.ttl), now)
        }
        return decision
    }

    override suspend fun invalidate(orgId: Uuid) {
        lock.withLock {
            generation++
            decisions.keys.removeAll { it.orgId == orgId }
            orgSpends.remove(orgId)
        }
    }

    private suspend fun evaluate(orgId: Uuid, memberId: Uuid, now: Instant, readAt: Long): BudgetDecision {
        val period = BudgetPeriod.of(now)
        val limits = repository.limits(orgId, memberId)
        val org = limits.orgLimitMicros?.let { Measured(it, orgSpent(orgId, period, now, readAt)) }
        val member = limits.memberLimitMicros?.let { Measured(it, spend.spentMicros(orgId, memberId, period.start)) }
        if (org != null) alertCrossings(Subject(orgId, null, BudgetScope.ORG), org, period, now)
        if (member != null) alertCrossings(Subject(orgId, memberId, BudgetScope.MEMBER), member, period, now)
        return when {
            org != null && org.exhausted -> BudgetDecision.Exhausted(BudgetScope.ORG, org.limit, period.end)
            member != null && member.exhausted -> BudgetDecision.Exhausted(BudgetScope.MEMBER, member.limit, period.end)
            else -> BudgetDecision.Allowed(listOfNotNull(org?.remaining, member?.remaining).minOrNull())
        }
    }

    private suspend fun orgSpent(orgId: Uuid, period: BudgetPeriod, now: Instant, readAt: Long): Long {
        val cached = lock.withLock { orgSpends[orgId] }
        if (cached != null && cached.expiresAt > now) return cached.value
        val spent = spend.spentMicros(orgId, null, period.start)
        lock.withLock {
            if (generation == readAt) orgSpends.putBounded(orgId, Cached(spent, now + cache.ttl), now)
        }
        return spent
    }

    private suspend fun alertCrossings(subject: Subject, measured: Measured, period: BudgetPeriod, now: Instant) {
        if (measured.limit <= 0) return
        for (threshold in BudgetRules.ALERT_THRESHOLDS) {
            // limit × threshold stays under 10^17: no overflow for any limit BudgetRules accepts.
            if (measured.spent < measured.limit * threshold / PERCENT) return
            val key = AlertKey(subject, threshold, measured.limit, period.start)
            if (!remember(key)) continue
            val alert = BudgetAlert(
                orgId = subject.orgId,
                memberId = subject.memberId,
                scope = subject.scope,
                thresholdPercent = threshold,
                limitMicros = measured.limit,
                spentMicros = measured.spent,
                periodStart = period.start,
            )
            // The decision does not depend on the alert: a failure is reported and a later check tries again.
            // Cancelled or failed: the memo must not claim an alert that was never sent.
            try {
                if (repository.markAlerted(alert, now)) publishOrUnmark(alert)
            } catch (error: CancellationException) {
                forget(key)
                throw error
            } catch (error: Exception) {
                forget(key)
                // The class only, never the message: PostgreSQL quotes row values in it.
                logger.warn(
                    "budget alert failed",
                    mapOf("org_id" to subject.orgId, "threshold" to threshold, "error" to error::class.simpleName),
                )
            }
        }
    }

    /** Publishes; if that fails or is cancelled, takes back the mark so that another check sends it. */
    private suspend fun publishOrUnmark(alert: BudgetAlert) {
        // No `finally` with a flag: the compiler copies a finally per exit, and the copy of the normal exit has a
        // branch no test can take.
        try {
            alerts.publish(alert)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { repository.unmarkAlerted(alert) }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { repository.unmarkAlerted(alert) }
            throw error
        }
    }

    private suspend fun forget(key: AlertKey) {
        withContext(NonCancellable) { lock.withLock { alerted.remove(key) } }
    }

    /** Adds the key to the memo of this month's alerts; false if it was there. A new month starts an empty memo. */
    private suspend fun remember(key: AlertKey): Boolean = lock.withLock {
        if (alertedPeriod != key.period) {
            alerted.clear()
            alertedPeriod = key.period
        }
        alerted.add(key)
    }

    private fun <K, V : Cached<*>> HashMap<K, V>.putBounded(key: K, value: V, now: Instant) {
        if (size >= cache.maxEntries) {
            entries.removeAll { it.value.expiresAt <= now }
            if (size >= cache.maxEntries) clear()
        }
        put(key, value)
    }
}

private const val PERCENT = 100
