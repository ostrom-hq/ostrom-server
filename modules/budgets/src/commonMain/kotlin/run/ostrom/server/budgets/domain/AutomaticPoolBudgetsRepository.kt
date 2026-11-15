// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.gateway.domain.PacingSettingsRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The limits with the automatic pool size in force (TeamCode's `AutomaticPoolSize.effectiveBudget`): while
 * `autoPoolSize` is on and the value the job stored is fresh ([AutomaticPoolSize.MAX_AGE]), its amount is the org's
 * daily limit, with the action of the typed one (it stops when none is typed). The members', the workflows' and the
 * monthly limits are kept. Every reader of a call's limits reads through it, so the guard and the ledger's admission
 * both see it; the stored value is read at each call, never cached.
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
        val now = clock.now()
        // The setting is read only once a fresh value exists: most calls of an org without one read a single row.
        val automatic = store.read(orgId)
            ?.takeIf { AutomaticPoolSize.isFresh(it, now) && settings.get(orgId).autoPoolSize }
            ?: return set
        val daily = { limit: BudgetLimit -> limit.window == BudgetWindow.DAY }
        val action = set.org.firstOrNull(daily)?.action ?: BudgetAction.BLOCK
        return set.copy(org = set.org.filterNot(daily) + BudgetLimit(automatic.micros, BudgetWindow.DAY, action))
    }
}
