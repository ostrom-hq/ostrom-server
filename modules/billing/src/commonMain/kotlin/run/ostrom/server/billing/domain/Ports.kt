// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What a Stripe Checkout session is created with. */
data class CheckoutRequest(
    val orgId: Uuid,
    val customerId: String,
    val priceId: String,
    val quantity: Int?,
    val successUrl: String,
    val cancelUrl: String,
)

/** How Stripe charges a plan change in the middle of a period. */
enum class Proration(val stripe: String) {
    /** Invoice the difference now (upgrades, yearly true-ups). */
    ALWAYS_INVOICE("always_invoice"),

    /** Carry the difference to the next invoice. */
    CREATE_PRORATIONS("create_prorations"),
}

/** The Stripe calls of billing. Implemented over Stripe's HTTP API in the infrastructure; faked in tests. */
@Suppress("TooManyFunctions") // Stripe's one port: each call billing makes to it.
interface StripeGateway {
    /**
     * Creates the customer of an organization, once: a second call within 24 hours returns the same customer. Call
     * it with the same [name] and [email] each time (Stripe refuses the same key with other parameters), and only when
     * no customer is stored.
     *
     * @return Its id.
     */
    suspend fun createCustomer(orgId: Uuid, name: String?, email: String?): String

    /** The e-mail Stripe holds for a customer (set at creation, at Checkout or in the portal), null when none. */
    suspend fun customerEmail(customerId: String): String?

    /** Sets the e-mail of a customer: Checkout then shows it instead of asking it, and receipts go to it. */
    suspend fun updateCustomerEmail(customerId: String, email: String)

    /** Resolves a price from its lookup key (cached). @throws BillingNotConfiguredException When it does not exist. */
    suspend fun priceId(lookupKey: String): String

    /** Creates a Checkout session in subscription mode with automatic tax. @return The URL to send the buyer to. */
    suspend fun createCheckoutSession(request: CheckoutRequest): String

    /** Creates a customer portal session (payment methods, invoices, cancellation). @return Its URL. */
    suspend fun createPortalSession(customerId: String, returnUrl: String): String

    /** Reads a subscription. */
    suspend fun getSubscription(subscriptionId: String): StripeSubscription

    /** Moves the subscription item to another price. [quantity] is null for a metered price. */
    suspend fun changePrice(
        subscriptionId: String,
        itemId: String,
        priceId: String,
        quantity: Int?,
        proration: Proration,
    )

    /** Schedules the end of the subscription at the period end, or cancels that schedule. */
    suspend fun setCancelAtPeriodEnd(subscriptionId: String, cancel: Boolean)

    /**
     * Ends the subscription now, without a refund of the period already paid nor a last invoice: what an
     * organization's deletion does.
     */
    suspend fun cancelNow(subscriptionId: String)

    /** Raises the quantity of a yearly item and invoices the prorated difference now. */
    suspend fun setQuantity(subscriptionId: String, itemId: String, quantity: Int)

    /** Sends one meter event of value 1 for an active person; [identifier] makes a retry count once. */
    suspend fun recordActivePerson(customerId: String, identifier: String, at: Instant)

    /** Lists the latest invoices of a customer, newest first. */
    suspend fun listInvoices(customerId: String, limit: Int): List<Invoice>
}

/** Billing's storage. Every table is keyed by organization. */
// One port for the module's tables (billing, active people, usage, alerts, events, outbox), read and written by the
// same use cases; splitting it would only spread one transaction boundary over several ports.
@Suppress("TooManyFunctions")
interface BillingRepository {
    /** Reads an organization's billing. */
    suspend fun get(orgId: Uuid): OrgBilling?

    /** Finds the organization of a Stripe customer. */
    suspend fun findByCustomer(customerId: String): OrgBilling?

    /** Creates or replaces an organization's billing. */
    suspend fun save(billing: OrgBilling)

    /**
     * Raises the people a yearly subscription is committed to, never lowers them, without touching the rest of the
     * row: a webhook may have saved a newer state since the caller read it.
     */
    suspend fun raiseCommittedPeople(orgId: Uuid, people: Int, at: Instant)

