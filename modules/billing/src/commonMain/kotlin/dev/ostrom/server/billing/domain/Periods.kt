package dev.ostrom.server.billing.domain

import kotlin.time.Instant

private const val SECONDS_PER_DAY = 86_400L

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
    val start = billing?.currentPeriodStart
    val end = billing?.currentPeriodEnd
    if (billing?.hasLiveSubscription != true || start == null || end == null || at < start) return calendarMonth(at)
    if (at < end) return BillingPeriod(start, end)
    val months = if (billing.interval == BillingInterval.YEAR) 12 else 1
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
 * [at] moved by [months] calendar months, at the same time of day, on the same day of the month or the month's last
 * day when it is shorter (as Stripe anchors a billing cycle on the 31st).
 */
fun addMonths(at: Instant, months: Int): Instant {
    val days = at.epochSeconds.floorDiv(SECONDS_PER_DAY)
    val secondOfDay = at.epochSeconds - days * SECONDS_PER_DAY
    val (year, month, day) = civilDate(days)
    val total = year * 12 + (month - 1) + months
    val targetYear = total.floorDiv(12L)
    val targetMonth = (total - targetYear * 12).toInt() + 1
    val nextMonthStart = if (targetMonth == 12) daysFromCivil(targetYear + 1, 1, 1) else daysFromCivil(targetYear, targetMonth + 1, 1)
    val monthLength = (nextMonthStart - daysFromCivil(targetYear, targetMonth, 1)).toInt()
    val targetDays = daysFromCivil(targetYear, targetMonth, minOf(day, monthLength))
    return Instant.fromEpochSeconds(targetDays * SECONDS_PER_DAY + secondOfDay, at.nanosecondsOfSecond)
}

/** The calendar month (UTC) containing [at]. */
fun calendarMonth(at: Instant): BillingPeriod {
    val days = at.epochSeconds.floorDiv(SECONDS_PER_DAY)
    val (year, month, _) = civilDate(days)
    val start = daysFromCivil(year, month, 1)
    val end = if (month == 12) daysFromCivil(year + 1, 1, 1) else daysFromCivil(year, month + 1, 1)
    return BillingPeriod(Instant.fromEpochSeconds(start * SECONDS_PER_DAY), Instant.fromEpochSeconds(end * SECONDS_PER_DAY))
}

// Howard Hinnant's days <-> civil date algorithms (public domain), proleptic Gregorian, valid for every Instant we
// store. Written here to avoid a calendar dependency for one function.
private fun civilDate(days: Long): Triple<Long, Int, Int> {
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return Triple(year, month, day)
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
