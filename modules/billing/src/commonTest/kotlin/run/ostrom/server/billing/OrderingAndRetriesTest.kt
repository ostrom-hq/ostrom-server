// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.BillingInterval
import run.ostrom.server.billing.domain.BillingNotifier
import run.ostrom.server.billing.domain.EventOutcome
import run.ostrom.server.billing.domain.OutboxKind
import run.ostrom.server.billing.domain.OutboxTask
import run.ostrom.server.billing.domain.PaymentIssue
import run.ostrom.server.billing.domain.PaymentIssueKind
import run.ostrom.server.billing.domain.RecordAgentUsageUseCaseImpl
import run.ostrom.server.billing.domain.SubscriptionStatus
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.fakes.stripeSubscription
import run.ostrom.server.billing.infrastructure.GuardedBillingNotifier
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Events that arrive late or out of order, concurrent writes, and side effects that fail after a commit. */
class OrderingAndRetriesTest {
    @Test
    fun aLateEventOfASubscriptionTheOrganizationReplacedIsIgnored() = runTest {
        val world = World()
        world.subscribe()
        world.stripe.subscription = stripeSubscription(status = SubscriptionStatus.INCOMPLETE_EXPIRED)
            .copy(id = "sub_old")
        val outcome = world.events(world.event("customer.subscription.deleted").copy(subscriptionId = "sub_old"))
        assertEquals(EventOutcome.IGNORED, outcome)
        assertEquals("sub_1", world.repository.get(world.org)?.stripeSubscriptionId)
        assertEquals(SubscriptionStatus.ACTIVE, world.repository.get(world.org)?.status)
        assertTrue(world.stripe.calls.none { it.startsWith("getSubscription") })
    }

    @Test
    fun aNewSubscriptionReplacesOneThatEnded() = runTest {
        val world = World()
        world.repository.save(world.subscribe().copy(status = SubscriptionStatus.CANCELED))
        world.stripe.subscription = stripeSubscription().copy(id = "sub_2")
        val outcome = world.events(world.event("checkout.session.completed").copy(subscriptionId = "sub_2"))
        assertEquals(EventOutcome.PROCESSED, outcome)
        assertEquals("sub_2", world.repository.get(world.org)?.stripeSubscriptionId)
    }

    @Test
    fun aPaymentWaitingForTheCustomerOnAnActiveSubscriptionStaysShown() = runTest {
        val world = World()
        world.subscribe()
        world.events(world.event("invoice.payment_action_required"))
        assertEquals(PaymentIssueKind.ACTION_REQUIRED, world.repository.get(world.org)?.paymentIssue?.kind)
    }

    @Test
    fun aPaymentDeliveredLateDoesNotClearANewerFailure() = runTest {
        val world = World()
        val issue = PaymentIssue(PaymentIssueKind.FAILED, "https://pay", NOW)
        world.repository.save(world.subscribe().copy(paymentIssue = issue))
        world.events(world.event("invoice.paid").copy(created = NOW - 1.hours))
        assertEquals(issue, world.repository.get(world.org)?.paymentIssue)
    }

    @Test
    fun anOlderFailureDeliveredLateDoesNotReplaceANewerOneNorNotify() = runTest {
        val world = World()
        val issue = PaymentIssue(PaymentIssueKind.ACTION_REQUIRED, "https://pay/2", NOW)
        world.repository.save(world.subscribe().copy(paymentIssue = issue))
        world.events(world.event("invoice.payment_failed").copy(created = NOW - 1.hours))
        assertEquals(issue, world.repository.get(world.org)?.paymentIssue)
        assertTrue(world.notifier.issues.isEmpty())
    }

    @Test
    fun anEndedSubscriptionLeavesNothingToPay() = runTest {
        for (status in listOf(SubscriptionStatus.CANCELED, SubscriptionStatus.INCOMPLETE_EXPIRED)) {
            val world = World()
            val issue = PaymentIssue(PaymentIssueKind.FAILED, "https://pay", NOW)
            val billing = world.subscribe().copy(paymentIssue = issue)
            world.stripe.subscription = stripeSubscription(status = status)
            assertEquals(null, world.sync(billing, "sub_1").paymentIssue, status.stripe)
        }
    }

    @Test
    fun aCompletedPurchaseStartsWithoutTheIssueOfAnEarlierSubscription() = runTest {
        val world = World()
        val stale = PaymentIssue(PaymentIssueKind.FAILED, "https://pay/old", NOW - 1.hours)
        world.repository.save(world.subscribe().copy(status = SubscriptionStatus.UNPAID, paymentIssue = stale))
        world.stripe.subscription = stripeSubscription().copy(id = "sub_2")
        world.events(world.event("checkout.session.completed").copy(subscriptionId = "sub_2"))
        assertEquals(null, world.repository.get(world.org)?.paymentIssue)
    }

