package dev.ostrom.server.billing.presentation

import dev.ostrom.server.billing.domain.BillingAccessDeniedException
import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingOverview
import dev.ostrom.server.billing.domain.ChangePlanUseCase
import dev.ostrom.server.billing.domain.GetBillingOverviewUseCase
import dev.ostrom.server.billing.domain.HandleStripeEventUseCase
import dev.ostrom.server.billing.domain.Invoice
import dev.ostrom.server.billing.domain.ListInvoicesUseCase
import dev.ostrom.server.billing.domain.OpenCustomerPortalUseCase
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.PlanCatalog
import dev.ostrom.server.billing.domain.SetCancellationUseCase
import dev.ostrom.server.billing.domain.StartCheckoutUseCase
import dev.ostrom.server.billing.infrastructure.MAX_WEBHOOK_BYTES
import dev.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import dev.ostrom.server.billing.infrastructure.parseStripeEvent
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.server.request.receiveText
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/** What the billing routes need. */
data class BillingRoutesDependencies(
    val getOverview: GetBillingOverviewUseCase,
    val startCheckout: StartCheckoutUseCase,
    val openPortal: OpenCustomerPortalUseCase,
    val changePlan: ChangePlanUseCase,
    val setCancellation: SetCancellationUseCase,
    val listInvoices: ListInvoicesUseCase,
    val handleEvent: HandleStripeEventUseCase,
    val verifier: StripeSignatureVerifier,
)

/**
 * The billing routes. Reading needs membership, changing needs an owner or billing admin (decided by the orgs
 * module's [dev.ostrom.server.billing.domain.BillingAccessPolicy]); the webhook is authenticated by Stripe's
 * signature only.
 */
fun Route.billingRoutes(dependencies: BillingRoutesDependencies) = with(dependencies) {
    authenticated {
        get<BillingApi> { params ->
            call.respond(getOverview(call.requirePrincipal().subjectId, params.orgId.toOrgId()).toResponse())
        }
        post<BillingApi.Checkout, PlanRequest> { params, request ->
            val (plan, interval) = request.parse()
            val url = startCheckout(call.requirePrincipal().subjectId, params.parent.orgId.toOrgId(), null, null, plan, interval)
            call.respond(RedirectResponse(url))
        }
        post<BillingApi.Portal> { params ->
            call.respond(RedirectResponse(openPortal(call.requirePrincipal().subjectId, params.parent.orgId.toOrgId())))
        }
        post<BillingApi.ChangePlan, PlanRequest> { params, request ->
            val (plan, interval) = request.parse()
            val principal = call.requirePrincipal().subjectId
            val orgId = params.parent.orgId.toOrgId()
            changePlan(principal, orgId, plan, interval)
            call.respond(getOverview(principal, orgId).toResponse())
        }
        post<BillingApi.Cancel> { params ->
            val principal = call.requirePrincipal().subjectId
            val orgId = params.parent.orgId.toOrgId()
            setCancellation(principal, orgId, cancel = true)
            call.respond(getOverview(principal, orgId).toResponse())
        }
        post<BillingApi.Resume> { params ->
            val principal = call.requirePrincipal().subjectId
            val orgId = params.parent.orgId.toOrgId()
            setCancellation(principal, orgId, cancel = false)
            call.respond(getOverview(principal, orgId).toResponse())
        }
        get<BillingApi.Invoices> { params ->
            val invoices = listInvoices(call.requirePrincipal().subjectId, params.parent.orgId.toOrgId(), params.limit)
            call.respond(InvoicesResponse(invoices.map { it.toResponse() }))
        }
    }
    post<StripeWebhookApi> {
        val length = call.request.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > MAX_WEBHOOK_BYTES) {
            throw OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "The event is too large.")
        }
        val payload = call.receiveText()
        if (payload.length > MAX_WEBHOOK_BYTES) throw OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "The event is too large.")
        verifier.verify(payload, call.request.headers["Stripe-Signature"])
        val outcome = handleEvent(parseStripeEvent(payload))
        call.respond(WebhookResponse(received = true, outcome = outcome.name.lowercase()))
    }
}

/** An organization id from the path; a malformed one is answered like an unknown one. */
fun String.toOrgId(): Uuid = try {
    Uuid.parse(this)
} catch (_: IllegalArgumentException) {
    throw BillingAccessDeniedException()
}

/** Reads a plan request. @throws OstromException 400 for an unknown plan or interval. */
fun PlanRequest.parse(): Pair<Plan, BillingInterval> {
    val plan = Plan.fromKey(plan) ?: throw OstromException(ErrorKind.INVALID_REQUEST, "Unknown plan: use team or business.")
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
        percentOfHours = limits.agentHoursPerPeriod?.let { (usage.agentSeconds * 100 / (it * 3600L)).toInt() },
    ),
    alerts = alerts,
    paymentIssue = billing.paymentIssue?.let { PaymentIssueResponse(it.kind.key, it.hostedInvoiceUrl, it.at.toString()) },
    purchasable = stripeEnabled,
    prices = if (stripeEnabled) PlanCatalog.prices.map { PriceResponse(it.plan.key, it.interval.key, it.unitAmount, "usd") } else emptyList(),
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
