// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.usage.domain.SpendReader
import kotlin.time.Clock
import kotlin.time.Instant

/** The bounds of budgets. */
object BudgetRules {
    /** The largest limit: one billion US dollars, in millionths. */
    const val MAX_LIMIT_MICROS: Long = 1_000_000_000_000_000L

    /** Member overrides listed at most. */
    const val MAX_OVERRIDES: Int = 1000

    /** The alert thresholds, in percent of a limit, ascending. */
    val ALERT_THRESHOLDS: List<Int> = listOf(80, 100)

    /**
     * Refuses a limit out of range.
     *
     * @throws InvalidBudgetException When it is negative or too large.
     */
    fun requireLimit(limitMicros: Long) {
        if (limitMicros !in 0..MAX_LIMIT_MICROS) throw InvalidBudgetException()
    }
}

/**
 * An org's limits and this month's spend, for its admins.
 *
 * @property settings The limits.
 * @property orgSpentMicros What the org spent this month.
 * @property period This month.
 */
data class BudgetOverview(val settings: BudgetSettings, val orgSpentMicros: Long, val period: BudgetPeriod)

/** Reads an org's budgets. */
interface GetBudgetsUseCase {
    /**
     * Reads them.
     *
     * @param caller An admin of the org.
     *
     * @return The limits and the month's spend.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller is not an admin.
     */
    suspend operator fun invoke(caller: Caller): BudgetOverview
}

/**
 * [GetBudgetsUseCase] on the repository and the ledger.
 *
 * @param repository The limits.
 * @param spend The ledger.
 * @param clock The time.
 */
class GetBudgetsUseCaseImpl(
    private val repository: BudgetsRepository,
    private val spend: SpendReader,
    private val clock: Clock,
) : GetBudgetsUseCase {
    override suspend fun invoke(caller: Caller): BudgetOverview {
        caller.requireAdmin()
        val period = BudgetPeriod.of(clock.now())
        val settings = repository.settings(caller.orgId, BudgetRules.MAX_OVERRIDES)
        return BudgetOverview(settings, spend.spentMicros(caller.orgId, null, period.start), period)
    }
}

/**
 * A member's own view of their budget this month.
 *
 * @property limitMicros Their limit (their own, or else the default), or null when none applies.
 * @property spentMicros What they spent this month.
 * @property remainingMicros What is left of their limit, never negative; null without a limit.
 * @property resetsAt The start of next month.
 * @property decision What the gateway would decide now, the org limit included.
 */
data class MyBudget(
    val limitMicros: Long?,
    val spentMicros: Long,
    val remainingMicros: Long?,
    val resetsAt: Instant,
    val decision: BudgetDecision,
)

/** Reads the caller's own budget. */
interface GetMyBudgetUseCase {
    /**
     * Reads it; any member may.
     *
     * @param caller The member.
     *
     * @return Their budget.
     */
    suspend operator fun invoke(caller: Caller): MyBudget
}

/**
 * [GetMyBudgetUseCase] on the repository, the ledger and the guard.
 *
 * @param repository The limits.
 * @param spend The ledger.
 * @param guard The decision.
 * @param clock The time.
 */
class GetMyBudgetUseCaseImpl(
    private val repository: BudgetsRepository,
    private val spend: SpendReader,
    private val guard: BudgetGuard,
    private val clock: Clock,
) : GetMyBudgetUseCase {
    override suspend fun invoke(caller: Caller): MyBudget {
        val period = BudgetPeriod.of(clock.now())
        val limit = repository.limits(caller.orgId, caller.memberId).memberLimitMicros
        val spent = spend.spentMicros(caller.orgId, caller.memberId, period.start)
        return MyBudget(
            limitMicros = limit,
            spentMicros = spent,
            remainingMicros = limit?.let { (it - spent).coerceAtLeast(0) },
            resetsAt = period.end,
            decision = guard.check(caller.orgId, caller.memberId),
        )
    }
}
