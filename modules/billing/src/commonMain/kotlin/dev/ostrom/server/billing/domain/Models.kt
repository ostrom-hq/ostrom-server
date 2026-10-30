package dev.ostrom.server.billing.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The status of a Stripe subscription, as Stripe names it. [UNKNOWN] keeps the server working when Stripe adds a
 * status: it counts as not paying.
 *
 * @property stripe Stripe's name of the status.
 */
enum class SubscriptionStatus(val stripe: String) {
    ACTIVE("active"),
    TRIALING("trialing"),
    PAST_DUE("past_due"),
    UNPAID("unpaid"),
    CANCELED("canceled"),
    INCOMPLETE("incomplete"),
    INCOMPLETE_EXPIRED("incomplete_expired"),
    PAUSED("paused"),
    UNKNOWN("unknown"),
    ;

    /**
     * Whether the paid plan applies. `past_due` keeps it while Stripe retries the payment (dunning): the team is not
     * cut off for a card that expired yesterday. `unpaid` and `canceled`, after the last retry, fall back to Free.
     */
    val grantsPlan: Boolean get() = this == ACTIVE || this == TRIALING || this == PAST_DUE

    /** Reading it back. */

    companion object {
        /** Reads Stripe's name, [UNKNOWN] for a status this version does not know. */
        fun fromStripe(value: String): SubscriptionStatus = entries.firstOrNull { it.stripe == value } ?: UNKNOWN
    }
}

/** Why the console must ask the billing admin to act on a payment. */
enum class PaymentIssueKind(val key: String) {
    /** The last payment failed; Stripe retries it and e-mails the customer. */
    FAILED("payment_failed"),

    /** The bank asks for an authentication (3-D Secure) the customer must complete. */
    ACTION_REQUIRED("action_required"),
}

/**
 * A payment that needs the customer.
 *
 * @property kind What happened.
 * @property hostedInvoiceUrl Stripe's page where the customer pays the invoice or authenticates.
 * @property at When Stripe reported it.
 */
data class PaymentIssue(val kind: PaymentIssueKind, val hostedInvoiceUrl: String?, val at: Instant)

/**
 * The billing state of one organization. An organization without a row, or without a subscription granting a plan,
 * is on Free.
 *
 * @property orgId The organization.
 * @property stripeCustomerId Its Stripe customer, created at its first checkout.
 * @property stripeSubscriptionId Its current Stripe subscription.
 * @property subscriptionItemId The subscription item carrying the plan's price.
 * @property plan The plan of the subscription (Free without one).
 * @property interval How it is billed.
 * @property status The subscription status, null without a subscription.
 * @property currentPeriodStart Start of the current billing period.
 * @property currentPeriodEnd End of the current billing period.
 * @property cancelAtPeriodEnd Whether the subscription ends at [currentPeriodEnd].
 * @property committedPeople The people paid in advance on a yearly plan (0 on a monthly plan).
 * @property paymentIssue A payment waiting for the customer, if any.
 * @property updatedAt Last change of this row.
 */
data class OrgBilling(
    val orgId: Uuid,
    val stripeCustomerId: String? = null,
    val stripeSubscriptionId: String? = null,
    val subscriptionItemId: String? = null,
    val plan: Plan = Plan.FREE,
    val interval: BillingInterval? = null,
    val status: SubscriptionStatus? = null,
    val currentPeriodStart: Instant? = null,
    val currentPeriodEnd: Instant? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val committedPeople: Int = 0,
    val paymentIssue: PaymentIssue? = null,
    val updatedAt: Instant,
) {
    /** The plan whose limits apply now. */
    val effectivePlan: Plan get() = if (status?.grantsPlan == true) plan else Plan.FREE

    /** Whether a subscription is running (a new checkout must not create a second one). */
    val hasLiveSubscription: Boolean get() = stripeSubscriptionId != null && status?.grantsPlan == true
}

/**
 * Cloud agent usage of an organization over one billing period.
 *
 * @property periodStart The period.
 * @property agentSeconds Cloud agent time.
 * @property sessions Cloud agent sessions started.
 */
data class Usage(val periodStart: Instant, val agentSeconds: Long, val sessions: Long) {
    /** Whole hours used, rounded down. */
    val agentHours: Long get() = agentSeconds / SECONDS_PER_HOUR
}

/**
 * A billing period: Stripe's current period while a subscription is live, the calendar month (UTC) otherwise.
 *
 * @property start First instant of the period.
 * @property end First instant after the period.
 */
