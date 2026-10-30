// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.Entitlement
import dev.ostrom.server.billing.domain.EventOutcome
import dev.ostrom.server.billing.domain.LicenseGrant
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.OutboxKind
import dev.ostrom.server.billing.domain.OutboxTask
import dev.ostrom.server.billing.domain.PaymentIssue
import dev.ostrom.server.billing.domain.PaymentIssueKind
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.PlanLimitException
import dev.ostrom.server.billing.domain.SubscriptionStatus
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
import kotlin.uuid.Uuid

class StripeEventsUsageAndLimitsTest {
    // Webhook events ----------------------------------------------------------------------------------------------

    @Test
    fun aCompletedCheckoutActivatesThePlanAndBackfillsThePeopleOfTheMonth() = runTest {
        val world = World()
        world.opensACheckout()
        world.repository.recordActivePerson(world.org, MONTH, "early", NOW - 1.days)
        world.stripe.subscription = stripeSubscription("ostrom_team_monthly")
        val outcome = world.events(
            world.event("checkout.session.completed").copy(customerId = "cus_1", orgId = world.org.toString())
        )
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
        assertEquals(
            EventOutcome.PROCESSED,
            world.events(
                world.event("checkout.session.completed").copy(customerId = null, orgId = world.org.toString())
            ),
        )
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
        world.events(
            world.event(
                "invoice.payment_failed",
                id = "evt_a",
            ).copy(hostedInvoiceUrl = "https://invoice.stripe.com/i/1")
        )
        val issue = PaymentIssue(PaymentIssueKind.FAILED, "https://invoice.stripe.com/i/1", NOW)
        assertEquals(issue, world.repository.get(world.org)?.paymentIssue)
        assertEquals(listOf(world.org to issue), world.notifier.issues)
        assertEquals(
            Plan.TEAM,
            world.repository.get(world.org)?.effectivePlan,
            "Past due keeps the plan during retries",
        )
        world.events(
            world.event(
                "invoice.payment_action_required",
                id = "evt_b",
            ).copy(hostedInvoiceUrl = "https://invoice.stripe.com/i/2")
        )
        assertEquals(PaymentIssueKind.ACTION_REQUIRED, world.repository.get(world.org)?.paymentIssue?.kind)
        world.events(world.event("invoice.paid", id = "evt_c"))
        assertNull(world.repository.get(world.org)?.paymentIssue)
    }

    @Test
    fun eventsWithoutOrganizationOrSubscriptionAreIgnoredButRecorded() = runTest {
        val world = World()
        assertEquals(
            EventOutcome.IGNORED,
            world.events(world.event("customer.subscription.updated", id = "e1").copy(customerId = "cus_unknown")),
        )
        world.opensACheckout()
        assertEquals(
            EventOutcome.IGNORED,
            world.events(
                world.event(
                    "checkout.session.completed",
                    id = "e3",
                ).copy(customerId = null, subscriptionId = null, orgId = world.org.toString())
            ),
        )
        world.subscribe()
        assertEquals(
            EventOutcome.IGNORED,
            world.events(world.event("customer.updated", id = "e4").copy(subscriptionId = null)),
        )
        assertEquals(setOf("e1", "e3", "e4"), world.repository.events.keys)
        assertEquals(emptyList(), world.metrics.unknownOrganizations)
    }

    @Test
    fun anEventForAnOrganizationThatDoesNotExistCreatesNothing() = runTest {
        val world = World()
        world.stripe.subscription = stripeSubscription("ostrom_business_monthly")
        val unknown = Uuid.parse("0195f7a2-0000-7000-8000-0000000000ff")
        for ((id, orgId) in listOf("e1" to unknown.toString(), "e2" to "not-a-uuid")) {
            assertEquals(
                EventOutcome.IGNORED_UNKNOWN_ORG,
                world.events(world.event("checkout.session.completed", id = id).copy(customerId = null, orgId = orgId)),
            )
        }
        // A customer Stripe knows but this instance does not is no better: the organization decides.
        assertEquals(
            EventOutcome.IGNORED_UNKNOWN_ORG,
            world.events(
                world.event("customer.subscription.created", id = "e3").copy(customerId = "cus_x", orgId = "$unknown")
            ),
        )
        assertNull(world.repository.get(unknown))
        assertEquals(0, world.stripe.calls.count { it.startsWith("getSubscription") }, "Stripe is not even asked")
        assertEquals(
            listOf(
                "e1 checkout.session.completed",
                "e2 checkout.session.completed",
                "e3 customer.subscription.created",
            ),
            world.metrics.unknownOrganizations,
        )
        assertEquals(setOf("e1", "e2", "e3"), world.repository.events.keys, "Recorded: a replay is a duplicate")
        assertEquals(EventOutcome.DUPLICATE, world.events(world.event("checkout.session.completed", id = "e1")))
    }

