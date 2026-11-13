// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private const val MONTHS_PER_YEAR = 12

/**
 * The billing period containing [at] for an organization: Stripe's period when a live subscription has started by
 * [at], else the calendar month in UTC. Active people and agent hours are counted per period, so they must use the
 * period Stripe invoices: a person active on the 10th and the 20th of a period starting on the 15th is in two periods.
 *
 * When [at] is past the stored period end, Stripe has renewed the subscription and its webhook is not in yet: the
 * period is rolled forward on the same anchor (one month, or one year), so that a person active in that gap is counted
 * in the period Stripe bills them in, and not a second time once the webhook arrives.
 */
fun billingPeriod(billing: OrgBilling?, at: Instant): BillingPeriod {
    val live = billing?.takeIf { it.hasLiveSubscription }
    val start = live?.currentPeriodStart?.takeIf { at >= it }
    val end = live?.currentPeriodEnd
    return when {
        live == null || start == null || end == null -> calendarMonth(at)
        at < end -> BillingPeriod(start, end)
        else -> rolledForward(start, end, if (live.interval == BillingInterval.YEAR) MONTHS_PER_YEAR else 1, at)
    }
}

/** The period after [end] that contains [at], on the anchor [start], each [months] long. */
private fun rolledForward(start: Instant, end: Instant, months: Int, at: Instant): BillingPeriod {
    var index = 1
    var periodStart = end
    var periodEnd = addMonths(start, 2 * months)
    while (at >= periodEnd) {
        index++
        periodStart = periodEnd
        periodEnd = addMonths(start, (index + 1) * months)
    }
    return BillingPeriod(periodStart, periodEnd)
}

/**
 * [at] moved by [months] calendar months in UTC, at the same time of day, on the same day of the month or the month's
 * last day when it is shorter (as Stripe anchors a billing cycle on the 31st).
 */
fun addMonths(at: Instant, months: Int): Instant = at.plus(months, DateTimeUnit.MONTH, TimeZone.UTC)

/** The calendar month (UTC) containing [at]. */
fun calendarMonth(at: Instant): BillingPeriod {
    val date = at.toLocalDateTime(TimeZone.UTC).date
    val start = LocalDate(date.year, date.month, 1).atStartOfDayIn(TimeZone.UTC)
    return BillingPeriod(start, addMonths(start, 1))
}
