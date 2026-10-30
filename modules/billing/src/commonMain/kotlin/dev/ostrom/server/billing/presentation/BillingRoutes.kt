package dev.ostrom.server.billing.presentation

import dev.ostrom.server.billing.domain.BILLING_MANAGE
import dev.ostrom.server.billing.domain.BILLING_READ
import dev.ostrom.server.billing.domain.BillingAccessDeniedException
import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingOverview
import dev.ostrom.server.billing.domain.BillingScopeRequiredException
import dev.ostrom.server.billing.domain.Invoice
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.PlanCatalog
import dev.ostrom.server.billing.domain.allowsBilling
import dev.ostrom.server.billing.domain.percentOfHours
import dev.ostrom.server.billing.infrastructure.MAX_WEBHOOK_BYTES
import dev.ostrom.server.billing.infrastructure.parseStripeEvent
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The billing routes. Reading needs membership, changing needs an owner, an admin or a billing admin (the member
 * comes from the kernel's [PrincipalOrgResolver]); the webhook is authenticated by Stripe's signature only.
 */
fun Route.billingRoutes(dependencies: BillingRoutesDependencies) = with(dependencies) {
    authenticated {
        get<BillingApi> { params ->
            val (caller, orgId) = call.caller(resolver, params.orgId, BILLING_READ)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        post<BillingApi.Checkout, PlanRequest> { params, request ->
            val (plan, interval) = request.parse()
            val (caller, orgId) = call.caller(resolver, params.parent.orgId, BILLING_MANAGE)
            call.respond(RedirectResponse(startCheckout(caller, orgId, null, null, plan, interval)))
        }
        post<BillingApi.Portal> { params ->
            val (caller, orgId) = call.caller(resolver, params.parent.orgId, BILLING_MANAGE)
            call.respond(RedirectResponse(openPortal(caller, orgId)))
        }
        post<BillingApi.ChangePlan, PlanRequest> { params, request ->
            val (plan, interval) = request.parse()
            val (caller, orgId) = call.caller(resolver, params.parent.orgId, BILLING_MANAGE)
            changePlan(caller, orgId, plan, interval)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        post<BillingApi.Cancel> { params ->
            val (caller, orgId) = call.caller(resolver, params.parent.orgId, BILLING_MANAGE)
            setCancellation(caller, orgId, cancel = true)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        post<BillingApi.Resume> { params ->
            val (caller, orgId) = call.caller(resolver, params.parent.orgId, BILLING_MANAGE)
            setCancellation(caller, orgId, cancel = false)
            call.respond(getOverview(caller, orgId).toResponse())
        }
        get<BillingApi.Invoices> { params ->
            val (caller, orgId) = call.caller(resolver, params.parent.orgId, BILLING_READ)
            call.respond(InvoicesResponse(listInvoices(caller, orgId, params.limit).map { it.toResponse() }))
        }
    }
    post<StripeWebhookApi> {
        val length = call.request.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > MAX_WEBHOOK_BYTES) throw eventTooLarge()
        val payload = call.receiveText()
        if (payload.length > MAX_WEBHOOK_BYTES) throw eventTooLarge()
        verifier.verify(payload, call.request.headers["Stripe-Signature"])
        val outcome = handleEvent(parseStripeEvent(payload))
        call.respond(WebhookResponse(received = true, outcome = outcome.name.lowercase()))
    }
}

private fun eventTooLarge() = OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "The event is too large.")

/**
 * The member the caller acts for in the organization of the path, and that organization. Only a person's credential
 * with [scope] gets there: an agent, device or service token is refused (S2-03).
 */
private suspend fun ApplicationCall.caller(
    resolver: PrincipalOrgResolver,
    orgId: String,
    scope: String,
): Pair<OrgPrincipal, Uuid> {
    if (!requirePrincipal().allowsBilling(scope)) throw BillingScopeRequiredException(scope)
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