data class BillingPeriod(val start: Instant, val end: Instant)

/**
 * A Stripe subscription, read back from Stripe (never trusted from a webhook body).
 *
 * @property id The subscription.
 * @property customerId Its customer.
 * @property status Stripe's status.
 * @property itemId The item carrying our price.
 * @property priceLookupKey The lookup key of that price.
 * @property quantity The item's quantity (yearly plans), null for a metered price.
 * @property currentPeriodStart Start of the current period.
 * @property currentPeriodEnd End of the current period.
 * @property cancelAtPeriodEnd Whether it ends at the period end.
 * @property orgId The `org_id` metadata set at checkout.
 */
data class StripeSubscription(
    val id: String,
    val customerId: String,
    val status: SubscriptionStatus,
    val itemId: String?,
    val priceLookupKey: String?,
    val quantity: Int?,
    val currentPeriodStart: Instant?,
    val currentPeriodEnd: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val orgId: String?,
)

/**
 * The part of a Stripe event the server acts on. Bodies are parsed by the infrastructure; the subscription itself is
 * always fetched again, so events received out of order cannot leave a stale state.
 *
 * @property id The event, used to process each one once.
 * @property type Stripe's event type.
 * @property created When Stripe created it.
 * @property customerId The customer the object belongs to.
 * @property subscriptionId The subscription concerned, when there is one.
 * @property orgId The `org_id` metadata or the checkout's client reference.
 * @property hostedInvoiceUrl For invoice events, the page where the customer pays.
 */
data class StripeEvent(
    val id: String,
    val type: String,
    val created: Instant,
    val customerId: String?,
    val subscriptionId: String?,
    val orgId: String?,
    val hostedInvoiceUrl: String?,
)

/**
 * An invoice, as listed in the console. Amounts are in the currency's smallest unit.
 *
 * @property id The invoice.
 * @property number Its human number.
 * @property status Stripe's status (`paid`, `open`, `void`…).
 * @property total Amount including tax.
 * @property tax Tax included in [total].
 * @property currency ISO currency, lowercase.
 * @property created Creation date.
 * @property hostedInvoiceUrl Stripe's page of the invoice (pay, download the receipt).
 * @property pdfUrl The PDF.
 */
data class Invoice(
    val id: String,
    val number: String?,
    val status: String,
    val total: Long,
    val tax: Long,
    val currency: String,
    val created: Instant,
    val hostedInvoiceUrl: String?,
    val pdfUrl: String?,
)

/**
 * What the self-hosted license key grants (empty without a key). Verified by the `ee/` license module, which binds
 * [LicenseProvider]; the AGPL code only reads this.
 *
 * @property features The licensed features (`sso`, `policies`, `retention`…).
 * @property maxPeople The licensed people, null for unlimited.
 * @property expiresAt The end of the license.
 */
data class LicenseGrant(val features: Set<String>, val maxPeople: Int?, val expiresAt: Instant?)

/** A use of the plan that has a limit, checked by [CheckEntitlementUseCase] before it happens. */
sealed interface Entitlement {
    /** One more member, the organization having [currentPeople] now. */
    data class AddPerson(val currentPeople: Int) : Entitlement

    /** One more cloud agent, [running] running now. */
    data class StartCloudAgent(val running: Int) : Entitlement

    /** Configuring single sign-on. */
    data object Sso : Entitlement

    /** Configuring organization policies. */
    data object Policies : Entitlement
}

/**
 * A task the background worker sends to Stripe, so that a session or a gate never waits for Stripe nor fails with it.
 *
 * @property id The task.
 * @property orgId The organization.
 * @property kind What to send.
 * @property identifier The idempotency identifier sent to Stripe (a retried task is counted once).
 * @property at When the activity happened.
 * @property attempts Failed attempts so far.
 */
data class OutboxTask(
    val id: Uuid,
    val orgId: Uuid,
    val kind: OutboxKind,
    val identifier: String,
    val at: Instant,
    val attempts: Int,
)

/** The kinds of [OutboxTask]. */
enum class OutboxKind(val key: String) {
    /** One meter event of value 1: a person became active this period (monthly plans). */
    ACTIVE_PERSON("active_person"),

    /** Raise the yearly quantity to the active people (true-up), invoiced now with proration. */
    TRUE_UP("true_up"),
    ;

    /** Reading it back. */

    companion object {
        /** Reads a stored key. */
        fun fromKey(key: String): OutboxKind = entries.first { it.key == key }
    }
}
