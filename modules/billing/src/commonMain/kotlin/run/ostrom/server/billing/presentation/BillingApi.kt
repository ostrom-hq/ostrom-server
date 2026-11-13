// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.presentation

import run.ostrom.server.kernel.http.NullsKept
import io.ktor.resources.Resource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/orgs/{orgId}/billing`: the plan, limits, usage and payment state of an organization (members).
 * To move to ostrom-core with the API contract (`api/openapi.yaml`, tag `billing`).
 *
 * @property orgId The organization.
 */
@Serializable
@Resource("/api/v1/orgs/{orgId}/billing")
class BillingApi(val orgId: String) {
    /** `POST …/checkout` {plan, interval}: a Stripe Checkout URL (owners and billing admins). */
    @Serializable
    @Resource("checkout")
    class Checkout(val parent: BillingApi)

    /** `POST …/portal`: a Stripe customer portal URL (payment methods, invoices, cancellation). */
    @Serializable
    @Resource("portal")
    class Portal(val parent: BillingApi)

    /** `POST …/plan` {plan, interval}: changes the running subscription, prorated. */
    @Serializable
    @Resource("plan")
    class ChangePlan(val parent: BillingApi)

    /** `POST …/cancel`: ends the subscription at the end of the period. */
    @Serializable
    @Resource("cancel")
    class Cancel(val parent: BillingApi)

    /** `POST …/resume`: keeps a subscription that was ending. */
    @Serializable
    @Resource("resume")
    class Resume(val parent: BillingApi)

    /**
     * `GET …/invoices?limit=`: invoices and receipts, newest first.
     *
     * @property limit At most this many (1 to 100).
     */
    @Serializable
    @Resource("invoices")
    class Invoices(val parent: BillingApi, val limit: Int = 24)
}

/**
 * `POST /webhooks/stripe`: Stripe's events, verified by their signature (no bearer token). Outside `/api/v1`, like
 * every incoming webhook (`/webhooks/github`, `/webhooks/slack`).
 */
@Serializable
@Resource("/webhooks/stripe")
class StripeWebhookApi

/**
 * `POST /api/v1/billing/stripe/webhook`: the path the webhook was first served at, kept as an alias of
 * [StripeWebhookApi] until live billing opens (the test endpoint may still point at it).
 */
@Serializable
@Resource("/api/v1/billing/stripe/webhook")
class LegacyStripeWebhookApi

/** A plan to buy or switch to. */
@Serializable
data class PlanRequest(val plan: String, val interval: String)

/** A URL to send the browser to (Checkout, portal). */
@Serializable
data class RedirectResponse(val url: String)

/** The limits of the plan in force; null means unlimited. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = PlanLimitsResponse.Answer::class)
data class PlanLimitsResponse(
    val maxPeople: Int?,
    val maxConcurrentCloudAgents: Int?,
    val agentHoursPerPeriod: Int?,
    val sso: Boolean,
    val policies: Boolean,
    val receiptRetentionDays: Int?,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<PlanLimitsResponse>(
        generatedSerializer(),
        "maxPeople",
        "maxConcurrentCloudAgents",
        "agentHoursPerPeriod",
        "receiptRetentionDays",
    )
}

/** The cloud agent usage of the current period. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = UsageResponse.Answer::class)
data class UsageResponse(
    val agentSeconds: Long,
    val agentHours: Long,
    val sessions: Long,
    val percentOfHours: Int?,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<UsageResponse>(generatedSerializer(), "percentOfHours")
}

/** A payment that needs the billing admin (show a banner with the link). */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = PaymentIssueResponse.Answer::class)
data class PaymentIssueResponse(val kind: String, val hostedInvoiceUrl: String?, val at: String) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<PaymentIssueResponse>(generatedSerializer(), "hostedInvoiceUrl")
}

/** A price of the catalog, for the plan picker. Amounts in cents, before tax. */
@Serializable
data class PriceResponse(val plan: String, val interval: String, val unitAmount: Long, val currency: String)

/** The console's billing page. Dates are ISO 8601. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = BillingOverviewResponse.Answer::class)
data class BillingOverviewResponse(
    val orgId: String,
    val plan: String,
    val effectivePlan: String,
    val interval: String?,
    val status: String?,
    val cancelAtPeriodEnd: Boolean,
    val periodStart: String,
    val periodEnd: String,
    val activePeople: Int,
    val committedPeople: Int,
    val limits: PlanLimitsResponse,
    val usage: UsageResponse,
    val alerts: List<Int>,
    val paymentIssue: PaymentIssueResponse?,
    val purchasable: Boolean,
    val prices: List<PriceResponse>,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<BillingOverviewResponse>(generatedSerializer(), "interval", "status", "paymentIssue")
}

/** One invoice; `hostedInvoiceUrl` shows it, pays it and gives its receipt. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = InvoiceResponse.Answer::class)
data class InvoiceResponse(
    val id: String,
    val number: String?,
    val status: String,
    val total: Long,
    val tax: Long,
    val currency: String,
    val created: String,
    val hostedInvoiceUrl: String?,
    val pdfUrl: String?,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<InvoiceResponse>(generatedSerializer(), "number", "hostedInvoiceUrl", "pdfUrl")
}

/** The invoices of an organization. */
@Serializable
data class InvoicesResponse(val invoices: List<InvoiceResponse>)

/** What the webhook did with an event (Stripe only reads the status). */
@Serializable
data class WebhookResponse(val received: Boolean, val outcome: String)
