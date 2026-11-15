// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Operation
import kotlinx.serialization.json.JsonObject

private const val TAG = "billing"

private const val BAD_EVENT = "A missing, wrong or too old `Stripe-Signature` (`invalid_request`)."

private const val NOT_FOUND = "No such organization for the caller (`not_found`)."

private const val PLAN = "An unknown plan or interval, or a plan that cannot be bought online " +
    "(`invalid_request`)."

/**
 * The billing module's operations: the plan, usage and payment state of an organization, the changes of its
 * subscription through Stripe, and the Stripe webhook. The overview and invoices are answered by the module's own
 * types, which follow the contract's `BillingOverview` and `BillingInvoiceList` field for field.
 */
internal fun ApiCatalog.billingOperations() {
    tag(TAG, "The organization's plan, limits, usage and payment state, and the changes of its " +
        "subscription.")
    purchaseOperations()
    planOperations()
    subscriptionOperations()
    stripeOperations()
}

/** The billing page, and the purchase of a plan. */
private fun ApiCatalog.purchaseOperations() {
    get("/api/v1/orgs/{orgId}/billing") {
        operationId = "getBilling"
        tag(TAG)
        summary = "The organization's plan, limits, usage and payment state."
        description = "Members only. Usage and alerts are for the current period: Stripe's billing period " +
            "while a subscription is live, the UTC calendar month otherwise. `purchasable` is false on a " +
            "self-hosted instance (limits come from the license key)."
        parameters {
            pathId("orgId", "The organization's id.")
        }
        responses {
            ok<BillingOverviewResponse>("The organization's plan, limits, usage and payment state.")
            notFound(NOT_FOUND)
        }
    }
    post("/api/v1/orgs/{orgId}/billing/checkout") {
        operationId = "startBillingCheckout"
        tag(TAG)
        summary = "Start buying a plan (Stripe Checkout)."
        description = "Owners, admins and billing admins. Opens a Stripe Checkout session with automatic " +
            "tax, tax ids and the billing address; the console redirects to `url`. Monthly plans are billed per " +
            "active person in arrears; yearly plans are bought for the people active now (at least one). 409 " +
            "while a subscription is running (change its plan instead), 400 for Free or Enterprise (sold on " +
            "quote)."
        parameters {
            pathId("orgId", "The organization's id.")
        }
        body<PlanRequest>("The plan (`team` or `business`) and the interval (`month` or `year`) to buy.")
        responses {
            ok<RedirectResponse>("The URL of the Stripe Checkout session to send the browser to.")
            badRequest(PLAN)
            notFound(NOT_FOUND)
            conflict("Billing is not enabled on this instance, or the organization already has a " +
                "subscription (`conflict`).")
        }
    }
    post("/api/v1/orgs/{orgId}/billing/portal") {
        operationId = "openBillingPortal"
        tag(TAG)
        summary = "Open the Stripe customer portal."
        description = "Owners, admins and billing admins: payment methods, invoices and receipts, billing " +
            "address and tax id, cancellation at the period end. 409 for an organization that never bought."
        parameters {
            pathId("orgId", "The organization's id.")
        }
        responses {
            ok<RedirectResponse>("The URL of the Stripe customer portal to send the browser to.")
            notFound(NOT_FOUND)
            conflict("Billing is not enabled on this instance, or the organization never bought " +
                "(`conflict`).")
        }
    }
}

/** Changing the plan of the subscription. */
private fun ApiCatalog.planOperations() {
    post("/api/v1/orgs/{orgId}/billing/plan") {
        operationId = "changeBillingPlan"
        tag(TAG)
        summary = "Change the plan or the interval of the subscription."
        description = "Owners, admins and billing admins. An upgrade (Team to Business, monthly to yearly) " +
            "is invoiced now, prorated; a downgrade is credited on the next invoice. Returns the billing after " +
            "the change."
        parameters {
            pathId("orgId", "The organization's id.")
        }
        body<PlanRequest>("The plan (`team` or `business`) and the interval (`month` or `year`) to switch " +
            "to.")
        responses {
            ok<BillingOverviewResponse>("The overview after the change.")
            badRequest(PLAN)
            notFound(NOT_FOUND)
            conflict("Billing is not enabled on this instance, or the organization has no running " +
                "subscription (`conflict`).")
        }
    }
}

