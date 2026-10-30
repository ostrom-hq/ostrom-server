// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/** Which limit a [SetBudgetUseCase] call sets. */
sealed interface BudgetTarget {
    /** The org's own limit. */
    data object Org : BudgetTarget

    /** The limit of every member without one of their own. */
    data object DefaultMember : BudgetTarget

    /**
     * One member's own limit.
     *
     * @property memberId The member.
     */
    data class Member(val memberId: Uuid) : BudgetTarget
}

/** Sets or removes one limit, leaving the others as they are (admins). */
interface SetBudgetUseCase {
    /**
     * Sets it.
     *
     * @param caller An owner or admin.
     * @param target Which limit.
     * @param limitMicros USD millionths per calendar month, or null to remove it (removing an absent one is a no-op).
     *
     * @return The org's budgets afterwards.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller does not administer the org.
     * @throws InvalidBudgetException When the limit is out of range.
     */
    suspend operator fun invoke(caller: Caller, target: BudgetTarget, limitMicros: Long?): BudgetOverview
}

/**
 * [SetBudgetUseCase] through the other budget use cases, which check, store and invalidate the cache.
 *
 * @param getBudgets Reads the current limits.
 * @param putBudgets Replaces the org and default member limits.
 * @param putMemberBudget Sets a member's own limit.
 * @param deleteMemberBudget Removes it.
 */
class SetBudgetUseCaseImpl(
    private val getBudgets: GetBudgetsUseCase,
    private val putBudgets: PutBudgetsUseCase,
    private val putMemberBudget: PutMemberBudgetUseCase,
    private val deleteMemberBudget: DeleteMemberBudgetUseCase,
) : SetBudgetUseCase {
    override suspend fun invoke(caller: Caller, target: BudgetTarget, limitMicros: Long?): BudgetOverview {
        caller.requireAdmin()
        return when (target) {
            BudgetTarget.Org -> putBudgets(caller, limitMicros, getBudgets(caller).settings.defaultMemberLimitMicros)
            BudgetTarget.DefaultMember -> putBudgets(caller, getBudgets(caller).settings.orgLimitMicros, limitMicros)
            is BudgetTarget.Member -> {
                if (limitMicros != null) {
                    putMemberBudget(caller, target.memberId, limitMicros)
                } else {
                    try {
                        deleteMemberBudget(caller, target.memberId)
                    } catch (_: MemberBudgetNotFoundException) {
                        // Already without a limit of their own: nothing to remove.
                    }
                }
                getBudgets(caller)
            }
        }
    }
}
