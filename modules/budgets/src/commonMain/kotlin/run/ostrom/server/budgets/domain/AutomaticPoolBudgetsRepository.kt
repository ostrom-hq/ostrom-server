// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.gateway.domain.PacingSettingsRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The limits with the automatic pool size in force (TeamCode's `AutomaticPoolSize.effectiveBudget`): while
 * `autoPoolSize` is on and the value the job stored is fresh ([AutomaticPoolSize.MAX_AGE]), its amount is the org's
 * daily limit, with the action of the typed one (it stops when none is typed). The members', the workflows' and the
 * monthly limits are kept. Every reader of a call's limits reads through it, so the guard and the ledger's admission
 * both see it; the stored value is read at each call, never cached. Its alerts are sent once per threshold and day,
 * whatever the amount the passes move ([AUTOMATIC_MARK]), and once reached it is listed by [exceeded] at the amount in
 * force now, in place of the typed daily limit it replaces.
 *
 * @param limits The limits as set.
 * @param store The stored automatic value.
 * @param settings The org's capacity controller settings.
 * @param clock The time.
 */
class AutomaticPoolBudgetsRepository(
    private val limits: BudgetsRepository,
    private val store: PoolSizeRepository,
    private val settings: PacingSettingsRepository,
    private val clock: Clock,
) : BudgetsRepository by limits {
    override suspend fun limits(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetLimits {
        val set = limits.limits(orgId, memberId, workflowId)
        val automatic = automatic(orgId) ?: return set
        val daily = { limit: BudgetLimit -> limit.window == BudgetWindow.DAY }
        val typed = set.org.firstOrNull(daily)
        val action = if (typed == null) BudgetAction.BLOCK else typed.action
        return set.copy(org = set.org.filterNot(daily) + BudgetLimit(automatic.micros, BudgetWindow.DAY, action))
    }

    override suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean = limits.markAlerted(marked(alert), at)

    override suspend fun unmarkAlerted(alert: BudgetAlert) = limits.unmarkAlerted(marked(alert))

    override suspend fun exceeded(
        orgId: Uuid,
        memberId: Uuid?,
        day: Instant,
        month: Instant,
        max: Int,
    ): List<ExceededBudget> {
        // The org's daily rows read here may be dropped below (at most the typed limit's and one marked per action).
        val reached = limits.exceeded(orgId, memberId, day, month, max + ORG_DAILY_ROWS)
        val orgDaily = { row: ExceededBudget -> row.scope == BudgetScope.ORG && row.limit.window == BudgetWindow.DAY }
        if (reached.none(orgDaily)) return reached.take(max)
        val automatic = automatic(orgId)
        val typed = limits.settings(orgId, 0).org.firstOrNull { it.window == BudgetWindow.DAY }
        val action = if (typed == null) BudgetAction.BLOCK else typed.action
        // While the automatic value is in force, the typed daily limit it replaces is not the one that was reached,
        // and the automatic one is listed at today's amount; out of force, only the typed one is.
        return reached.mapNotNull { row ->
            when {
                !orgDaily(row) -> row
                automatic == null -> row.takeUnless { it.limit.micros == AUTOMATIC_MARK }
                row.limit.micros == AUTOMATIC_MARK && row.limit.action == action ->
                    row.copy(limit = BudgetLimit(automatic.micros, BudgetWindow.DAY, action))
                else -> null
            }
        }.take(max)
    }

    /**
     * An alert of the automatic daily limit, marked under [AUTOMATIC_MARK] instead of its amount: the amount moves at
     * every pass, and an alert keyed on it would be sent again each time (once per threshold and day, as a typed one).
     */
    private suspend fun marked(alert: BudgetAlert): BudgetAlert =
        if (alert.scope == BudgetScope.ORG && alert.window == BudgetWindow.DAY && automatic(alert.orgId) != null) {
            alert.copy(limitMicros = AUTOMATIC_MARK)
        } else {
            alert
        }

    // The setting is read only once a fresh value exists: most calls of an org without one read a single row.
    private suspend fun automatic(orgId: Uuid): AutomaticDaily? = store.read(orgId)
        ?.takeIf { AutomaticPoolSize.isFresh(it, clock.now()) && settings.get(orgId).autoPoolSize }

    /** The marks of the automatic limit's alerts. */
    companion object {
        /** The `limit_micros` an alert of the automatic daily limit is marked under (no typed limit is negative). */
        const val AUTOMATIC_MARK: Long = -1

        private const val ORG_DAILY_ROWS = 3
    }
}
