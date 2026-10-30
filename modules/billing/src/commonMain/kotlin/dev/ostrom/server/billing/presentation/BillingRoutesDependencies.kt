package dev.ostrom.server.billing.presentation

import dev.ostrom.server.billing.domain.ChangePlanUseCase
import dev.ostrom.server.billing.domain.GetBillingOverviewUseCase
import dev.ostrom.server.billing.domain.HandleStripeEventUseCase
import dev.ostrom.server.billing.domain.ListInvoicesUseCase
import dev.ostrom.server.billing.domain.OpenCustomerPortalUseCase
import dev.ostrom.server.billing.domain.SetCancellationUseCase
import dev.ostrom.server.billing.domain.StartCheckoutUseCase
import dev.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver

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
    val resolver: PrincipalOrgResolver,
)
