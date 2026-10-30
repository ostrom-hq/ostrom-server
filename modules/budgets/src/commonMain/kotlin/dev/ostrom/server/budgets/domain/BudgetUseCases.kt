// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.usage.domain.SpendReader
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The bounds of budgets. */
object BudgetRules {
    /** The largest limit: one billion US dollars, in millionths. */
    const val MAX_LIMIT_MICROS: Long = 1_000_000_000_000_000L

    /** Member overrides listed at most. */
    const val MAX_OVERRIDES: Int = 1000

    /** The alert thresholds, in percent of a limit, ascending. */
    val ALERT_THRESHOLDS: List<Int> = listOf(80, 100)

    /**
     * Refuses a limit out of range; null (no limit) is accepted.
     *
     * @throws InvalidBudgetException When it is negative or too large.
     */
    fun requireLimit(limitMicros: Long?) {
        if (limitMicros != null && limitMicros !in 0..MAX_LIMIT_MICROS) throw InvalidBudgetException()
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

/** Replaces an org's limit and default member limit. */
interface PutBudgetsUseCase {
    /**
     * Replaces both; a null removes the limit.
     *
     * @param caller An admin of the org.
     * @param orgLimitMicros The org limit.
     * @param defaultMemberLimitMicros The default member limit.
     *
     * @return The budgets as they are now.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller is not an admin.
     * @throws InvalidBudgetException When a limit is out of range.
     */
    suspend operator fun invoke(caller: Caller, orgLimitMicros: Long?, defaultMemberLimitMicros: Long?): BudgetOverview
}

/**
 * [PutBudgetsUseCase] on the repository.
 *
 * @param repository The limits.
 * @param cache The guard's cache, invalidated after the change.
 * @param getBudgets Reads the result.
 * @param clock The time.
 */
class PutBudgetsUseCaseImpl(
    private val repository: BudgetsRepository,
    private val cache: BudgetDecisionCache,
    private val getBudgets: GetBudgetsUseCase,
    private val clock: Clock,
) : PutBudgetsUseCase {
    override suspend fun invoke(
        caller: Caller,
        orgLimitMicros: Long?,
        defaultMemberLimitMicros: Long?,
    ): BudgetOverview {
        caller.requireAdmin()
        BudgetRules.requireLimit(orgLimitMicros)
        BudgetRules.requireLimit(defaultMemberLimitMicros)
        repository.putOrgLimits(caller.orgId, orgLimitMicros, defaultMemberLimitMicros, clock.now())
        cache.invalidate(caller.orgId)
        return getBudgets(caller)
    }
}

/** Sets one member's own limit. */
interface PutMemberBudgetUseCase {
    /**
     * Sets it.
     *
     * @param caller An admin of the org.
     * @param memberId The member.
     * @param limitMicros The limit.
     *
     * @return The override.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller is not an admin.
     * @throws InvalidBudgetException When the limit is out of range.
     */
    suspend operator fun invoke(caller: Caller, memberId: Uuid, limitMicros: Long): MemberBudget
}

/**
 * [PutMemberBudgetUseCase] on the repository.
 *
 * @param repository The limits.
 * @param cache The guard's cache.
 * @param clock The time.
 */
class PutMemberBudgetUseCaseImpl(
    private val repository: BudgetsRepository,
    private val cache: BudgetDecisionCache,
    private val clock: Clock,
) : PutMemberBudgetUseCase {
    override suspend fun invoke(caller: Caller, memberId: Uuid, limitMicros: Long): MemberBudget {
        caller.requireAdmin()
        BudgetRules.requireLimit(limitMicros)
        val budget = repository.putMemberLimit(caller.orgId, memberId, limitMicros, clock.now())
        cache.invalidate(caller.orgId)
        return budget
    }
}

/** Removes one member's own limit. */
interface DeleteMemberBudgetUseCase {
    /**
     * Removes it: the default member limit applies again.
     *
     * @param caller An admin of the org.
     * @param memberId The member.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller is not an admin.
     * @throws MemberBudgetNotFoundException When the member had none.
     */
    suspend operator fun invoke(caller: Caller, memberId: Uuid)
}

/**
 * [DeleteMemberBudgetUseCase] on the repository.
 *
 * @param repository The limits.
 * @param cache The guard's cache.
 */
class DeleteMemberBudgetUseCaseImpl(
    private val repository: BudgetsRepository,
    private val cache: BudgetDecisionCache,
) : DeleteMemberBudgetUseCase {
    override suspend fun invoke(caller: Caller, memberId: Uuid) {
        caller.requireAdmin()
        if (!repository.deleteMemberLimit(caller.orgId, memberId)) throw MemberBudgetNotFoundException()
        cache.invalidate(caller.orgId)
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