    /** Whether a Stripe event was already processed. */
    suspend fun isEventProcessed(eventId: String): Boolean

    /** Records a processed Stripe event; recording it twice is harmless. */
    suspend fun markEventProcessed(eventId: String, type: String, at: Instant)

    /** Records a person as active in a period. @return True the first time in that period. */
    suspend fun recordActivePerson(orgId: Uuid, periodStart: Instant, userId: String, at: Instant): Boolean

    /** Counts the people active in a period. */
    suspend fun countActivePeople(orgId: Uuid, periodStart: Instant): Int

    /** Lists the people active in a period (bounded), oldest activity first. */
    suspend fun listActivePeople(orgId: Uuid, periodStart: Instant, limit: Int): List<String>

    /** Adds cloud agent usage to a period. @return The period's totals after the addition. */
    suspend fun addUsage(orgId: Uuid, periodStart: Instant, agentSeconds: Long, sessions: Long): Usage

    /** Reads a period's usage (zero when nothing was recorded). */
    suspend fun usage(orgId: Uuid, periodStart: Instant): Usage

    /** Records that an alert threshold was reached in a period. @return True the first time. */
    suspend fun recordAlert(orgId: Uuid, periodStart: Instant, percent: Int, at: Instant): Boolean

    /** Lists the thresholds reached in a period, ascending. */
    suspend fun alerts(orgId: Uuid, periodStart: Instant): List<Int>

    /** Queues a task for the worker; a task with the same identifier is queued once. */
    suspend fun enqueue(task: OutboxTask)

    /**
     * Takes due tasks, oldest first, and hides them from other workers until [leaseUntil]. A task neither completed
     * nor failed by then (its worker died) is taken again; sending it twice is harmless, its identifier counts it once.
     */
    suspend fun claimDueTasks(now: Instant, leaseUntil: Instant, limit: Int): List<OutboxTask>

    /** Marks a task done. */
    suspend fun completeTask(id: Uuid, at: Instant)

    /** Records a failed attempt; the task is retried at [retryAt]. */
    suspend fun failTask(id: Uuid, retryAt: Instant)
}

/** The self-hosted license. The `ee/` license module binds the verifying implementation. */
interface LicenseProvider {
    /** The license in force now, or null without a valid one. */
    suspend fun current(): LicenseGrant?
}

/** No license: the default of an Apache-2.0 build without `ee/`. */
class NoLicenseProvider : LicenseProvider {
    override suspend fun current(): LicenseGrant? = null
}

/**
 * What billing counts for operators (the observability module binds its OpenTelemetry counters; until then the
 * default logs each event and keeps the count in memory).
 */
interface BillingMetrics {
    /**
     * A signed Stripe event named an organization that does not exist: nothing was created, Stripe was answered 200
     * (BILL-001). Counted as `ignored_unknown_org`.
     *
     * @param eventId Stripe's id of the event.
     * @param eventType Its type.
     */
    fun stripeEventForUnknownOrganization(eventId: String, eventType: String)
}

/** Where usage alerts go (the notifications module binds the e-mail and console ones). */
interface BillingNotifier {
    /** An organization used [percent] % of its agent hours this period. */
    suspend fun agentHoursReached(orgId: Uuid, percent: Int, usedHours: Long, limitHours: Int)

    /** A payment needs the customer. */
    suspend fun paymentIssue(orgId: Uuid, issue: PaymentIssue)
}

/**
 * How billing runs on this instance. [stripeEnabled] is false on a self-hosted instance: limits then come from the
 * license, and the purchase routes answer that billing is not enabled.
 *
 * @property stripeEnabled Whether a Stripe key is configured (Ostrom Cloud).
 * @property consoleUrl The console, for Checkout and portal return pages (`https://app.ostrom.run`).
 */
data class BillingSettings(val stripeEnabled: Boolean, val consoleUrl: String) {
    /**
     * The billing page of the console (its route `/billing`), where Checkout and the portal bring people back and limit
     * messages send them.
     */
    val billingPageUrl: String get() = "${consoleUrl.trimEnd('/')}/billing"
}
