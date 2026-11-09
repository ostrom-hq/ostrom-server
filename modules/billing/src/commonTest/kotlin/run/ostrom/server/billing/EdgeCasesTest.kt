// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.BillingInterval
import run.ostrom.server.billing.domain.Entitlement
import run.ostrom.server.billing.domain.EventOutcome
import run.ostrom.server.billing.domain.NoActiveSubscriptionException
import run.ostrom.server.billing.domain.NoLicenseProvider
import run.ostrom.server.billing.domain.OrgBilling
import run.ostrom.server.billing.domain.OutboxKind
import run.ostrom.server.billing.domain.OutboxTask
import run.ostrom.server.billing.domain.Plan
import run.ostrom.server.billing.domain.PlanLimitException
import run.ostrom.server.billing.domain.SubscriptionStatus
import run.ostrom.server.billing.domain.live
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.fakes.stripeSubscription
import run.ostrom.server.billing.presentation.toResponse
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The less travelled paths: organizations without a billing row, half-filled rows and odd Stripe answers. */
class EdgeCasesTest {
    @Test
    fun aRunningSubscriptionNeedsARowASubscriptionACustomerAndAStatusThatGrantsIt() {
        val org = Uuid.parse("0195f7a2-0000-7000-8000-000000000001")
        val live = OrgBilling(
            orgId = org,
            stripeCustomerId = "cus_1",
            stripeSubscriptionId = "sub_1",
            status = SubscriptionStatus.ACTIVE,
            updatedAt = NOW,
        )
        assertEquals("sub_1", live.live()?.subscriptionId)
        assertEquals("cus_1", live.live()?.customerId)
        assertTrue(live.hasLiveSubscription)
        assertNull((null as OrgBilling?).live())
        assertNull(live.copy(stripeSubscriptionId = null).live())
        assertNull(live.copy(stripeCustomerId = null).live())
        assertNull(live.copy(status = null).live())
        assertNull(live.copy(status = SubscriptionStatus.CANCELED).live())
        assertFalse(live.copy(status = null).hasLiveSubscription)
    }

    @Test
    fun anOrganizationWithoutABillingRowIsOnFree() = runTest {
        val world = World()
        assertEquals(5, world.limits(world.org).maxPeople)
        val error = assertFailsWith<PlanLimitException> { world.entitlements(world.org, Entitlement.AddPerson(5)) }
        assertTrue(error.message.startsWith("The Free plan includes 5 people"), error.message)
        world.activity(world.org, "alice", NOW)
        assertEquals(emptyList(), world.repository.tasks)
        assertEquals(emptyList(), world.invoices(world.admin, world.org, 10))
        assertFailsWith<NoActiveSubscriptionException> { world.portal(world.admin, world.org) }
    }

    @Test
    fun aRowWithoutCustomerHasNoInvoiceNoPortalAndGetsACustomerAtCheckout() = runTest {
        val world = World()
        world.repository.save(OrgBilling(orgId = world.org, updatedAt = NOW))
        assertEquals(emptyList(), world.invoices(world.admin, world.org, 10))
        assertFailsWith<NoActiveSubscriptionException> { world.portal(world.admin, world.org) }
        world.checkout(world.admin, world.org, Plan.TEAM, BillingInterval.MONTH)
        assertEquals(1, world.stripe.customers)
        assertEquals("cus_1", world.repository.get(world.org)?.stripeCustomerId)
    }

    @Test
    fun aPriceThatIsNotOursKeepsThePlanAndTheInterval() = runTest {
        val world = World()
        val billing = world.subscribe(Plan.BUSINESS, BillingInterval.YEAR, committed = 2)
        world.stripe.subscription = stripeSubscription(lookupKey = "someone_elses_price")
        val synced = world.sync(billing, "sub_1")
        assertEquals(Plan.BUSINESS, synced.plan)
        assertEquals(BillingInterval.YEAR, synced.interval)
        world.stripe.subscription = stripeSubscription(lookupKey = null)
        assertEquals(Plan.BUSINESS, world.sync(billing, "sub_1").plan)
    }

    @Test
    fun anEventWithoutSubscriptionUsesTheOrganizationsOne() = runTest {
        val world = World()
        world.subscribe()
        val outcome = world.events(world.event("invoice.paid").copy(subscriptionId = null))
        assertEquals(EventOutcome.PROCESSED, outcome)
        world.repository.save(OrgBilling(orgId = world.org, stripeCustomerId = "cus_1", updatedAt = NOW))
        val noSubscription = world.events(world.event("invoice.paid", "evt_2").copy(subscriptionId = null))
        assertEquals(EventOutcome.IGNORED, noSubscription)
        val stranger = World().events(World().event("invoice.paid", "evt_3").copy(subscriptionId = null))
        assertEquals(EventOutcome.IGNORED, stranger, "No subscription and no organization")
    }

    @Test
    fun aFreeOrganizationsActivityQueuesNothingForStripe() = runTest {
        val world = World()
        world.repository.save(OrgBilling(orgId = world.org, stripeCustomerId = "cus_1", updatedAt = NOW))
        world.activity(world.org, "alice", NOW)
        assertEquals(emptyList(), world.repository.tasks)
    }

    @Test
    fun aLiveSubscriptionWithoutIntervalQueuesNothing() = runTest {
        val world = World()
        world.repository.save(world.subscribe().copy(interval = null))
        world.activity(world.org, "alice", NOW)
        assertEquals(emptyList(), world.repository.tasks)
    }

    @Test
    fun aTaskOfAnOrganizationThatStoppedPayingIsDroppedWithoutCallingStripe() = runTest {
        val world = World()
        world.repository.enqueue(OutboxTask(Uuid.random(), world.org, OutboxKind.ACTIVE_PERSON, "id-1", NOW, 0))
        assertEquals(1, world.outbox(10))
        assertEquals(emptyList(), world.stripe.calls)
    }

    @Test
    fun aSelfHostedOverviewHasNoPriceAndNoHourLimit() = runTest {
        val world = World(stripeEnabled = false)
        val response = world.overview(world.member, world.org).toResponse()
        assertEquals(emptyList(), response.prices)
        assertNull(response.usage.percentOfHours)
        assertFalse(response.purchasable)
    }

    @Test
    fun noLicenseMeansNoGrant() = runTest {
        assertNull(NoLicenseProvider().current())
    }

    @Test
    fun aFailedTaskIsLoggedByIdKindAndErrorClassOnly() {
        val lines = mutableListOf<String>()
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
                lines += "$level $message $fields"
            }

            override fun with(fields: Map<String, Any?>): Logger = this
        }
        val id = Uuid.parse("0195f7a2-0000-7000-8000-0000000000f1")
        outboxFailureLogger(logger)(
            OutboxTask(id, id, OutboxKind.TRUE_UP, "x", NOW, 0),
            IllegalStateException("sk_test_secret leaked"),
        )
        assertEquals(listOf("WARN stripe task failed {task_id=$id, kind=true_up, error=IllegalStateException}"), lines)
    }
}
