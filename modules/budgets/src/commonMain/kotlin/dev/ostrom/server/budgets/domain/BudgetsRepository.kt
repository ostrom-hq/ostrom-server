// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One member's own monthly limit, which replaces the default member limit.
 *
 * @property memberId The member.
 * @property limitMicros The limit, USD millionths.
 * @property updatedAt Its last change.
 */
data class MemberBudget(val memberId: Uuid, val limitMicros: Long, val updatedAt: Instant)

/**
 * An org's monthly limits; a null limit means none.
 *
 * @property orgLimitMicros What the whole org may spend.
 * @property defaultMemberLimitMicros What each member may spend, unless overridden.
 * @property overrides Members with their own limit, by member id.
 */
data class BudgetSettings(
    val orgLimitMicros: Long?,
    val defaultMemberLimitMicros: Long?,
    val overrides: List<MemberBudget>,
)

/**
 * The limits that apply to one member.
 *
 * @property orgLimitMicros The org's.
 * @property memberLimitMicros The member's own, or else the default member limit.
 */
data class BudgetLimits(val orgLimitMicros: Long?, val memberLimitMicros: Long?)

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
     * Reads the limits of one member, in one query.
     *
     * @param orgId The org.
     * @param memberId The member.
     *
     * @return The limits.
     */
    suspend fun limits(orgId: Uuid, memberId: Uuid): BudgetLimits

    /**
     * Replaces the org limit and the default member limit together; a null removes it.
     *
     * @param orgId The org.
     * @param orgLimitMicros The org limit.
     * @param defaultMemberLimitMicros The default member limit.
     * @param at The time of the change.
     */
    suspend fun putOrgLimits(orgId: Uuid, orgLimitMicros: Long?, defaultMemberLimitMicros: Long?, at: Instant)

    /**
     * Sets a member's own limit.
     *
     * @param orgId The org.
     * @param memberId The member.
     * @param limitMicros The limit.
     * @param at The time of the change.
     *
     * @return The stored override.
     */
    suspend fun putMemberLimit(orgId: Uuid, memberId: Uuid, limitMicros: Long, at: Instant): MemberBudget

    /**
     * Removes a member's own limit: the default applies again.
     *
     * @param orgId The org.
     * @param memberId The member.
     *
     * @return True if there was one.
     */
    suspend fun deleteMemberLimit(orgId: Uuid, memberId: Uuid): Boolean

    /**
     * Records that an alert is being sent, once across replicas.
     *
     * @param alert The alert.
     * @param at The time.
     *
     * @return True the first time for this limit, threshold and month; false if it was already sent.
     */
    suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean
}
