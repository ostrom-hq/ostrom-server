// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BILLING_MANAGE
import dev.ostrom.server.billing.domain.BILLING_READ
import dev.ostrom.server.billing.domain.BillingAccessDeniedException
import dev.ostrom.server.billing.domain.BillingAdminRequiredException
import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingNotConfiguredException
import dev.ostrom.server.billing.domain.BillingSessionRequiredException
import dev.ostrom.server.billing.domain.Invoice
import dev.ostrom.server.billing.domain.LicenseGrant
import dev.ostrom.server.billing.domain.NoActiveSubscriptionException
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.PaymentIssue
import dev.ostrom.server.billing.domain.PaymentIssueKind
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.PlanLimits
import dev.ostrom.server.billing.domain.PlanNotPurchasableException
import dev.ostrom.server.billing.domain.SubscriptionAlreadyActiveException
import dev.ostrom.server.billing.domain.SubscriptionStateException
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.domain.allowsBillingRead
import dev.ostrom.server.billing.domain.allowsBillingWrite
import dev.ostrom.server.billing.domain.requireBillingWrite
import dev.ostrom.server.billing.fakes.NOW
import dev.ostrom.server.billing.fakes.stripeSubscription
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UseCasesTest {
    // Limits ------------------------------------------------------------------------------------------------------

    @Test
    fun anOrganizationWithoutBillingIsOnFree() = runTest {
        val world = World()
        assertEquals(
            PlanLimits(5, 5, 300, sso = false, policies = false, receiptRetentionDays = 30),
            world.limits(world.org),
        )
    }

    @Test
    fun paidLimitsCountActiveOrCommittedPeople() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM, BillingInterval.YEAR, committed = 12)
        assertEquals(1_200, world.limits(world.org).agentHoursPerPeriod)
        repeat(15) { world.repository.recordActivePerson(world.org, PERIOD, "u$it", NOW) }
        assertEquals(1_500, world.limits(world.org).agentHoursPerPeriod)
    }

    @Test
    fun selfHostedWithoutLicenseIsUnlimitedButWithoutEnterpriseFeatures() = runTest {
        val world = World(stripeEnabled = false)
        assertEquals(
            PlanLimits(null, null, null, sso = false, policies = false, receiptRetentionDays = 30),
            world.limits(world.org),
        )
    }

    @Test
    fun selfHostedLicenseGrantsItsFeaturesAndPeople() = runTest {
        val world = World(stripeEnabled = false)
        world.license.grant = LicenseGrant(setOf("sso", "policies", "retention"), maxPeople = 50, expiresAt = null)
        assertEquals(
            PlanLimits(50, null, null, sso = true, policies = true, receiptRetentionDays = null),
            world.limits(world.org),
        )
    }

    // Overview ----------------------------------------------------------------------------------------------------

    @Test
    fun theOverviewShowsPlanPeriodPeopleUsageAndAlerts() = runTest {
        val world = World()
        world.subscribe(Plan.BUSINESS)
        world.repository.recordActivePerson(world.org, PERIOD, "a", NOW)
        world.repository.addUsage(world.org, PERIOD, 3_600, 1)
        world.repository.recordAlert(world.org, PERIOD, 80, NOW)
        val overview = world.overview(world.member, world.org)
        assertEquals(Plan.BUSINESS, overview.effectivePlan)
        assertEquals(PERIOD, overview.period.start)
        assertEquals(1, overview.activePeople)
        assertEquals(3_600L, overview.usage.agentSeconds)
        assertEquals(listOf(80), overview.alerts)
        assertTrue(overview.stripeEnabled)
    }

    @Test
    fun theOverviewOfANewOrganizationIsFreeAndTheCalendarMonth() = runTest {
        val world = World()
        val overview = world.overview(world.member, world.org)
        assertEquals(Plan.FREE, overview.billing.plan)
        assertEquals(MONTH, overview.period.start)
    }

    @Test
    fun aNonMemberCannotSeeTheBilling() = runTest {
        val world = World()
        assertFailsWith<BillingAccessDeniedException> { world.overview(world.stranger, world.org) }
        assertFailsWith<BillingAccessDeniedException> { world.portal(world.stranger, world.org) }
    }

    @Test
    fun billingIsReadByPeopleAndChangedInTheConsoleOnly() {
        fun principal(kind: PrincipalKind, vararg scopes: String) = AuthenticatedPrincipal("u", kind, scopes.toSet())
        val session = principal(PrincipalKind.USER, "user:*")
        assertTrue(session.allowsBillingRead())
        assertTrue(session.allowsBillingWrite())
        session.requireBillingWrite()
        assertTrue(principal(PrincipalKind.USER, BILLING_MANAGE).allowsBillingRead())
        assertTrue(principal(PrincipalKind.USER, BILLING_READ).allowsBillingRead())
        assertFalse(principal(PrincipalKind.USER, "orgs:read").allowsBillingRead())
        assertFalse(principal(PrincipalKind.DEVICE, "user:*").allowsBillingRead())
        assertFalse(principal(PrincipalKind.SERVICE, BILLING_MANAGE).allowsBillingRead())
        // S2-52: a personal token never changes billing, whatever its scopes; nor does anything but a person.
        assertFalse(principal(PrincipalKind.USER, BILLING_MANAGE).allowsBillingWrite())
        assertFalse(principal(PrincipalKind.USER, BILLING_READ).allowsBillingWrite())
        assertFalse(principal(PrincipalKind.DEVICE, "user:*").allowsBillingWrite())
        val refused = assertFailsWith<BillingSessionRequiredException> {
            principal(PrincipalKind.USER, BILLING_MANAGE).requireBillingWrite()
        }
        assertEquals("This change needs a signed-in session in the console: a token cannot make it.", refused.message)
    }

    @Test
    fun ownersAdminsAndBillingAdminsManageTheSubscriptionOtherRolesOnlyRead() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM)
        for (role in OrgRole.entries) {
            val caller = world.member.copy(role = role)
            world.overview(caller, world.org)
            if (role in setOf(OrgRole.OWNER, OrgRole.ADMIN, OrgRole.BILLING)) {
                world.invoices(caller, world.org, 1)
            } else {
                assertFailsWith<BillingAdminRequiredException>(role.name) { world.invoices(caller, world.org, 1) }
            }
        }
    }

    // Checkout ----------------------------------------------------------------------------------------------------

    @Test
    fun aMonthlyCheckoutCreatesTheCustomerOnceAndSendsTheMeteredPrice() = runTest {
        val world = World()
        val url = world.checkout(world.admin, world.org, Plan.TEAM, BillingInterval.MONTH)
        assertEquals("https://checkout.stripe.com/c/pay/cs_test_1", url)
        val request = world.stripe.checkouts.single()
        assertEquals("cus_1", request.customerId)
        assertEquals("price_ostrom_team_monthly", request.priceId)
        assertNull(request.quantity)
        assertEquals("https://app.ostrom.run/settings/billing?checkout=success", request.successUrl)
        assertEquals("https://app.ostrom.run/settings/billing?checkout=canceled", request.cancelUrl)
        assertEquals("cus_1", world.repository.get(world.org)?.stripeCustomerId)
        world.checkout(world.admin, world.org, Plan.TEAM, BillingInterval.MONTH)
        assertEquals(1, world.stripe.customers, "The customer is reused on a second checkout")
    }

    @Test
    fun aYearlyCheckoutIsForTheActivePeopleAtLeastOne() = runTest {
        val world = World()
        world.checkout(world.admin, world.org, Plan.BUSINESS, BillingInterval.YEAR)
        assertEquals(1, world.stripe.checkouts.last().quantity)
        repeat(4) { world.repository.recordActivePerson(world.org, MONTH, "u$it", NOW) }
        world.checkout(world.admin, world.org, Plan.BUSINESS, BillingInterval.YEAR)
        assertEquals(4, world.stripe.checkouts.last().quantity)
    }

    @Test
    fun checkoutRefusals() = runTest {
        val world = World()
        assertFailsWith<BillingAdminRequiredException> {
            world.checkout(world.member, world.org, Plan.TEAM, BillingInterval.MONTH)
        }
        assertFailsWith<PlanNotPurchasableException> {
            world.checkout(world.admin, world.org, Plan.ENTERPRISE, BillingInterval.MONTH)
        }
        assertFailsWith<PlanNotPurchasableException> {
            world.checkout(world.admin, world.org, Plan.FREE, BillingInterval.MONTH)
        }
        world.subscribe()
        assertFailsWith<SubscriptionAlreadyActiveException> {
            world.checkout(world.admin, world.org, Plan.BUSINESS, BillingInterval.MONTH)
        }
        assertFailsWith<BillingNotConfiguredException> {
            World(stripeEnabled = false).let {
                it.checkout(it.admin, it.org, Plan.TEAM, BillingInterval.MONTH)
            }
        }
    }

    @Test
    fun aCanceledOrganizationBuysAgainWithItsCustomer() = runTest {
        val world = World()
        world.repository.save(world.subscribe().copy(status = SubscriptionStatus.CANCELED))
        world.checkout(world.admin, world.org, Plan.TEAM, BillingInterval.MONTH)
        assertEquals("cus_1", world.stripe.checkouts.single().customerId)
        assertEquals(0, world.stripe.customers)
    }

    @Test
    fun enterpriseRefusalSaysToContactSales() {
        assertEquals(
            "Enterprise is sold on quote: contact sales@ostrom.run.",
            PlanNotPurchasableException(Plan.ENTERPRISE).message,
        )
        assertEquals("The free plan cannot be bought.", PlanNotPurchasableException(Plan.FREE).message)
    }

    // Portal, invoices --------------------------------------------------------------------------------------------

    @Test
    fun thePortalOpensForACustomerOnly() = runTest {
        val world = World()
        assertFailsWith<NoActiveSubscriptionException> { world.portal(world.admin, world.org) }
        world.subscribe()
        assertEquals("https://billing.stripe.com/p/session/test_1", world.portal(world.admin, world.org))
        assertEquals("portal cus_1 https://app.ostrom.run/settings/billing", world.stripe.calls.last())
        assertFailsWith<BillingAdminRequiredException> { world.portal(world.member, world.org) }
        assertFailsWith<BillingNotConfiguredException> {
            World(stripeEnabled = false).let { it.portal(it.admin, it.org) }
        }
    }

    @Test
    fun invoicesAreBoundedAndEmptyBeforeTheFirstPurchase() = runTest {
        val world = World()
        assertEquals(emptyList(), world.invoices(world.admin, world.org, 10))
        world.subscribe()
        world.stripe.invoices = listOf(
            Invoice("in_1", "A-1", "paid", 4_200, 700, "usd", NOW, "https://pay", "https://pdf")
        )
        assertEquals("in_1", world.invoices(world.admin, world.org, 500).single().id)
        assertEquals("invoices cus_1 100", world.stripe.calls.last())
        world.invoices(world.admin, world.org, 0)
        assertEquals("invoices cus_1 1", world.stripe.calls.last())
    }

    // Change plan, cancel, resume ---------------------------------------------------------------------------------

    @Test
    fun upgradingToBusinessIsInvoicedNow() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM)
        world.stripe.subscription = stripeSubscription("ostrom_business_monthly")
        val after = world.changePlan(world.admin, world.org, Plan.BUSINESS, BillingInterval.MONTH)
        assertTrue("changePrice sub_1 si_1 price_ostrom_business_monthly null always_invoice" in world.stripe.calls)
        assertEquals(Plan.BUSINESS, after.plan)
        assertEquals(Plan.BUSINESS, world.repository.get(world.org)?.plan)
    }

    @Test
    fun downgradingIsCreditedOnTheNextInvoice() = runTest {
        val world = World()
        world.subscribe(Plan.BUSINESS)
        world.changePlan(world.admin, world.org, Plan.TEAM, BillingInterval.MONTH)
        assertTrue("changePrice sub_1 si_1 price_ostrom_team_monthly null create_prorations" in world.stripe.calls)
    }

    @Test
    fun goingYearlyCommitsTheActivePeopleAndIsInvoicedNow() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM)
        repeat(3) { world.repository.recordActivePerson(world.org, PERIOD, "u$it", NOW) }
        world.stripe.subscription = stripeSubscription("ostrom_team_yearly", quantity = 3)
        val after = world.changePlan(world.admin, world.org, Plan.TEAM, BillingInterval.YEAR)
        assertTrue("changePrice sub_1 si_1 price_ostrom_team_yearly 3 always_invoice" in world.stripe.calls)
        assertEquals(3, after.committedPeople)
    }

    @Test
    fun aYearlyChangeKeepsTheCommittedPeople() = runTest {
        val world = World()
        world.subscribe(Plan.BUSINESS, BillingInterval.YEAR, committed = 8)
        world.changePlan(world.admin, world.org, Plan.TEAM, BillingInterval.YEAR)
        assertTrue("changePrice sub_1 si_1 price_ostrom_team_yearly 8 create_prorations" in world.stripe.calls)
    }

    @Test
    fun theSamePlanChangesNothing() = runTest {
        val world = World()
        val billing = world.subscribe(Plan.TEAM)
        assertEquals(billing, world.changePlan(world.admin, world.org, Plan.TEAM, BillingInterval.MONTH))
        assertTrue(world.stripe.calls.none { it.startsWith("changePrice") })
    }

    @Test
    fun changePlanRefusals() = runTest {
        val world = World()
        assertFailsWith<NoActiveSubscriptionException> {
            world.changePlan(world.admin, world.org, Plan.BUSINESS, BillingInterval.MONTH)
        }
        world.repository.save(world.subscribe().copy(subscriptionItemId = null))
        assertFailsWith<SubscriptionStateException> {
            world.changePlan(world.admin, world.org, Plan.BUSINESS, BillingInterval.MONTH)
        }
        assertFailsWith<PlanNotPurchasableException> {
            world.changePlan(world.admin, world.org, Plan.ENTERPRISE, BillingInterval.MONTH)
        }
        assertFailsWith<BillingAdminRequiredException> {
            world.changePlan(world.member, world.org, Plan.BUSINESS, BillingInterval.MONTH)
        }
        assertFailsWith<BillingNotConfiguredException> {
            World(stripeEnabled = false).let { it.changePlan(it.admin, it.org, Plan.BUSINESS, BillingInterval.MONTH) }
        }
    }

    @Test
    fun cancelThenResume() = runTest {
        val world = World()
        world.subscribe()
        world.stripe.subscription = stripeSubscription(cancelAtPeriodEnd = true)
        assertTrue(world.cancellation(world.admin, world.org, cancel = true).cancelAtPeriodEnd)
        assertEquals("cancelAtPeriodEnd sub_1 true", world.stripe.calls[world.stripe.calls.size - 2])
        assertFailsWith<SubscriptionStateException> { world.cancellation(world.admin, world.org, cancel = true) }
        world.stripe.subscription = stripeSubscription(cancelAtPeriodEnd = false)
        assertEquals(false, world.cancellation(world.admin, world.org, cancel = false).cancelAtPeriodEnd)
        assertFailsWith<SubscriptionStateException> { world.cancellation(world.admin, world.org, cancel = false) }
    }

    @Test
    fun cancellationRefusals() = runTest {
        val world = World()
        assertFailsWith<NoActiveSubscriptionException> { world.cancellation(world.admin, world.org, cancel = true) }
        assertFailsWith<BillingAdminRequiredException> { world.cancellation(world.member, world.org, cancel = true) }
        assertFailsWith<BillingNotConfiguredException> {
            World(stripeEnabled = false).let { it.cancellation(it.admin, it.org, cancel = true) }
        }
    }

    // Sync --------------------------------------------------------------------------------------------------------

    @Test
    fun anUnknownPriceKeepsThePreviousPlan() = runTest {
        val world = World()
        val billing = world.subscribe(Plan.BUSINESS)
        world.stripe.subscription = stripeSubscription(lookupKey = "legacy_price")
        val synced = world.sync(billing, "sub_1")
        assertEquals(Plan.BUSINESS, synced.plan)
        assertEquals(BillingInterval.MONTH, synced.interval)
    }

    @Test
    fun aYearlySubscriptionWithoutQuantityCommitsNobody() = runTest {
        val world = World()
        world.stripe.subscription = stripeSubscription(lookupKey = "ostrom_team_yearly", quantity = null)
        assertEquals(0, world.sync(OrgBilling(orgId = world.org, updatedAt = NOW), "sub_1").committedPeople)
    }

    @Test
    fun aSyncLeavesThePaymentIssueToTheInvoiceEvents() = runTest {
        val world = World()
        val issue = PaymentIssue(PaymentIssueKind.ACTION_REQUIRED, "https://pay", NOW)
        val billing = world.subscribe().copy(paymentIssue = issue)
        assertEquals(issue, world.sync(billing, "sub_1").paymentIssue, "Active, with an invoice waiting for 3DS")
        world.stripe.subscription = stripeSubscription(status = SubscriptionStatus.PAST_DUE)
        assertEquals(issue, world.sync(billing, "sub_1").paymentIssue)
        assertNull(world.sync(billing.copy(paymentIssue = null), "sub_1").paymentIssue)
    }
}
