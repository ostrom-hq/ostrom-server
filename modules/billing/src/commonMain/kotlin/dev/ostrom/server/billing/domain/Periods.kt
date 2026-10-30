package dev.ostrom.server.billing.domain

import kotlin.time.Instant

private const val SECONDS_PER_DAY = 86_400L

/**
 * The billing period containing [at] for an organization: Stripe's current period when a live subscription covers
 * [at], else the calendar month in UTC. Active people and agent hours are counted per period, so they must use
 * the period Stripe invoices: a person active on the 10th and the 20th of a period starting on the 15th is in two
 * periods.
 */
fun billingPeriod(billing: OrgBilling?, at: Instant): BillingPeriod {
    val start = billing?.currentPeriodStart
    val end = billing?.currentPeriodEnd
    if (billing?.hasLiveSubscription == true && start != null && end != null && at >= start && at < end) {
        return BillingPeriod(start, end)
    }
    return calendarMonth(at)
}

/** The calendar month (UTC) containing [at]. */
fun calendarMonth(at: Instant): BillingPeriod {
    val days = at.epochSeconds.floorDiv(SECONDS_PER_DAY)
    val (year, month) = civilFromDays(days)
    val start = daysFromCivil(year, month, 1)
    val end = if (month == 12) daysFromCivil(year + 1, 1, 1) else daysFromCivil(year, month + 1, 1)
    return BillingPeriod(Instant.fromEpochSeconds(start * SECONDS_PER_DAY), Instant.fromEpochSeconds(end * SECONDS_PER_DAY))
}

// Howard Hinnant's days <-> civil date algorithms (public domain), proleptic Gregorian, valid for every Instant we
// store. Written here to avoid a calendar dependency for one function.
private fun civilFromDays(days: Long): Pair<Long, Int> {
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return year to month
}

private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400L)
    val yearOfEra = y - era * 400
    val mp = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153 * mp + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}
