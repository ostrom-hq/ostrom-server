package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BackfillActivePeopleUseCaseImpl
import dev.ostrom.server.billing.domain.BillingAccessDeniedException
import dev.ostrom.server.billing.domain.BillingAdminRequiredException
import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingNotConfiguredException
import dev.ostrom.server.billing.domain.BillingSettings
import dev.ostrom.server.billing.domain.ChangePlanUseCaseImpl
import dev.ostrom.server.billing.domain.CheckEntitlementUseCaseImpl
import dev.ostrom.server.billing.domain.Entitlement
import dev.ostrom.server.billing.domain.EventOutcome
import dev.ostrom.server.billing.domain.GetBillingOverviewUseCaseImpl
import dev.ostrom.server.billing.domain.GetPlanLimitsUseCaseImpl
import dev.ostrom.server.billing.domain.HandleStripeEventUseCaseImpl
import dev.ostrom.server.billing.domain.Invoice
import dev.ostrom.server.billing.domain.LicenseGrant
import dev.ostrom.server.billing.domain.ListInvoicesUseCaseImpl
import dev.ostrom.server.billing.domain.NoActiveSubscriptionException
import dev.ostrom.server.billing.domain.OpenCustomerPortalUseCaseImpl
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.OutboxKind
import dev.ostrom.server.billing.domain.PaymentIssue
import dev.ostrom.server.billing.domain.PaymentIssueKind
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.PlanLimitException
import dev.ostrom.server.billing.domain.PlanLimits
import dev.ostrom.server.billing.domain.PlanNotPurchasableException
import dev.ostrom.server.billing.domain.ProcessOutboxUseCaseImpl
import dev.ostrom.server.billing.domain.RecordAgentUsageUseCaseImpl
import dev.ostrom.server.billing.domain.RecordMemberActivityUseCaseImpl
import dev.ostrom.server.billing.domain.SetCancellationUseCaseImpl
import dev.ostrom.server.billing.domain.StartCheckoutUseCaseImpl
import dev.ostrom.server.billing.domain.StripeEvent
import dev.ostrom.server.billing.domain.SubscriptionAlreadyActiveException
import dev.ostrom.server.billing.domain.SubscriptionStateException
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.domain.SyncSubscriptionUseCaseImpl
import dev.ostrom.server.billing.fakes.FakeAccessPolicy
import dev.ostrom.server.billing.fakes.FakeBillingRepository
import dev.ostrom.server.billing.fakes.FakeLicenseProvider
import dev.ostrom.server.billing.fakes.FakeNotifier
import dev.ostrom.server.billing.fakes.FakeStripeGateway
import dev.ostrom.server.billing.fakes.FixedClock
import dev.ostrom.server.billing.fakes.NOW
import dev.ostrom.server.billing.fakes.stripeSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Every use case wired on fakes, as the module wires them on real implementations. */
private class World(stripeEnabled: Boolean = true) {
    val org: Uuid = Uuid.parse("0195f7a2-0000-7000-8000-000000000001")
    val repository = FakeBillingRepository()
    val stripe = FakeStripeGateway()
    val access = FakeAccessPolicy(members = mutableSetOf("member" to org), admins = mutableSetOf("admin" to org))
    val license = FakeLicenseProvider()
    val notifier = FakeNotifier()
    val clock = FixedClock()
    val settings = BillingSettings(stripeEnabled, "https://app.ostrom.run/")
    val limits = GetPlanLimitsUseCaseImpl(repository, license, settings, clock)
    val overview = GetBillingOverviewUseCaseImpl(repository, access, limits, settings, clock)
    val checkout = StartCheckoutUseCaseImpl(repository, access, stripe, settings, clock)
    val portal = OpenCustomerPortalUseCaseImpl(repository, access, stripe, settings)
    val sync = SyncSubscriptionUseCaseImpl(repository, stripe, clock)
    val changePlan = ChangePlanUseCaseImpl(repository, access, stripe, sync, settings, clock)
    val cancellation = SetCancellationUseCaseImpl(repository, access, stripe, sync, settings)
    val invoices = ListInvoicesUseCaseImpl(repository, access, stripe)
    val backfill = BackfillActivePeopleUseCaseImpl(repository, clock)
    val events = HandleStripeEventUseCaseImpl(repository, sync, notifier, backfill, clock)
    val activity = RecordMemberActivityUseCaseImpl(repository)
    val usage = RecordAgentUsageUseCaseImpl(repository, limits, notifier, clock)
    val entitlements = CheckEntitlementUseCaseImpl(repository, limits, settings, clock)
    val failures = mutableListOf<String>()
    val outbox = ProcessOutboxUseCaseImpl(repository, stripe, clock) { task, error -> failures += "${task.kind} ${error.message}" }

