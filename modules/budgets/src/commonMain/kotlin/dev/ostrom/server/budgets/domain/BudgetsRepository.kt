// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One of a member's own limits, which together replace the default member limits.
 *
 * @property memberId The member.
 * @property limitMicros The limit, USD millionths.
 * @property updatedAt Its last change.
 * @property window Per day or per month.
 * @property action Stops or warns.
 */
data class MemberBudget(
    val memberId: Uuid,
    val limitMicros: Long,
    val updatedAt: Instant,
    val window: BudgetWindow = BudgetWindow.MONTH,
    val action: BudgetAction = BudgetAction.BLOCK,
) {
    /** The limit. */
    val limit: BudgetLimit get() = BudgetLimit(limitMicros, window, action)
}

/**
 * An org's limits, each subject with at most one per window.
 *
 * @property org What the whole org may spend.
 * @property defaultMember What each member may spend, unless they have limits of their own.
 * @property overrides Members' own limits, by member id.
 * @property workflows The workflows' limits, by workflow id: what the model calls of the launches that play each one
 *   may spend.
 */
data class BudgetSettings(
    val org: List<BudgetLimit>,
    val defaultMember: List<BudgetLimit>,
    val overrides: List<MemberBudget>,
    val workflows: List<WorkflowBudget> = emptyList(),
) {
    /** The org's monthly amount, whatever it does; null without one. */
    val orgLimitMicros: Long? get() = org.monthlyMicros()

    /** The default member's monthly amount, whatever it does; null without one. */
    val defaultMemberLimitMicros: Long? get() = defaultMember.monthlyMicros()
}

/**
 * One of a workflow's limits.
 *
 * @property workflowId The workflow.
 * @property limit The limit.
 * @property updatedAt Its last change.
 */
data class WorkflowBudget(val workflowId: String, val limit: BudgetLimit, val updatedAt: Instant)

/**
 * The limits that apply to one member's call, the month's before the day's.
 *
 * @property org The org's.
 * @property member The member's own, or else the default member limits.
 * @property workflow The limits of the workflow the call's launch plays; empty without one.
 */
data class BudgetLimits(
    val org: List<BudgetLimit>,
    val member: List<BudgetLimit>,
    val workflow: List<BudgetLimit> = emptyList(),
) {
    /** The member's monthly amount, whatever it does; null without one. */
    val memberLimitMicros: Long? get() = member.monthlyMicros()
}

/**
 * A limit spent to its end in its current day or month, as the guard saw it before a call.
 *
 * @property scope The org's limit, a member's (their own or the default) or a workflow's.
 * @property memberId The member, for a [BudgetScope.MEMBER] limit.
 * @property limit The limit.
 * @property since When the guard saw it reached.
 * @property workflowId The workflow, for a [BudgetScope.WORKFLOW] limit.
 */
data class ExceededBudget(
    val scope: BudgetScope,
    val memberId: Uuid?,
    val limit: BudgetLimit,
    val since: Instant,
    val workflowId: String? = null,
)

/** The limits and the alerts already sent. Every read and write is scoped by org. */
interface BudgetsRepository {
    /**
     * Reads the org's limits.
     *
     * @param orgId The org.
     * @param maxOverrides The most member overrides returned.
     *
     * @return The limits.
     */
    suspend fun settings(orgId: Uuid, maxOverrides: Int): BudgetSettings

    /**
     * Reads the limits of one member's call, in one query.
     *
     * @param orgId The org.
     * @param memberId The member.
     * @param workflowId The workflow the call's launch plays, or null: its limits are read too.
     *
     * @return The limits.
     */
    suspend fun limits(orgId: Uuid, memberId: Uuid, workflowId: String? = null): BudgetLimits

    /**
     * Replaces a subject's limits whole, in one transaction: a window absent from [limits] has no limit afterwards.
     * Only that subject's rows are written, so that two admins changing two subjects at once keep both changes.
     *
     * @param orgId The org.
     * @param target The subject.
     * @param limits Its limits, at most one per window; empty removes them.
     * @param at The time of the change.
     */
    suspend fun replaceLimits(orgId: Uuid, target: BudgetTarget, limits: List<BudgetLimit>, at: Instant)

    /**
     * Records that an alert is being sent, once across replicas.
     *
     * @param alert The alert.
     * @param at The time.
     *
     * @return True the first time for this limit value, threshold and month; false if it was already sent.
     */
    suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean

    /**
     * Forgets an alert whose sending failed, so that a later check sends it again.
     *
     * @param alert The alert [markAlerted] recorded.
     */
    suspend fun unmarkAlerted(alert: BudgetAlert)

    /**
     * The limits reached in their current period, as the guard marked them at 100 %, that still hold with the same
     * amount and action.
     *
     * @param orgId The org.
     * @param memberId Only this member's, or null for the whole org's (the workflows' included).
     * @param day The start of the current day.
     * @param month The start of the current month.
     * @param max The most returned.
     *
     * @return The limits, the first reached first.
     */
    suspend fun exceeded(orgId: Uuid, memberId: Uuid?, day: Instant, month: Instant, max: Int): List<ExceededBudget>
}
