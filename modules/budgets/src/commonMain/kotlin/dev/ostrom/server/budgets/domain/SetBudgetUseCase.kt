// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import dev.ostrom.server.kernel.workflows.WorkflowDirectory
import dev.ostrom.server.secrets.domain.Caller
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Whose limits a [SetBudgetUseCase] call sets. */
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

    /**
     * One workflow's limit: what the model calls of the launches that play it may spend.
     *
     * @property workflowId The workflow, one of the org's.
     */
    data class Workflow(val workflowId: String) : BudgetTarget
}

/** Sets a subject's limits, leaving the other subjects' as they are (admins). */
interface SetBudgetUseCase {
    /**
     * Sets them.
     *
     * @param caller An owner or admin.
     * @param target Whose limits.
     * @param limits The subject's limits, at most one per window: they replace its limits whole, and an empty list
     *   removes them (removing absent ones is a no-op).
     *
     * @return The org's budgets afterwards.
     *
     * @throws dev.ostrom.server.secrets.domain.AdminRequiredException When the caller does not administer the org.
     * @throws InvalidBudgetException When a limit is out of range.
     * @throws BudgetWorkflowNotFoundException When a workflow budget's workflow is not one of the org's.
     */
    suspend operator fun invoke(caller: Caller, target: BudgetTarget, limits: List<BudgetLimit>): BudgetOverview
}

/**
 * [SetBudgetUseCase]: a subject's limits are written alone, never read and written back with another subject's, so
 * that two admins changing two subjects at once keep both changes.
 *
 * @param repository Stores the limits.
 * @param cache Forgets cached decisions when a limit changes.
 * @param clock The time of the change.
 * @param getBudgets Reads the limits afterwards.
 * @param workflows The org's workflows, which a workflow budget names; null, none is known.
 */
class SetBudgetUseCaseImpl(
    private val repository: BudgetsRepository,
    private val cache: BudgetDecisionCache,
    private val clock: Clock,
    private val getBudgets: GetBudgetsUseCase,
    private val workflows: WorkflowDirectory? = null,
) : SetBudgetUseCase {
    override suspend fun invoke(caller: Caller, target: BudgetTarget, limits: List<BudgetLimit>): BudgetOverview {
        caller.requireAdmin()
        limits.forEach { BudgetRules.requireLimit(it.micros) }
        // A limit is set only on one of the org's workflows; removing one never asks (its workflow may be gone).
        // Without a directory the server keeps no workflows, so none is found.
        if (target is BudgetTarget.Workflow && limits.isNotEmpty() && !knows(caller, target.workflowId)) {
            throw BudgetWorkflowNotFoundException()
        }
        repository.replaceLimits(caller.orgId, target, limits, clock.now())
        cache.invalidate(caller.orgId)
        return getBudgets(caller)
    }

    private suspend fun knows(caller: Caller, workflowId: String): Boolean =
        workflows?.name(caller.orgId, workflowId) != null
}