    /** Stores a live subscription of [plan] and makes Stripe return it. */
    suspend fun subscribe(plan: Plan = Plan.TEAM, interval: BillingInterval = BillingInterval.MONTH, committed: Int = 0): OrgBilling {
        val billing = OrgBilling(
            orgId = org,
            stripeCustomerId = "cus_1",
            stripeSubscriptionId = "sub_1",
            subscriptionItemId = "si_1",
            plan = plan,
            interval = interval,
            status = SubscriptionStatus.ACTIVE,
            currentPeriodStart = Instant.parse("2026-10-15T00:00:00Z"),
            currentPeriodEnd = Instant.parse("2026-11-15T00:00:00Z"),
            committedPeople = committed,
            updatedAt = NOW,
        )
        repository.save(billing)
        stripe.subscription = stripeSubscription(
            lookupKey = "ostrom_${plan.key}_${if (interval == BillingInterval.MONTH) "monthly" else "yearly"}",
            quantity = if (interval == BillingInterval.YEAR) committed else null,
        )
        return billing
    }

    fun event(type: String, id: String = "evt_1", customer: String? = "cus_1", subscription: String? = "sub_1", org: String? = null, url: String? = null) =
        StripeEvent(id, type, NOW, customer, subscription, org, url)
}

private val PERIOD = Instant.parse("2026-10-15T00:00:00Z")
private val MONTH = Instant.parse("2026-10-01T00:00:00Z")

class UseCasesTest {
    // Limits ------------------------------------------------------------------------------------------------------

    @Test
    fun anOrganizationWithoutBillingIsOnFree() = runTest {
        val world = World()
        assertEquals(PlanLimits(5, 5, 300, sso = false, policies = false, receiptRetentionDays = 30), world.limits(world.org))
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
        assertEquals(PlanLimits(null, null, null, sso = false, policies = false, receiptRetentionDays = 30), world.limits(world.org))
    }

    @Test
    fun selfHostedLicenseGrantsItsFeaturesAndPeople() = runTest {
        val world = World(stripeEnabled = false)
        world.license.grant = LicenseGrant(setOf("sso", "policies", "retention"), maxPeople = 50, expiresAt = null)
        assertEquals(PlanLimits(50, null, null, sso = true, policies = true, receiptRetentionDays = null), world.limits(world.org))
    }

    // Overview ----------------------------------------------------------------------------------------------------

    @Test
    fun theOverviewShowsPlanPeriodPeopleUsageAndAlerts() = runTest {
        val world = World()
        world.subscribe(Plan.BUSINESS)
        world.repository.recordActivePerson(world.org, PERIOD, "a", NOW)
        world.repository.addUsage(world.org, PERIOD, 3_600, 1)
        world.repository.recordAlert(world.org, PERIOD, 80, NOW)
        val overview = world.overview("member", world.org)
        assertEquals(Plan.BUSINESS, overview.effectivePlan)
        assertEquals(PERIOD, overview.period.start)
        assertEquals(1, overview.activePeople)
        assertEquals(3_600, overview.usage.agentSeconds)
        assertEquals(listOf(80), overview.alerts)
        assertTrue(overview.stripeEnabled)
    }

    @Test
    fun theOverviewOfANewOrganizationIsFreeAndTheCalendarMonth() = runTest {
        val world = World()
        val overview = world.overview("member", world.org)
        assertEquals(Plan.FREE, overview.billing.plan)
        assertEquals(MONTH, overview.period.start)
    }