    @Test
    fun anEventFindsTheOrganizationByMetadataWhenTheCustomerIsNew() = runTest {
        val world = World()
        world.opensACheckout()
        world.stripe.subscription = stripeSubscription()
        world.events(
            world.event("customer.subscription.created").copy(customerId = "cus_new", orgId = world.org.toString())
        )
        assertEquals(
            "cus_1",
            world.repository.get(world.org)?.stripeCustomerId,
            "The subscription's customer is stored",
        )
    }

    // Activity, backfill, outbox ----------------------------------------------------------------------------------

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
        assertEquals(
            world.clock.now + 60.minutes,
            world.repository.retries[task.id],
            "The delay stops growing at one hour",
        )
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
    fun aPersonActiveBeforeTheSubscriptionEndedStillReachesItsLastInvoice() = runTest {
        val world = World()
        world.subscribe()
        world.activity(world.org, "alice", NOW)
        world.repository.save(world.repository.get(world.org)!!.copy(status = SubscriptionStatus.CANCELED))
        assertEquals(1, world.outbox(10))
        assertEquals("cus_1", world.stripe.meterEvents.single().first)
    }

    @Test
    fun aTrueUpOfAnOrganizationThatStoppedPayingIsDropped() = runTest {
        val world = World()
        world.subscribe(interval = BillingInterval.YEAR, committed = 1)
        world.repository.save(world.repository.get(world.org)!!.copy(status = SubscriptionStatus.CANCELED))
        world.repository.enqueue(OutboxTask(Uuid.random(), world.org, OutboxKind.TRUE_UP, "t-1", NOW, 0))
        assertEquals(1, world.outbox(10))
        assertTrue(world.stripe.calls.none { it.startsWith("setQuantity") })
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

    // Usage and alerts --------------------------------------------------------------------------------------------

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
        assertEquals(301L, usage.agentHours)
        assertEquals(12L, usage.sessions)
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

    // Entitlements ------------------------------------------------------------------------------------------------

    @Test
    fun theSixthPersonOfFreeIsRefusedWithWhereToUpgrade() = runTest {
        val world = World()
        world.entitlements(world.org, Entitlement.AddPerson(4))
        val error = assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.AddPerson(5)) }
        assertEquals(
            "The Free plan includes 5 people and this organization has 5. " +
                "Upgrade at https://app.ostrom.run/settings/billing.",
            error.message,
        )
    }

    @Test
    fun theSixthCloudAgentOfFreeIsRefused() = runTest {
        val world = World()
        world.entitlements(world.org, Entitlement.StartCloudAgent(4))
        val error = assertFailsWith<PlanLimitException> {
            world.entitlements(world.org, Entitlement.StartCloudAgent(5))
        }
        assertEquals(
            "The Free plan runs 5 cloud agents at the same time and 5 are running. " +
                "Wait for one to finish, or upgrade at https://app.ostrom.run/settings/billing.",
            error.message,
        )
    }

    @Test
    fun noCloudAgentStartsOnceTheHoursAreUsed() = runTest {
        val world = World()
        world.repository.addUsage(world.org, MONTH, 300 * 3600L, 40)
        val error = assertFailsWith<PlanLimitException> {
            world.entitlements(world.org, Entitlement.StartCloudAgent(0))
        }
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
        assertEquals(
            "Single sign-on is part of the Business plan. Contact sales@ostrom.run for a license.",
            error.message,
        )
        world.license.grant = LicenseGrant(emptySet(), maxPeople = 3, expiresAt = null)
        assertEquals(
            "This instance's license includes 3 people and this organization has 3. " +
                "Contact sales@ostrom.run for a license.",
            assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.AddPerson(3)) }.message,
        )
    }
}