    @Test
    fun aPriceThatIsNotOursIsReported() = runTest {
        val world = World()
        val billing = world.subscribe()
        world.sync(billing, "sub_1")
        assertTrue(world.unknownPrices.isEmpty())
        world.stripe.subscription = stripeSubscription(lookupKey = "someone_elses_price")
        world.sync(billing, "sub_1")
        assertEquals(listOf<Pair<String, String?>>("sub_1" to "someone_elses_price"), world.unknownPrices.toList())
    }

    @Test
    fun aTrueUpKeepsWhatAWebhookSavedWhileStripeAnswered() = runTest {
        val world = World()
        world.subscribe(interval = BillingInterval.YEAR, committed = 1)
        world.repository.recordActivePerson(world.org, PERIOD, "alice", NOW)
        world.repository.recordActivePerson(world.org, PERIOD, "bob", NOW)
        world.repository.enqueue(OutboxTask(Uuid.random(), world.org, OutboxKind.TRUE_UP, "t-1", NOW, 0))
        world.stripe.whileSettingQuantity = {
            world.repository.save(world.repository.get(world.org)!!.copy(cancelAtPeriodEnd = true))
        }
        assertEquals(1, world.outbox(10))
        val stored = world.repository.get(world.org)!!
        assertEquals(2, stored.committedPeople)
        assertTrue(stored.cancelAtPeriodEnd, "The webhook's cancellation is kept")
    }

    @Test
    fun aClaimedTaskIsHiddenFromTheNextPassUntilItsLeaseEnds() = runTest {
        val world = World()
        val task = OutboxTask(Uuid.random(), world.org, OutboxKind.ACTIVE_PERSON, "a-1", NOW, 0)
        world.repository.enqueue(task)
        world.outbox(10)
        assertEquals(NOW + 10.minutes, world.repository.retries[task.id], "Claimed for the lease")
    }

    @Test
    fun aFailedNotificationIsLoggedAndTheUsageIsCountedOnce() = runTest {
        val world = World()
        val lines = mutableListOf<String>()
        val notifier = GuardedBillingNotifier(Failing(IllegalStateException("smtp down")), recording(lines))
        val usage = RecordAgentUsageUseCaseImpl(world.repository, world.limits, notifier, world.clock)
        assertEquals(240 * 3600L, usage(world.org, 240 * 3600L, 1).agentSeconds)
        notifier.paymentIssue(world.org, PaymentIssue(PaymentIssueKind.FAILED, null, NOW))
        val failed = "WARN billing notification failed {org_id=${world.org}"
        assertEquals(
            listOf(
                "$failed, kind=agent_hours, error=IllegalStateException}",
                "$failed, kind=payment_issue, error=IllegalStateException}",
            ),
            lines,
        )
        assertEquals(listOf(80), world.repository.alerts(world.org, MONTH))
    }

    @Test
    fun aNotificationThatWorksIsPassedOnAndCancellationIsNotSwallowed() = runTest {
        val world = World()
        val lines = mutableListOf<String>()
        GuardedBillingNotifier(world.notifier, recording(lines)).agentHoursReached(world.org, 80, 240, 300)
        assertEquals(listOf(world.org to 80), world.notifier.hours)
        assertTrue(lines.isEmpty())
        val cancelled = GuardedBillingNotifier(Failing(CancellationException("stop")), recording(lines))
        assertFailsWith<CancellationException> { cancelled.agentHoursReached(world.org, 80, 240, 300) }
    }

    @Test
    fun aCustomerAddressNotUpdatedIsLoggedWithoutItsMessage() {
        val lines = mutableListOf<String>()
        customerEmailFailureLogger(recording(lines))("cus_1", IllegalStateException("ada@acme.dev refused"))
        assertEquals(
            listOf("WARN stripe customer email not updated {customer_id=cus_1, error=IllegalStateException}"),
            lines,
        )
    }

    @Test
    fun anUnknownPriceIsLoggedAsAnError() {
        val lines = mutableListOf<String>()
        unknownPriceLogger(recording(lines))("sub_1", null)
        assertEquals(
            listOf("ERROR subscription price not in the catalog {subscription_id=sub_1, lookup_key=null}"),
            lines,
        )
    }

    private class Failing(private val error: Exception) : BillingNotifier {
        override suspend fun agentHoursReached(orgId: Uuid, percent: Int, usedHours: Long, limitHours: Int) {
            throw error
        }

        override suspend fun paymentIssue(orgId: Uuid, issue: PaymentIssue) {
            throw error
        }
    }

    private fun recording(lines: MutableList<String>) = object : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += "$level $message $fields"
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }
}
