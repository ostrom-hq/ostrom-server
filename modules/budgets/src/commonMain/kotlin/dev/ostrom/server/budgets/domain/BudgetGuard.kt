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
 * Decides whether a member may spend more today and this month. Bound in Koin by the budgets module; the gateway calls
 * it before each model call, so a decision is cached a few seconds and a cached check costs a map lookup.
 */
interface BudgetGuard {
    /**
     * Checks the org limits, then the member's (their own, or else the default member limits): only a limit that stops
     * refuses; a limit that warns is measured for its alerts and its audit line.
     *
     * @param orgId The org.
     * @param memberId The member.
     * @param workflowId The workflow the call's launch plays, or null: its limits are checked after the member's.
     *
     * @return The decision.
     */
    suspend fun check(orgId: Uuid, memberId: Uuid, workflowId: String? = null): BudgetDecision
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
 * and the org's spend as long per org and period, so that the gateway's hot path stays far under a millisecond; the
 * price is that spending may overshoot a limit by what is spent within that time. Crossing 80 % and 100 % of a limit
 * publishes a [BudgetAlert], once per limit value, action, threshold and period: a local memo avoids asking the
 * database again, and the database makes it once across replicas ([AuditedBudgetAlerts] also writes a limit that warns,
 * reached, to the organization's audit log, with the same once).
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
    private data class MemberKey(val orgId: Uuid, val memberId: Uuid, val workflowId: String?)

    private data class SpendKey(val orgId: Uuid, val period: BudgetPeriod)

    private class Cached<T>(val value: T, val expiresAt: Instant)

    /** Whose limit: the org's (no member), a member's, or a workflow's. */
    private data class Subject(
        val orgId: Uuid,
        val memberId: Uuid?,
        val scope: BudgetScope,
        val workflowId: String? = null,
    )

    private data class AlertKey(val subject: Subject, val threshold: Int, val limit: BudgetLimit, val period: Instant)

    private class Measured(val subject: Subject, val limit: BudgetLimit, val period: BudgetPeriod, val spent: Long) {
        val exhausted: Boolean get() = spent >= limit.micros
        val remaining: Long get() = (limit.micros - spent).coerceAtLeast(0)
    }

    private val lock = Mutex()
    private val decisions = HashMap<MemberKey, Cached<BudgetDecision>>()
    private val orgSpends = HashMap<SpendKey, Cached<Long>>()
    private val alerted = HashSet<AlertKey>()
    private var alertedMonth: Instant? = null

    /**
     * Counts invalidations: a value read before one is not cached after it, or a check running while an admin changes
     * a limit would keep the old decision for the whole TTL.
     */
    private var generation = 0L

    override suspend fun check(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetDecision {
        val now = clock.now()
        val key = MemberKey(orgId, memberId, workflowId)
        val (cached, readAt) = lock.withLock { decisions[key] to generation }
        if (cached != null && cached.expiresAt > now) return cached.value
        val decision = evaluate(key, now, readAt)
        lock.withLock {
            if (generation == readAt) decisions.putBounded(key, Cached(decision, now + cache.ttl), now)
        }
        return decision
    }

    override suspend fun invalidate(orgId: Uuid) {
        lock.withLock {
            generation++
            decisions.keys.removeAll { it.orgId == orgId }
            orgSpends.keys.removeAll { it.orgId == orgId }
        }
    }

    private suspend fun evaluate(key: MemberKey, now: Instant, readAt: Long): BudgetDecision {
        val (orgId, memberId, workflowId) = key
        val limits = repository.limits(orgId, memberId, workflowId)
        val org = Subject(orgId, null, BudgetScope.ORG)
        val member = Subject(orgId, memberId, BudgetScope.MEMBER)
        val measured = limits.org.map { limit ->
            val period = limit.window.periodOf(now)
            Measured(org, limit, period, orgSpent(orgId, period, now, readAt))
        } + limits.member.map { limit ->
            val period = limit.window.periodOf(now)
            Measured(member, limit, period, spend.spentMicros(orgId, memberId, period.start))
        } + workflowId?.let { measuredWorkflow(orgId, it, limits.workflow, now) }.orEmpty()
        val month = BudgetPeriod.of(now).start
        for (each in measured) alertCrossings(each, month, now)
        val stopping = measured.filter { it.limit.action == BudgetAction.BLOCK }
        val reached = stopping.firstOrNull { it.exhausted }
            ?: return BudgetDecision.Allowed(stopping.minOfOrNull { it.remaining })
        return BudgetDecision.Exhausted(
            scope = reached.subject.scope,
            limitMicros = reached.limit.micros,
            resetsAt = reached.period.end,
            window = reached.limit.window,
        )
    }

    /** A workflow's limits, measured on the spend of the calls of the launches that play it. */
    private suspend fun measuredWorkflow(orgId: Uuid, workflowId: String, limits: List<BudgetLimit>, now: Instant) =
        limits.map { limit ->
            val period = limit.window.periodOf(now)
            val subject = Subject(orgId, null, BudgetScope.WORKFLOW, workflowId)
            Measured(subject, limit, period, spend.workflowSpentMicros(orgId, workflowId, period.start))
        }

    private suspend fun orgSpent(orgId: Uuid, period: BudgetPeriod, now: Instant, readAt: Long): Long {
        val key = SpendKey(orgId, period)
        val cached = lock.withLock { orgSpends[key] }
        if (cached != null && cached.expiresAt > now) return cached.value
        val spent = spend.spentMicros(orgId, null, period.start)
        lock.withLock {
            if (generation == readAt) orgSpends.putBounded(key, Cached(spent, now + cache.ttl), now)
        }
        return spent
    }

    private suspend fun alertCrossings(measured: Measured, month: Instant, now: Instant) {
        val limit = measured.limit.micros
        // Nothing spent crosses nothing, even a limit of 0 or 80 % of one millionth (which rounds to 0); then the first
        // cent spent crosses every threshold of a limit of 0, which means "nothing".
        if (measured.spent <= 0) return
        val subject = measured.subject
        for (threshold in BudgetRules.ALERT_THRESHOLDS) {
            // limit × threshold stays under 10^17: no overflow for any limit BudgetRules accepts.
            if (measured.spent < limit * threshold / PERCENT) return
            val key = AlertKey(subject, threshold, measured.limit, measured.period.start)
            if (!remember(key, month)) continue
            val alert = BudgetAlert(
                orgId = subject.orgId,
                memberId = subject.memberId,
                scope = subject.scope,
                thresholdPercent = threshold,
                limitMicros = limit,
                spentMicros = measured.spent,
                periodStart = measured.period.start,
                window = measured.limit.window,
                action = measured.limit.action,
                workflowId = subject.workflowId,
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

    /**
     * Adds the key to the memo of this month's alerts, its days' included; false if it was there. A new month starts
     * an empty memo.
     */
    private suspend fun remember(key: AlertKey, month: Instant): Boolean = lock.withLock {
        if (alertedMonth != month) {
            alerted.clear()
            alertedMonth = month
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
