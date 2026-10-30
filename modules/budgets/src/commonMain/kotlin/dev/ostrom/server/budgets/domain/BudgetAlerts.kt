// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Spending crossed a threshold of a monthly limit. Published once per limit, threshold and month, across replicas.
 *
 * @property orgId The org.
 * @property memberId The member, for a [BudgetScope.MEMBER] limit; null for the org's.
 * @property scope The limit crossed.
 * @property thresholdPercent 80 or 100.
 * @property limitMicros The limit, USD millionths.
 * @property spentMicros What was spent this month when the crossing was seen.
 * @property periodStart The month (its first instant, UTC).
 */
data class BudgetAlert(
    val orgId: Uuid,
    val memberId: Uuid?,
    val scope: BudgetScope,
    val thresholdPercent: Int,
    val limitMicros: Long,
    val spentMicros: Long,
    val periodStart: Instant,
)

/**
 * Where budget alerts go: the port the notifications module binds in Koin. Until it does, the budgets module uses
 * [NoBudgetAlerts].
 */
interface BudgetAlerts {
    /**
     * Delivers an alert.
     *
     * @param alert The alert.
     */
    suspend fun publish(alert: BudgetAlert)
}

/** Drops every alert: the default until notifications bind their own [BudgetAlerts]. */
class NoBudgetAlerts : BudgetAlerts {
    override suspend fun publish(alert: BudgetAlert) = Unit
}
