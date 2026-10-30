package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingPeriod
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.PlanCatalog
import dev.ostrom.server.billing.domain.PlanLimits
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.domain.Usage
import dev.ostrom.server.billing.domain.addMonths
import dev.ostrom.server.billing.domain.billingPeriod
import dev.ostrom.server.billing.domain.calendarMonth
import dev.ostrom.server.billing.domain.displayName
import dev.ostrom.server.billing.fakes.NOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PlansAndPeriodsTest {
    @Test
    fun freeIsFivePeopleFiveAgentsThreeHundredHours() {
        assertEquals(PlanLimits(5, 5, 300, sso = false, policies = false, receiptRetentionDays = 30), PlanCatalog.limits(Plan.FREE, 3))
    }

    @Test
    fun teamGrowsWithPeopleButNeverBelowFree() {
        assertEquals(PlanLimits(null, 5, 300, sso = false, policies = false, receiptRetentionDays = 90), PlanCatalog.limits(Plan.TEAM, 0))
        assertEquals(PlanLimits(null, 50, 1000, sso = false, policies = false, receiptRetentionDays = 90), PlanCatalog.limits(Plan.TEAM, 10))
    }

    @Test
    fun businessAddsSsoPoliciesAndAYearOfReceipts() {
        assertEquals(PlanLimits(null, 10, 600, sso = true, policies = true, receiptRetentionDays = 365), PlanCatalog.limits(Plan.BUSINESS, 1))
        assertEquals(PlanLimits(null, 100, 3000, sso = true, policies = true, receiptRetentionDays = 365), PlanCatalog.limits(Plan.BUSINESS, 20))
    }

    @Test
    fun enterpriseIsUnlimited() {
        assertEquals(PlanLimits(null, null, null, sso = true, policies = true, receiptRetentionDays = null), PlanCatalog.limits(Plan.ENTERPRISE, 1))
    }

    @Test
    fun lookupKeysMatchTheProvisioningCatalog() {
        assertEquals("ostrom_team_monthly", PlanCatalog.lookupKey(Plan.TEAM, BillingInterval.MONTH))
        assertEquals("ostrom_business_yearly", PlanCatalog.lookupKey(Plan.BUSINESS, BillingInterval.YEAR))
        assertEquals(Plan.BUSINESS to BillingInterval.MONTH, PlanCatalog.fromLookupKey("ostrom_business_monthly"))
        assertEquals(Plan.TEAM to BillingInterval.YEAR, PlanCatalog.fromLookupKey("ostrom_team_yearly"))
        assertNull(PlanCatalog.fromLookupKey("someone_else"))
        assertNull(PlanCatalog.fromLookupKey(null))
        assertFailsWith<IllegalArgumentException> { PlanCatalog.lookupKey(Plan.ENTERPRISE, BillingInterval.MONTH) }
    }

    @Test
    fun pricesAreTheGridAndYearlyIsMinusTwentyPercent() {
        val cents = PlanCatalog.prices.associate { (it.plan to it.interval) to it.unitAmount }
        assertEquals(3_500, cents[Plan.TEAM to BillingInterval.MONTH])
        assertEquals(33_600, cents[Plan.TEAM to BillingInterval.YEAR])
        assertEquals(6_500, cents[Plan.BUSINESS to BillingInterval.MONTH])
        assertEquals(62_400, cents[Plan.BUSINESS to BillingInterval.YEAR])
    }

    @Test
    fun keysReadBack() {
        assertEquals(Plan.TEAM, Plan.fromKey("team"))
        assertNull(Plan.fromKey("gold"))
        assertEquals(BillingInterval.YEAR, BillingInterval.fromKey("year"))
        assertNull(BillingInterval.fromKey("week"))
        assertEquals("Business", Plan.BUSINESS.displayName)
        assertTrue(Plan.TEAM.selfServe)
        assertFalse(Plan.FREE.selfServe)
        assertFalse(Plan.ENTERPRISE.selfServe)
    }

    @Test
    fun onlyActiveTrialingAndPastDueGrantThePlan() {
        val granting = SubscriptionStatus.entries.filter { it.grantsPlan }
        assertEquals(listOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING, SubscriptionStatus.PAST_DUE), granting)
        assertEquals(SubscriptionStatus.UNKNOWN, SubscriptionStatus.fromStripe("something_new"))
        assertEquals(SubscriptionStatus.PAST_DUE, SubscriptionStatus.fromStripe("past_due"))
    }

    @Test
    fun anUnpaidSubscriptionFallsBackToFree() {
        val org = Uuid.random()
        val billing = OrgBilling(orgId = org, stripeSubscriptionId = "sub", plan = Plan.BUSINESS, status = SubscriptionStatus.UNPAID, updatedAt = NOW)
        assertEquals(Plan.FREE, billing.effectivePlan)
        assertFalse(billing.hasLiveSubscription)
        assertEquals(Plan.BUSINESS, billing.copy(status = SubscriptionStatus.PAST_DUE).effectivePlan)
        assertEquals(Plan.FREE, billing.copy(status = null).effectivePlan)
        assertFalse(billing.copy(status = SubscriptionStatus.ACTIVE, stripeSubscriptionId = null).hasLiveSubscription)
    }

    @Test
    fun calendarMonthsCoverLeapYearsAndDecember() {
        assertEquals(period("2026-10-01", "2026-11-01"), calendarMonth(NOW))
        assertEquals(period("2026-12-01", "2027-01-01"), calendarMonth(Instant.parse("2026-12-31T23:59:59Z")))
        assertEquals(period("2028-02-01", "2028-03-01"), calendarMonth(Instant.parse("2028-02-29T12:00:00Z")))
        assertEquals(period("2027-01-01", "2027-02-01"), calendarMonth(Instant.parse("2027-01-01T00:00:00Z")))
        assertEquals(period("1969-12-01", "1970-01-01"), calendarMonth(Instant.parse("1969-12-15T00:00:00Z")))
        assertEquals(period("2000-03-01", "2000-04-01"), calendarMonth(Instant.parse("2000-03-31T00:00:00Z")))
    }

    @Test
    fun aLiveSubscriptionUsesStripesPeriodOnlyInsideIt() {
        val live = OrgBilling(
            orgId = Uuid.random(),
            stripeSubscriptionId = "sub",
            status = SubscriptionStatus.ACTIVE,
            currentPeriodStart = Instant.parse("2026-10-15T00:00:00Z"),
            currentPeriodEnd = Instant.parse("2026-11-15T00:00:00Z"),
            updatedAt = NOW,
        )
        assertEquals(period("2026-10-15", "2026-11-15"), billingPeriod(live, NOW))
        assertEquals(period("2026-11-15", "2026-12-15"), billingPeriod(live, Instant.parse("2026-11-20T00:00:00Z")))
        assertEquals(period("2026-11-15", "2026-12-15"), billingPeriod(live, Instant.parse("2026-11-15T00:00:00Z")))
        assertEquals(period("2027-01-15", "2027-02-15"), billingPeriod(live, Instant.parse("2027-02-14T23:59:59Z")))
        assertEquals(period("2026-10-01", "2026-11-01"), billingPeriod(live, Instant.parse("2026-10-14T00:00:00Z")))
        assertEquals(period("2026-10-01", "2026-11-01"), billingPeriod(live.copy(currentPeriodEnd = null), NOW))
        assertEquals(period("2026-10-01", "2026-11-01"), billingPeriod(live.copy(currentPeriodStart = null), NOW))
        assertEquals(period("2026-10-01", "2026-11-01"), billingPeriod(live.copy(status = SubscriptionStatus.CANCELED), NOW))
        assertEquals(period("2026-10-01", "2026-11-01"), billingPeriod(null, NOW))
    }

    @Test
    fun usageRoundsHoursDown() {
        assertEquals(1, Usage(NOW, 7_199, 2).agentHours)
    }

    @Test
    fun aRenewedYearlySubscriptionRollsByYears() {
        val yearly = OrgBilling(
            orgId = Uuid.random(),
            stripeSubscriptionId = "sub",
            interval = BillingInterval.YEAR,
            status = SubscriptionStatus.ACTIVE,
            currentPeriodStart = Instant.parse("2026-10-15T00:00:00Z"),
            currentPeriodEnd = Instant.parse("2027-10-15T00:00:00Z"),
            updatedAt = NOW,
        )
        assertEquals(period("2027-10-15", "2028-10-15"), billingPeriod(yearly, Instant.parse("2027-10-16T00:00:00Z")))
    }

    @Test
    fun monthsAreAddedOnTheAnchorDayOrTheLastDay() {
        val jan31 = Instant.parse("2027-01-31T08:30:15.250Z")
        assertEquals(Instant.parse("2027-02-28T08:30:15.250Z"), addMonths(jan31, 1))
        assertEquals(Instant.parse("2028-02-29T08:30:15.250Z"), addMonths(jan31, 13))
        assertEquals(Instant.parse("2027-03-31T08:30:15.250Z"), addMonths(jan31, 2))
        assertEquals(Instant.parse("2026-12-31T08:30:15.250Z"), addMonths(jan31, -1))
        assertEquals(Instant.parse("2027-12-31T08:30:15.250Z"), addMonths(jan31, 11))
        assertEquals(Instant.parse("1969-12-31T00:00:00Z"), addMonths(Instant.parse("1970-01-31T00:00:00Z"), -1))
    }

    private fun period(start: String, end: String) =
        BillingPeriod(Instant.parse("${start}T00:00:00Z"), Instant.parse("${end}T00:00:00Z"))
}
