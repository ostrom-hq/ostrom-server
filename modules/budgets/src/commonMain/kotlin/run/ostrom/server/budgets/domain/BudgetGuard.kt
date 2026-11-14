// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.usage.domain.SpendReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Decides whether a member may spend more today and this month. Bound in Koin by the budgets module; the gateway calls
 * it before each model call. Nothing is cached in the process: every check reads the limits and the spend as they are
 * now, so a limit set on any replica applies to the next call on every one (TeamCode docs/replicas.md, « Policy and
 * account edits: database transactions; no process cache »).
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

/**
 * [BudgetGuard] on the limits and the ledger, read at every check (the spend from the spend of each day,
 * `usage_spend_days`). Crossing 80 % and 100 % of a limit
 * publishes a [BudgetAlert], once per limit value, action, threshold and period: a local memo avoids asking the
 * database again, and the database makes it once across replicas ([AuditedBudgetAlerts] also writes a limit that warns,
 * reached, to the organization's audit log, with the same once).
 *
 * @param repository The limits.
 * @param spend The ledger.
 * @param alerts Where alerts go.
 * @param clock The time.
 * @param logger Where a failed alert is reported.
 */
class BudgetGuardImpl(
    private val repository: BudgetsRepository,
    private val spend: SpendReader,
    private val alerts: BudgetAlerts,
    private val clock: Clock,
    private val logger: Logger,
) : BudgetGuard {
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

    // The alerts this replica already saw marked: they spare a write, never decide (the database marks each once).
    private val lock = Mutex()
    private val alerted = HashSet<AlertKey>()
    private var alertedMonth: Instant? = null

    override suspend fun check(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetDecision {
        val now = clock.now()
        val limits = repository.limits(orgId, memberId, workflowId)
        val org = Subject(orgId, null, BudgetScope.ORG)
        val member = Subject(orgId, memberId, BudgetScope.MEMBER)
        val measured = limits.org.map { limit ->
            val period = limit.window.periodOf(now)
            Measured(org, limit, period, spend.spentMicros(orgId, null, period.start))
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
    private suspend fun measuredWorkflow(
        orgId: Uuid,
        workflowId: String,
        limits: List<BudgetLimit>,
        now: Instant,
    ): List<Measured> {
        val subject = Subject(orgId, null, BudgetScope.WORKFLOW, workflowId)
        return limits.map { limit ->
            val period = limit.window.periodOf(now)
            Measured(subject, limit, period, spend.workflowSpentMicros(orgId, workflowId, period.start))
        }
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
}

private const val PERCENT = 100
