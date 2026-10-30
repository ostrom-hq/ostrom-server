// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
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
 * [SetBudgetUseCase]: an org-wide limit is written alone, never read and written back with the other, so that two
 * admins changing the two limits at once keep both changes; a member's limit goes through its own use cases.
 *
 * @param repository Stores the org-wide limits.
 * @param cache Forgets cached decisions when a limit changes.
 * @param clock The time of the change.
 * @param getBudgets Reads the limits afterwards.
 * @param putMemberBudget Sets a member's own limit.
 * @param deleteMemberBudget Removes it.
 */
class SetBudgetUseCaseImpl(
    private val repository: BudgetsRepository,
    private val cache: BudgetDecisionCache,
    private val clock: Clock,
    private val getBudgets: GetBudgetsUseCase,
    private val putMemberBudget: PutMemberBudgetUseCase,
    private val deleteMemberBudget: DeleteMemberBudgetUseCase,
) : SetBudgetUseCase {
    override suspend fun invoke(caller: Caller, target: BudgetTarget, limitMicros: Long?): BudgetOverview {
        caller.requireAdmin()
        when (target) {
            BudgetTarget.Org -> putShared(caller, SharedLimit.ORG, limitMicros)
            BudgetTarget.DefaultMember -> putShared(caller, SharedLimit.DEFAULT_MEMBER, limitMicros)
            is BudgetTarget.Member -> if (limitMicros != null) {
                putMemberBudget(caller, target.memberId, limitMicros)
            } else {
                try {
                    deleteMemberBudget(caller, target.memberId)
                } catch (_: MemberBudgetNotFoundException) {
                    // Already without a limit of their own: nothing to remove.
                }
            }
        }
        return getBudgets(caller)
    }

    private suspend fun putShared(caller: Caller, limit: SharedLimit, limitMicros: Long?) {
        BudgetRules.requireLimit(limitMicros)
        repository.putSharedLimit(caller.orgId, limit, limitMicros, clock.now())
        cache.invalidate(caller.orgId)
    }
}
