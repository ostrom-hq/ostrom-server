// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.domain

/** The thresholds of agent-hour alerts, in percent of the period's hours. */
val ALERT_THRESHOLDS = listOf(80, 100)

/** Upper bound of the people read at once (a backfill, a listing). */
const val MAX_PEOPLE_READ = 1_000

/** Seconds in an agent hour. */
const val SECONDS_PER_HOUR = 3_600L

/** Upper bound of the invoices listed at once (Stripe's own maximum). */
const val MAX_INVOICES = 100

private const val PERCENT = 100

/** The share of [hours] agent hours that [agentSeconds] use, in whole percent. */
fun percentOfHours(agentSeconds: Long, hours: Int): Int = (agentSeconds * PERCENT / (hours * SECONDS_PER_HOUR)).toInt()

/**
 * The billing of an organization as the console shows it.
 *
 * @property billing The stored state (a fresh Free row when nothing is stored).
 * @property effectivePlan The plan whose limits apply now.
 * @property limits Those limits.
 * @property period The current billing period.
 * @property activePeople The people active in the period.
 * @property usage The cloud agent usage of the period.
 * @property alerts The usage thresholds reached in the period.
 * @property stripeEnabled Whether plans can be bought on this instance.
 */
data class BillingOverview(
    val billing: OrgBilling,
    val effectivePlan: Plan,
    val limits: PlanLimits,
    val period: BillingPeriod,
    val activePeople: Int,
    val usage: Usage,
    val alerts: List<Int>,
    val stripeEnabled: Boolean,
)
