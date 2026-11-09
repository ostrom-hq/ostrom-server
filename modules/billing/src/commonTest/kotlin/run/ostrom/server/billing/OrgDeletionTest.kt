// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.OrgBilling
import run.ostrom.server.billing.domain.SubscriptionStatus
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.fakes.stripeSubscription
import run.ostrom.server.billing.infrastructure.BillingOrgDeletionListener
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What ending a subscription asks Stripe: read it, end it, read it back. */
private val ENDED = listOf("getSubscription sub_1", "cancelNow sub_1", "getSubscription sub_1")

/** What billing does when the orgs module deletes an organization. */
class OrgDeletionTest {
    @Test
    fun aDeletedOrganizationsSubscriptionEndsNowOnce() = runTest {
        val world = World()
        world.subscribe()
        val listener = BillingOrgDeletionListener(world.endSubscription)

        listener.deleted(world.org)

        assertEquals(ENDED, world.stripe.calls)
        val ended = world.repository.get(world.org)
        assertEquals(SubscriptionStatus.CANCELED, ended?.status)
        assertEquals("cus_1", ended?.stripeCustomerId, "The billing row stays: accounting keeps it")

        world.stripe.calls.clear()
        listener.deleted(world.org)
        listener.purge(world.org)
        assertEquals(emptyList(), world.stripe.calls, "Told again or purged: nothing cancelled twice, nothing erased")
        assertEquals(ended, world.repository.get(world.org))
    }

    @Test
    fun aSubscriptionStripeAlreadyEndedIsOnlyReadBack() = runTest {
        val world = World()
        world.subscribe()
        world.stripe.subscription = stripeSubscription(status = SubscriptionStatus.CANCELED)

        world.endSubscription(world.org)

        assertEquals(listOf("getSubscription sub_1"), world.stripe.calls)
        assertEquals(SubscriptionStatus.CANCELED, world.repository.get(world.org)?.status)
    }

    @Test
    fun withoutASubscriptionOrWithoutStripeNothingIsCalled() = runTest {
        val free = World()
        free.endSubscription(free.org)
        free.repository.save(OrgBilling(orgId = free.org, updatedAt = NOW))
        free.endSubscription(free.org)
        assertEquals(emptyList(), free.stripe.calls)

        val selfHosted = World(stripeEnabled = false)
        selfHosted.subscribe()
        selfHosted.endSubscription(selfHosted.org)
        assertEquals(emptyList(), selfHosted.stripe.calls)
        assertEquals(SubscriptionStatus.ACTIVE, selfHosted.repository.get(selfHosted.org)?.status)
    }

    @Test
    fun aSubscriptionInAnyStatusButEndedIsEnded() = runTest {
        for (status in listOf(SubscriptionStatus.PAUSED, SubscriptionStatus.UNPAID, SubscriptionStatus.INCOMPLETE)) {
            val world = World()
            world.repository.save(world.subscribe().copy(status = status))
            world.stripe.subscription = stripeSubscription(status = status)

            world.endSubscription(world.org)

            assertEquals(ENDED, world.stripe.calls, status.name)
        }
        val expired = World()
        expired.repository.save(expired.subscribe().copy(status = SubscriptionStatus.INCOMPLETE_EXPIRED))
        expired.endSubscription(expired.org)
        assertEquals(emptyList(), expired.stripe.calls)
    }

    @Test
    fun aCheckoutPaidAfterTheDeletionIsEndedAtThePurge() = runTest {
        val world = World()
        world.opensACheckout()
        val listener = BillingOrgDeletionListener(world.endSubscription)
        listener.deleted(world.org)
        assertEquals(emptyList(), world.stripe.calls)

        world.subscribe()
        listener.purge(world.org)

        assertEquals(ENDED, world.stripe.calls)
        assertEquals(SubscriptionStatus.CANCELED, world.repository.get(world.org)?.status)
    }
}