/** Ending the subscription or keeping it, and the invoices. */
private fun ApiCatalog.subscriptionOperations() {
    post("/api/v1/orgs/{orgId}/billing/cancel") {
        operationId = "cancelBillingSubscription"
        tag(TAG)
        summary = "End the subscription at the end of the period."
        description = "Owners, admins and billing admins. The plan stays until the period end; 409 if it " +
            "already ends."
        parameters {
            pathId("orgId", "The organization's id.")
        }
        responses {
            ok<BillingOverviewResponse>("The overview, the subscription set to end with the period.")
            notFound(NOT_FOUND)
            conflict("No running subscription, or one that already ends (`conflict`).")
        }
    }
    post("/api/v1/orgs/{orgId}/billing/resume") {
        operationId = "resumeBillingSubscription"
        tag(TAG)
        summary = "Keep a subscription that was set to end."
        description = "Owners, admins and billing admins. 409 if the subscription is not ending; a " +
            "subscription already ended is bought again through checkout."
        parameters {
            pathId("orgId", "The organization's id.")
        }
        responses {
            ok<BillingOverviewResponse>("The overview, the subscription kept.")
            notFound(NOT_FOUND)
            conflict("No running subscription, or one that is not ending (`conflict`).")
        }
    }
    get("/api/v1/orgs/{orgId}/billing/invoices") {
        operationId = "listBillingInvoices"
        tag(TAG)
        summary = "Invoices and receipts, newest first."
        description = "Owners, admins and billing admins. Empty for an organization that never bought. A " +
            "`limit` under 1 is refused (400), one above 100 is brought down to 100."
        parameters {
            pathId("orgId", "The organization's id.")
            queryLimit("At most this many invoices, 1 to 100 (24 by default).", 100)
        }
        responses {
            ok<InvoicesResponse>("The invoices, newest first.")
            notFound(NOT_FOUND)
        }
    }
}

/** The Stripe webhook and its first path, kept as an alias. */
private fun ApiCatalog.stripeOperations() {
    post("/webhooks/stripe") {
        operationId = "receiveStripeWebhook"
        tag(TAG)
        summary = "Stripe events (subscriptions, invoices, checkout)."
        description = STRIPE_WEBHOOK
        stripeEvent()
    }
    post("/api/v1/billing/stripe/webhook") {
        operationId = "receiveStripeWebhookAlias"
        tag(TAG)
        summary = "Deprecated alias of `POST /webhooks/stripe`."
        deprecated = true
        description = "The path the webhook was first served at: same signature, same answers. Removed after the " +
            "public release; point the Stripe endpoint at `/webhooks/stripe`. $STRIPE_WEBHOOK"
        stripeEvent()
    }
}

private const val STRIPE_WEBHOOK = "Called by Stripe only, authenticated by the `Stripe-Signature` header " +
    "(HMAC-SHA256 of the timestamp and the body, 5 minutes of tolerance). Each event is applied once; a replay " +
    "answers `duplicate`. Bodies over 256 KiB are refused (413)."

/** The signed Stripe event and its answers, the same on both paths. */
private fun Operation.Builder.stripeEvent() {
    parameters {
        headerText(
            "Stripe-Signature",
            "The signature of the event: an HMAC-SHA256 of the timestamp and the body, with 5 minutes of tolerance.",
        )
    }
    body<JsonObject>("A Stripe event, as Stripe sends it.")
    responses {
        ok<WebhookResponse>("The event was received, with what was done with it.")
        badRequest(BAD_EVENT)
        error(HttpStatusCode.PayloadTooLarge, "An event of more than 256 KiB (`payload_too_large`).")
    }
}
