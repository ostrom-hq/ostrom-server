// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.presentation

import run.ostrom.server.billing.domain.BILLING_READ
import run.ostrom.server.billing.domain.BillingAccessDeniedException
import run.ostrom.server.billing.domain.BillingInterval
import run.ostrom.server.billing.domain.BillingOverview
import run.ostrom.server.billing.domain.BillingScopeRequiredException
import run.ostrom.server.billing.domain.Invoice
import run.ostrom.server.billing.domain.Plan
import run.ostrom.server.billing.domain.PlanCatalog
import run.ostrom.server.billing.domain.allowsBillingRead
import run.ostrom.server.billing.domain.percentOfHours
import run.ostrom.server.billing.domain.requireBillingWrite
import run.ostrom.server.billing.infrastructure.MAX_WEBHOOK_BYTES
import run.ostrom.server.billing.infrastructure.parseStripeEvent
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

/**
 * The billing routes. Reading needs membership, changing needs an owner, an admin or a billing admin signed in to the
 * console (the member comes from the kernel's [PrincipalOrgResolver]); the webhook is authenticated by Stripe's
 * signature only.
 */
fun Route.billingRoutes(dependencies: BillingRoutesDependencies) = with(dependencies) {
    authenticated {
        get<BillingApi> { params ->
            val (caller, orgId) = call.reader(resolver, params.orgId)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        post<BillingApi.Checkout, PlanRequest> { params, request ->
            val (plan, interval) = request.parse()
            val (caller, orgId) = call.writer(resolver, params.parent.orgId)
            call.respond(RedirectResponse(startCheckout(caller, orgId, plan, interval)))
        }
        post<BillingApi.Portal> { params ->
            val (caller, orgId) = call.writer(resolver, params.parent.orgId)
            call.respond(RedirectResponse(openPortal(caller, orgId)))
        }
        post<BillingApi.ChangePlan, PlanRequest> { params, request ->
            val (plan, interval) = request.parse()
            val (caller, orgId) = call.writer(resolver, params.parent.orgId)
            changePlan(caller, orgId, plan, interval)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        post<BillingApi.Cancel> { params ->
            val (caller, orgId) = call.writer(resolver, params.parent.orgId)
            setCancellation(caller, orgId, cancel = true)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        post<BillingApi.Resume> { params ->
            val (caller, orgId) = call.writer(resolver, params.parent.orgId)
            setCancellation(caller, orgId, cancel = false)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        get<BillingApi.Invoices> { params ->
            val (caller, orgId) = call.reader(resolver, params.parent.orgId)
            call.respond(InvoicesResponse(listInvoices(caller, orgId, params.limit).map { it.toResponse() }))
        }
    }
    post<StripeWebhookApi> { call.receiveStripeEvent(dependencies) }
    post<LegacyStripeWebhookApi> { call.receiveStripeEvent(dependencies) }
}

/** Verifies and applies one Stripe event; an event Stripe sent is always answered 200 once its signature holds. */
private suspend fun ApplicationCall.receiveStripeEvent(dependencies: BillingRoutesDependencies) {
    // Reads at most one byte past the limit, whatever Content-Length says (or a chunked body hides).
    val bytes = receiveChannel().readRemaining(MAX_WEBHOOK_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_WEBHOOK_BYTES) throw eventTooLarge()
    val payload = bytes.decodeToString()
    dependencies.verifier.verify(payload, request.headers["Stripe-Signature"])
    val outcome = dependencies.handleEvent(parseStripeEvent(payload))
    respond(WebhookResponse(received = true, outcome = outcome.name.lowercase()))
}

private fun eventTooLarge() = OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "The event is too large.")

/**
 * The member the caller acts for in the organization of the path, and that organization, for a read. Only a person's
 * credential with [BILLING_READ] (or the console session) gets there: an agent, device or service token is refused
 * (S2-03).
 */
private suspend fun ApplicationCall.reader(resolver: PrincipalOrgResolver, orgId: String): Pair<OrgPrincipal, Uuid> {
    if (!requirePrincipal().allowsBillingRead()) throw BillingScopeRequiredException(BILLING_READ)
    return member(resolver, orgId)
}

/** The same for a write, which only a person signed in to the console makes, never a token (S2-52). */
private suspend fun ApplicationCall.writer(resolver: PrincipalOrgResolver, orgId: String): Pair<OrgPrincipal, Uuid> {
    requirePrincipal().requireBillingWrite()
    return member(resolver, orgId)
}

private suspend fun ApplicationCall.member(resolver: PrincipalOrgResolver, orgId: String): Pair<OrgPrincipal, Uuid> {
    val id = orgId.toOrgId()
    return requireOrgPrincipal(resolver, id) to id
}

/** An organization id from the path; a malformed one is answered like an unknown one. */
fun String.toOrgId(): Uuid = try {
    Uuid.parse(this)
} catch (_: IllegalArgumentException) {
    throw BillingAccessDeniedException()
}

/** Reads a plan request. @throws OstromException 400 for an unknown plan or interval. */
fun PlanRequest.parse(): Pair<Plan, BillingInterval> {
    val plan = Plan.fromKey(plan)
        ?: throw OstromException(ErrorKind.INVALID_REQUEST, "Unknown plan: use team or business.")
    val interval = BillingInterval.fromKey(interval)
        ?: throw OstromException(ErrorKind.INVALID_REQUEST, "Unknown interval: use month or year.")
    return plan to interval
}

/** Maps the overview to the API. */
fun BillingOverview.toResponse() = BillingOverviewResponse(
    orgId = billing.orgId.toString(),
    plan = billing.plan.key,
    effectivePlan = effectivePlan.key,
    interval = billing.interval?.key,
    status = billing.status?.stripe,
    cancelAtPeriodEnd = billing.cancelAtPeriodEnd,
    periodStart = period.start.toString(),
    periodEnd = period.end.toString(),
    activePeople = activePeople,
    committedPeople = billing.committedPeople,
    limits = PlanLimitsResponse(
        maxPeople = limits.maxPeople,
        maxConcurrentCloudAgents = limits.maxConcurrentCloudAgents,
        agentHoursPerPeriod = limits.agentHoursPerPeriod,
        sso = limits.sso,
        policies = limits.policies,
        receiptRetentionDays = limits.receiptRetentionDays,
    ),
    usage = UsageResponse(
        agentSeconds = usage.agentSeconds,
        agentHours = usage.agentHours,
        sessions = usage.sessions,
        percentOfHours = limits.agentHoursPerPeriod?.let { percentOfHours(usage.agentSeconds, it) },
    ),
    alerts = alerts,
    paymentIssue = billing.paymentIssue?.let {
        PaymentIssueResponse(it.kind.key, it.hostedInvoiceUrl, it.at.toString())
    },
    purchasable = stripeEnabled,
    prices = if (stripeEnabled) {
        PlanCatalog.prices.map { PriceResponse(it.plan.key, it.interval.key, it.unitAmount, "usd") }
    } else {
        emptyList()
    },
)

/** Maps an invoice to the API. */
fun Invoice.toResponse() = InvoiceResponse(
    id = id,
    number = number,
    status = status,
    total = total,
    tax = tax,
    currency = currency,
    created = created.toString(),
    hostedInvoiceUrl = hostedInvoiceUrl,
    pdfUrl = pdfUrl,
)
