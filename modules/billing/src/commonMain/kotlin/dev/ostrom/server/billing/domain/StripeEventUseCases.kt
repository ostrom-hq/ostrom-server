// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What happened to an event. */
enum class EventOutcome { PROCESSED, DUPLICATE, IGNORED }

/** Applies a verified Stripe event, once. */
interface HandleStripeEventUseCase {
    /** @return What was done. */
    suspend operator fun invoke(event: StripeEvent): EventOutcome
}

/**
 * [HandleStripeEventUseCase]. Every event that concerns a subscription re-reads it from Stripe, so the order of
 * delivery does not matter and a replayed event changes nothing. The event is marked processed only after the state
 * is saved: if the server dies in between, Stripe's retry processes it again, harmlessly.
 */
class HandleStripeEventUseCaseImpl(
    private val repository: BillingRepository,
    private val sync: SyncSubscriptionUseCase,
    private val notifier: BillingNotifier,
    private val backfill: BackfillActivePeopleUseCase,
    private val clock: Clock,
) : HandleStripeEventUseCase {
    override suspend fun invoke(event: StripeEvent): EventOutcome {
        if (repository.isEventProcessed(event.id)) return EventOutcome.DUPLICATE
        val outcome = apply(event)
        repository.markEventProcessed(event.id, event.type, clock.now())
        return outcome
    }

    private suspend fun apply(event: StripeEvent): EventOutcome {
        val billing = findBilling(event)
        val stored = billing?.stripeSubscriptionId
        val subscriptionId = event.subscriptionId ?: stored
        return when {
            billing == null || subscriptionId == null -> EventOutcome.IGNORED
            // A late event about a subscription the organization no longer pays with (an expired checkout, an old
            // one deleted) must not replace the one that grants its plan.
            subscriptionId != stored && billing.live() != null -> EventOutcome.IGNORED
            else -> apply(event, billing, subscriptionId)
        }
    }

    private suspend fun apply(event: StripeEvent, billing: OrgBilling, subscriptionId: String): EventOutcome =
        when (event.type) {
            "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                val synced = sync(
                    billing.copy(stripeCustomerId = event.customerId ?: billing.stripeCustomerId),
                    subscriptionId,
                )
                backfill(synced)
                EventOutcome.PROCESSED
            }
            "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted",
            "customer.subscription.paused", "customer.subscription.resumed", "checkout.session.async_payment_failed",
            "invoice.finalized",
            -> {
                sync(billing, subscriptionId)
                EventOutcome.PROCESSED
            }
            "invoice.paid" -> {
                // A payment delivered late does not clear a failure that happened after it.
                val issue = billing.paymentIssue
                val kept = if (issue != null && issue.at > event.created) issue else null
                sync(billing.copy(paymentIssue = kept), subscriptionId)
                EventOutcome.PROCESSED
            }
            "invoice.payment_failed", "invoice.payment_action_required" -> {
                val failed = event.type == "invoice.payment_failed"
                val kind = if (failed) PaymentIssueKind.FAILED else PaymentIssueKind.ACTION_REQUIRED
                val issue = PaymentIssue(kind, event.hostedInvoiceUrl, event.created)
                sync(billing.copy(paymentIssue = issue), subscriptionId)
                notifier.paymentIssue(billing.orgId, issue)
                EventOutcome.PROCESSED
            }
            else -> EventOutcome.IGNORED
        }

    private suspend fun findBilling(event: StripeEvent): OrgBilling? {
        val known = event.customerId?.let { repository.findByCustomer(it) }
        val orgId = event.orgId?.let(::parseUuid)
        return when {
            known != null -> known
            orgId != null -> repository.get(orgId) ?: OrgBilling(orgId = orgId, updatedAt = clock.now())
            else -> null
        }
    }
}

/**
 * Counts the people already active in the new period once a subscription starts: they were active before the
 * subscription existed, so no meter event was sent for them.
 */
interface BackfillActivePeopleUseCase {
    /** Queues what the new subscription must be told. */
    suspend operator fun invoke(billing: OrgBilling)
}

/** [BackfillActivePeopleUseCase]. */
class BackfillActivePeopleUseCaseImpl(
    private val repository: BillingRepository,
    private val clock: Clock,
) : BackfillActivePeopleUseCase {
    override suspend fun invoke(billing: OrgBilling) {
        if (!billing.hasLiveSubscription) return
        val now = clock.now()
        val period = billingPeriod(billing, now)
        val people = repository.listActivePeople(billing.orgId, calendarMonth(now).start, MAX_PEOPLE_READ)
        for (userId in people) {
            if (repository.recordActivePerson(billing.orgId, period.start, userId, now)) {
                queueActivity(repository, billing, period, userId, now)
            }
        }
    }
}

private fun parseUuid(value: String): Uuid? = try {
    Uuid.parse(value)
} catch (_: IllegalArgumentException) {
    null
}