    @Test
    fun aNonMemberCannotSeeTheBilling() = runTest {
        val world = World()
        assertFailsWith<BillingAccessDeniedException> { world.overview("stranger", world.org) }
    }

    // Checkout ----------------------------------------------------------------------------------------------------

    @Test
    fun aMonthlyCheckoutCreatesTheCustomerOnceAndSendsTheMeteredPrice() = runTest {
        val world = World()
        val url = world.checkout("admin", world.org, "Acme", "a@acme.dev", Plan.TEAM, BillingInterval.MONTH)
        assertEquals("https://checkout.stripe.com/c/pay/cs_test_1", url)
        val request = world.stripe.checkouts.single()
        assertEquals("cus_1", request.customerId)
        assertEquals("price_ostrom_team_monthly", request.priceId)
        assertNull(request.quantity)
        assertEquals("https://app.ostrom.run/settings/billing?checkout=success", request.successUrl)
        assertEquals("https://app.ostrom.run/settings/billing?checkout=canceled", request.cancelUrl)
        assertEquals("cus_1", world.repository.get(world.org)?.stripeCustomerId)
        world.checkout("admin", world.org, "Acme", null, Plan.TEAM, BillingInterval.MONTH)
        assertEquals(1, world.stripe.customers, "The customer is reused on a second checkout")
    }

    @Test
    fun aYearlyCheckoutIsForTheActivePeopleAtLeastOne() = runTest {
        val world = World()
        world.checkout("admin", world.org, null, null, Plan.BUSINESS, BillingInterval.YEAR)
        assertEquals(1, world.stripe.checkouts.last().quantity)
        repeat(4) { world.repository.recordActivePerson(world.org, MONTH, "u$it", NOW) }
        world.checkout("admin", world.org, null, null, Plan.BUSINESS, BillingInterval.YEAR)
        assertEquals(4, world.stripe.checkouts.last().quantity)
    }

    @Test
    fun checkoutRefusals() = runTest {
        val world = World()
        assertFailsWith<BillingAdminRequiredException> { world.checkout("member", world.org, null, null, Plan.TEAM, BillingInterval.MONTH) }
        assertFailsWith<PlanNotPurchasableException> { world.checkout("admin", world.org, null, null, Plan.ENTERPRISE, BillingInterval.MONTH) }
        assertFailsWith<PlanNotPurchasableException> { world.checkout("admin", world.org, null, null, Plan.FREE, BillingInterval.MONTH) }
        world.subscribe()
        assertFailsWith<SubscriptionAlreadyActiveException> { world.checkout("admin", world.org, null, null, Plan.BUSINESS, BillingInterval.MONTH) }
        assertFailsWith<BillingNotConfiguredException> {
            World(stripeEnabled = false).let { it.checkout("admin", it.org, null, null, Plan.TEAM, BillingInterval.MONTH) }
        }
    }

    @Test
    fun aCanceledOrganizationBuysAgainWithItsCustomer() = runTest {
        val world = World()
        world.repository.save(world.subscribe().copy(status = SubscriptionStatus.CANCELED))
        world.checkout("admin", world.org, null, null, Plan.TEAM, BillingInterval.MONTH)
        assertEquals("cus_1", world.stripe.checkouts.single().customerId)
        assertEquals(0, world.stripe.customers)
    }

    @Test
    fun enterpriseRefusalSaysToContactSales() {
        assertEquals("Enterprise is sold on quote: contact sales@ostrom.run.", PlanNotPurchasableException(Plan.ENTERPRISE).message)
        assertEquals("The free plan cannot be bought.", PlanNotPurchasableException(Plan.FREE).message)
    }

    // Portal, invoices ----------------------------------------------------------------------------------------------

    @Test
    fun thePortalOpensForACustomerOnly() = runTest {
        val world = World()
        assertFailsWith<NoActiveSubscriptionException> { world.portal("admin", world.org) }
        world.subscribe()
        assertEquals("https://billing.stripe.com/p/session/test_1", world.portal("admin", world.org))
        assertEquals("portal cus_1 https://app.ostrom.run/settings/billing", world.stripe.calls.last())
        assertFailsWith<BillingAdminRequiredException> { world.portal("member", world.org) }
        assertFailsWith<BillingNotConfiguredException> { World(stripeEnabled = false).let { it.portal("admin", it.org) } }
    }

    @Test
    fun invoicesAreBoundedAndEmptyBeforeTheFirstPurchase() = runTest {
        val world = World()
        assertEquals(emptyList(), world.invoices("admin", world.org, 10))
        world.subscribe()
        world.stripe.invoices = listOf(Invoice("in_1", "A-1", "paid", 4_200, 700, "usd", NOW, "https://pay", "https://pdf"))
        assertEquals("in_1", world.invoices("admin", world.org, 500).single().id)
        assertEquals("invoices cus_1 100", world.stripe.calls.last())
        world.invoices("admin", world.org, 0)
        assertEquals("invoices cus_1 1", world.stripe.calls.last())
    }

    // Change plan, cancel, resume ---------------------------------------------------------------------------------------

    @Test
    fun upgradingToBusinessIsInvoicedNow() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM)
        world.stripe.subscription = stripeSubscription("ostrom_business_monthly")
        val after = world.changePlan("admin", world.org, Plan.BUSINESS, BillingInterval.MONTH)
        assertTrue("changePrice sub_1 si_1 price_ostrom_business_monthly null always_invoice" in world.stripe.calls)
        assertEquals(Plan.BUSINESS, after.plan)
        assertEquals(Plan.BUSINESS, world.repository.get(world.org)?.plan)
    }

    @Test
    fun downgradingIsCreditedOnTheNextInvoice() = runTest {
        val world = World()
        world.subscribe(Plan.BUSINESS)
        world.changePlan("admin", world.org, Plan.TEAM, BillingInterval.MONTH)
        assertTrue("changePrice sub_1 si_1 price_ostrom_team_monthly null create_prorations" in world.stripe.calls)
    }

    @Test
    fun goingYearlyCommitsTheActivePeopleAndIsInvoicedNow() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM)
        repeat(3) { world.repository.recordActivePerson(world.org, PERIOD, "u$it", NOW) }
        world.stripe.subscription = stripeSubscription("ostrom_team_yearly", quantity = 3)
        val after = world.changePlan("admin", world.org, Plan.TEAM, BillingInterval.YEAR)
        assertTrue("changePrice sub_1 si_1 price_ostrom_team_yearly 3 always_invoice" in world.stripe.calls)
        assertEquals(3, after.committedPeople)
    }

    @Test
    fun aYearlyChangeKeepsTheCommittedPeople() = runTest {
        val world = World()
        world.subscribe(Plan.BUSINESS, BillingInterval.YEAR, committed = 8)
        world.changePlan("admin", world.org, Plan.TEAM, BillingInterval.YEAR)
        assertTrue("changePrice sub_1 si_1 price_ostrom_team_yearly 8 create_prorations" in world.stripe.calls)
    }

    @Test
    fun theSamePlanChangesNothing() = runTest {
        val world = World()
        val billing = world.subscribe(Plan.TEAM)
        assertEquals(billing, world.changePlan("admin", world.org, Plan.TEAM, BillingInterval.MONTH))
        assertTrue(world.stripe.calls.none { it.startsWith("changePrice") })
    }

    @Test
    fun changePlanRefusals() = runTest {
        val world = World()
        assertFailsWith<NoActiveSubscriptionException> { world.changePlan("admin", world.org, Plan.BUSINESS, BillingInterval.MONTH) }
        world.repository.save(world.subscribe().copy(subscriptionItemId = null))
        assertFailsWith<SubscriptionStateException> { world.changePlan("admin", world.org, Plan.BUSINESS, BillingInterval.MONTH) }
        assertFailsWith<PlanNotPurchasableException> { world.changePlan("admin", world.org, Plan.ENTERPRISE, BillingInterval.MONTH) }
        assertFailsWith<BillingAdminRequiredException> { world.changePlan("member", world.org, Plan.BUSINESS, BillingInterval.MONTH) }
        assertFailsWith<BillingNotConfiguredException> {
            World(stripeEnabled = false).let { it.changePlan("admin", it.org, Plan.BUSINESS, BillingInterval.MONTH) }
        }
    }

    @Test
    fun cancelThenResume() = runTest {
        val world = World()
        world.subscribe()
        world.stripe.subscription = stripeSubscription(cancelAtPeriodEnd = true)
        assertTrue(world.cancellation("admin", world.org, cancel = true).cancelAtPeriodEnd)
        assertEquals("cancelAtPeriodEnd sub_1 true", world.stripe.calls[world.stripe.calls.size - 2])
        assertFailsWith<SubscriptionStateException> { world.cancellation("admin", world.org, cancel = true) }
        world.stripe.subscription = stripeSubscription(cancelAtPeriodEnd = false)
        assertEquals(false, world.cancellation("admin", world.org, cancel = false).cancelAtPeriodEnd)
        assertFailsWith<SubscriptionStateException> { world.cancellation("admin", world.org, cancel = false) }
    }

    @Test
    fun cancellationRefusals() = runTest {
        val world = World()
        assertFailsWith<NoActiveSubscriptionException> { world.cancellation("admin", world.org, cancel = true) }
        assertFailsWith<BillingAdminRequiredException> { world.cancellation("member", world.org, cancel = true) }
        assertFailsWith<BillingNotConfiguredException> { World(stripeEnabled = false).let { it.cancellation("admin", it.org, cancel = true) } }
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
    fun anActiveSubscriptionClearsThePaymentIssueAndAPastDueOneKeepsIt() = runTest {
        val world = World()
        val issue = PaymentIssue(PaymentIssueKind.FAILED, "https://pay", NOW)
        val billing = world.subscribe().copy(paymentIssue = issue)
        assertNull(world.sync(billing, "sub_1").paymentIssue)
        world.stripe.subscription = stripeSubscription(status = SubscriptionStatus.PAST_DUE)
        assertEquals(issue, world.sync(billing, "sub_1").paymentIssue)
    }

    // Webhook events ---------------------------------------------------------------------------------------------------

    @Test
    fun aCompletedCheckoutActivatesThePlanAndBackfillsThePeopleOfTheMonth() = runTest {
        val world = World()
        world.repository.recordActivePerson(world.org, MONTH, "early", NOW - 1.days)
        world.stripe.subscription = stripeSubscription("ostrom_team_monthly")
        val outcome = world.events(world.event("checkout.session.completed", customer = "cus_1", org = world.org.toString()))
        assertEquals(EventOutcome.PROCESSED, outcome)
        val billing = world.repository.get(world.org)
        assertEquals(Plan.TEAM, billing?.effectivePlan)
        assertEquals("cus_1", billing?.stripeCustomerId)
        assertEquals(listOf("early"), world.repository.listActivePeople(world.org, PERIOD, 10))
        assertEquals(OutboxKind.ACTIVE_PERSON, world.repository.tasks.single().kind)
        assertEquals("ostrom-${world.org}-${PERIOD.epochSeconds}-early", world.repository.tasks.single().identifier)
    }

    @Test
    fun aCheckoutOfAKnownCustomerKeepsItsCustomer() = runTest {
        val world = World()
        world.subscribe()
        assertEquals(EventOutcome.PROCESSED, world.events(world.event("checkout.session.completed", customer = null, org = world.org.toString())))
        assertEquals("cus_1", world.repository.get(world.org)?.stripeCustomerId)
    }

    @Test
    fun anEventIsProcessedOnce() = runTest {
        val world = World()
        world.subscribe()
        assertEquals(EventOutcome.PROCESSED, world.events(world.event("customer.subscription.updated")))
        assertEquals(EventOutcome.DUPLICATE, world.events(world.event("customer.subscription.updated")))
        assertEquals(1, world.stripe.calls.count { it.startsWith("getSubscription") })
        assertEquals("customer.subscription.updated", world.repository.events["evt_1"])
    }

    @Test
    fun subscriptionEventsReReadTheSubscription() = runTest {
        for (type in listOf(
            "customer.subscription.created", "customer.subscription.deleted", "customer.subscription.paused",
            "customer.subscription.resumed", "checkout.session.async_payment_failed", "invoice.finalized",
            "checkout.session.async_payment_succeeded",
        )) {
            val world = World()
            world.subscribe()
            world.stripe.subscription = stripeSubscription(status = SubscriptionStatus.CANCELED)
            assertEquals(EventOutcome.PROCESSED, world.events(world.event(type)), type)
            assertEquals(Plan.FREE, world.repository.get(world.org)?.effectivePlan, type)
        }
    }

    @Test
    fun aFailedPaymentIsShownAndNotifiedAndAPaidInvoiceClearsIt() = runTest {
        val world = World()
        world.subscribe()
        world.stripe.subscription = stripeSubscription(status = SubscriptionStatus.PAST_DUE)
        world.events(world.event("invoice.payment_failed", id = "evt_a", url = "https://invoice.stripe.com/i/1"))
        val issue = PaymentIssue(PaymentIssueKind.FAILED, "https://invoice.stripe.com/i/1", NOW)
        assertEquals(issue, world.repository.get(world.org)?.paymentIssue)
        assertEquals(listOf(world.org to issue), world.notifier.issues)
        assertEquals(Plan.TEAM, world.repository.get(world.org)?.effectivePlan, "Past due keeps the plan during retries")
        world.events(world.event("invoice.payment_action_required", id = "evt_b", url = "https://invoice.stripe.com/i/2"))
        assertEquals(PaymentIssueKind.ACTION_REQUIRED, world.repository.get(world.org)?.paymentIssue?.kind)
        world.events(world.event("invoice.paid", id = "evt_c"))
        assertNull(world.repository.get(world.org)?.paymentIssue)
    }

    @Test
    fun eventsWithoutOrganizationOrSubscriptionAreIgnoredButRecorded() = runTest {
        val world = World()
        assertEquals(EventOutcome.IGNORED, world.events(world.event("customer.subscription.updated", id = "e1", customer = "cus_unknown")))
        assertEquals(EventOutcome.IGNORED, world.events(world.event("customer.subscription.updated", id = "e2", customer = null, org = "not-a-uuid")))
        assertEquals(EventOutcome.IGNORED, world.events(world.event("checkout.session.completed", id = "e3", customer = null, subscription = null, org = world.org.toString())))
        world.subscribe()
        assertEquals(EventOutcome.IGNORED, world.events(world.event("customer.updated", id = "e4", subscription = null)))
        assertEquals(setOf("e1", "e2", "e3", "e4"), world.repository.events.keys)
    }

    @Test
    fun anEventFindsTheOrganizationByMetadataWhenTheCustomerIsNew() = runTest {
        val world = World()
        world.stripe.subscription = stripeSubscription()
        world.events(world.event("customer.subscription.created", customer = "cus_new", org = world.org.toString()))
        assertEquals("cus_1", world.repository.get(world.org)?.stripeCustomerId, "The subscription's customer is stored")
    }

    // Activity, backfill, outbox ------------------------------------------------------------------------------------------

    @Test
    fun aMonthlyPersonIsMeteredOncePerPeriod() = runTest {
        val world = World()
        world.subscribe()
        world.activity(world.org, "alice", NOW)
        world.activity(world.org, "alice", NOW + 1.hours)
        world.activity(world.org, "bob", NOW)
        assertEquals(2, world.repository.tasks.size)
        assertEquals(2, world.outbox(10))
        assertEquals(listOf("cus_1", "cus_1"), world.stripe.meterEvents.map { it.first })
        assertEquals(0, world.outbox(10), "Done tasks are not sent again")
    }

    @Test
    fun aFreeOrganizationCountsItsPeopleWithoutStripe() = runTest {
        val world = World()
        world.activity(world.org, "alice", NOW)
        assertEquals(1, world.repository.countActivePeople(world.org, MONTH))
        assertTrue(world.repository.tasks.isEmpty())
    }

    @Test
    fun aYearlyTrueUpHappensOnlyAboveTheCommittedPeople() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM, BillingInterval.YEAR, committed = 2)
        world.activity(world.org, "a", NOW)
        world.activity(world.org, "b", NOW)
        assertTrue(world.repository.tasks.isEmpty())
        world.activity(world.org, "c", NOW)
        assertEquals(OutboxKind.TRUE_UP, world.repository.tasks.single().kind)
        world.outbox(10)
        assertTrue("setQuantity sub_1 si_1 3" in world.stripe.calls)
        assertEquals(3, world.repository.get(world.org)?.committedPeople)
    }

    @Test
    fun aTrueUpAlreadyCoveredSendsNothing() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM, BillingInterval.YEAR, committed = 0)
        world.activity(world.org, "a", NOW)
        world.repository.save(world.repository.get(world.org)!!.copy(committedPeople = 5))
        world.outbox(10)
        assertTrue(world.stripe.calls.none { it.startsWith("setQuantity") })
        world.repository.save(world.repository.get(world.org)!!.copy(committedPeople = 0, subscriptionItemId = null))
        world.repository.tasks.clear()
        world.activity(world.org, "b", NOW)
        world.outbox(10)
        assertTrue(world.stripe.calls.none { it.startsWith("setQuantity") })
    }

    @Test
    fun aTrueUpOfASubscriptionTurnedMonthlySendsNothing() = runTest {
        val world = World()
        world.subscribe(Plan.TEAM, BillingInterval.YEAR, committed = 0)
        world.activity(world.org, "a", NOW)
        world.repository.save(world.repository.get(world.org)!!.copy(interval = BillingInterval.MONTH))
        world.outbox(10)
        assertTrue(world.stripe.calls.none { it.startsWith("setQuantity") })
    }

    @Test
    fun aSubscriptionWithoutIntervalQueuesNothing() = runTest {
        val world = World()
        world.repository.save(world.subscribe().copy(interval = null))
        world.activity(world.org, "a", NOW)
        assertTrue(world.repository.tasks.isEmpty())
    }

    @Test
    fun aFailedTaskIsRetriedLaterWithAGrowingDelay() = runTest {
        val world = World()
        world.subscribe()
        world.activity(world.org, "alice", NOW)
        world.stripe.failure = IllegalStateException("stripe down")
        assertEquals(0, world.outbox(10))
        assertEquals(listOf("ACTIVE_PERSON stripe down"), world.failures)
        val task = world.repository.tasks.single()
        assertEquals(NOW + 30.seconds, world.repository.retries[task.id])
        world.clock.advance(31.seconds)
        world.outbox(10)
        assertEquals(NOW + 31.seconds + 1.minutes, world.repository.retries[task.id])
        repeat(10) { world.clock.advance(2.hours); world.outbox(10) }
        assertEquals(world.clock.now + 60.minutes, world.repository.retries[task.id], "The delay stops growing at one hour")
        world.stripe.failure = null
        world.clock.advance(2.hours)
        assertEquals(1, world.outbox(10))
    }

    @Test
    fun theOutboxPropagatesCancellation() = runTest {
        val world = World()
        world.subscribe()
        world.activity(world.org, "alice", NOW)
        world.stripe.failure = CancellationException("stop")
        assertFailsWith<CancellationException> { world.outbox(10) }
    }

    @Test
    fun aTaskOfAnOrganizationThatStoppedPayingIsDropped() = runTest {
        val world = World()
        world.subscribe()
        world.activity(world.org, "alice", NOW)
        world.repository.save(world.repository.get(world.org)!!.copy(status = SubscriptionStatus.CANCELED))
        assertEquals(1, world.outbox(10))
        assertTrue(world.stripe.meterEvents.isEmpty())
    }

    @Test
    fun backfillDoesNothingWithoutALiveSubscription() = runTest {
        val world = World()
        world.repository.recordActivePerson(world.org, MONTH, "a", NOW)
        world.backfill(OrgBilling(orgId = world.org, updatedAt = NOW))
        assertTrue(world.repository.tasks.isEmpty())
    }

    @Test
    fun backfillSkipsPeopleAlreadyCountedInThePeriod() = runTest {
        val world = World()
        val billing = world.subscribe()
        world.repository.recordActivePerson(world.org, MONTH, "a", NOW)
        world.repository.recordActivePerson(world.org, PERIOD, "a", NOW)
        world.backfill(billing)
        assertTrue(world.repository.tasks.isEmpty())
    }

    // Usage and alerts ---------------------------------------------------------------------------------------------------

    @Test
    fun usageRaisesEightyThenHundredPercentOnce() = runTest {
        val world = World()
        world.usage(world.org, 239 * 3600L, 10)
        assertTrue(world.notifier.hours.isEmpty())
        world.usage(world.org, 3600L, 1)
        assertEquals(listOf(world.org to 80), world.notifier.hours)
        world.usage(world.org, 3600L, 0)
        assertEquals(1, world.notifier.hours.size, "80 % is raised once")
        val usage = world.usage(world.org, 60 * 3600L, 1)
        assertEquals(listOf(80, 100), world.notifier.hours.map { it.second })
        assertEquals(301, usage.agentHours)
        assertEquals(12, usage.sessions)
    }

    @Test
    fun usageWithoutHourLimitRaisesNothing() = runTest {
        val world = World(stripeEnabled = false)
        world.usage(world.org, 10_000 * 3600L, 1)
        assertTrue(world.notifier.hours.isEmpty())
    }

    @Test
    fun negativeUsageIsRefused() = runTest {
        val world = World()
        assertFailsWith<IllegalArgumentException> { world.usage(world.org, -1, 0) }
        assertFailsWith<IllegalArgumentException> { world.usage(world.org, 0, -1) }
    }

    // Entitlements -------------------------------------------------------------------------------------------------------

    @Test
    fun theSixthPersonOfFreeIsRefusedWithWhereToUpgrade() = runTest {
        val world = World()
        world.entitlements(world.org, Entitlement.AddPerson(4))
        val error = assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.AddPerson(5)) }
        assertEquals(
            "The Free plan includes 5 people and this organization has 5. Upgrade at https://app.ostrom.run/settings/billing.",
            error.message,
        )
    }

    @Test
    fun theSixthCloudAgentOfFreeIsRefused() = runTest {
        val world = World()
        world.entitlements(world.org, Entitlement.StartCloudAgent(4))
        val error = assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.StartCloudAgent(5)) }
        assertEquals(
            "The Free plan runs 5 cloud agents at the same time and 5 are running. Wait for one to finish, or upgrade at " +
                "https://app.ostrom.run/settings/billing.",
            error.message,
        )
    }

    @Test
    fun noCloudAgentStartsOnceTheHoursAreUsed() = runTest {
        val world = World()
        world.repository.addUsage(world.org, MONTH, 300 * 3600L, 40)
        val error = assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.StartCloudAgent(0)) }
        assertEquals(
            "This organization used the 300 cloud agent hours of its plan for this period (it renews on 2026-11-01). " +
                "Upgrade at https://app.ostrom.run/settings/billing.",
            error.message,
        )
    }

    @Test
    fun ssoAndPoliciesNeedBusiness() = runTest {
        val world = World()
        assertEquals(
            "Single sign-on is part of the Business plan. Upgrade at https://app.ostrom.run/settings/billing.",
            assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.Sso) }.message,
        )
        assertEquals(
            "Organization policies are part of the Business plan. Upgrade at https://app.ostrom.run/settings/billing.",
            assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.Policies) }.message,
        )
        world.subscribe(Plan.BUSINESS)
        world.entitlements(world.org, Entitlement.Sso)
        world.entitlements(world.org, Entitlement.Policies)
        world.entitlements(world.org, Entitlement.AddPerson(500))
        world.entitlements(world.org, Entitlement.StartCloudAgent(9))
    }

    @Test
    fun enterpriseAndSelfHostedSkipTheCountLimits() = runTest {
        val world = World(stripeEnabled = false)
        world.entitlements(world.org, Entitlement.AddPerson(10_000))
        world.entitlements(world.org, Entitlement.StartCloudAgent(10_000))
        val error = assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.Sso) }
        assertEquals("Single sign-on is part of the Business plan. Contact sales@ostrom.run for a license.", error.message)
        world.license.grant = LicenseGrant(emptySet(), maxPeople = 3, expiresAt = null)
        assertEquals(
            "This instance's license includes 3 people and this organization has 3. Contact sales@ostrom.run for a license.",
            assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.AddPerson(3)) }.message,
        )
    }
}
