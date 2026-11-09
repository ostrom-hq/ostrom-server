// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.billing.presentation

import run.ostrom.server.billing.domain.ChangePlanUseCase
import run.ostrom.server.billing.domain.GetBillingOverviewUseCase
import run.ostrom.server.billing.domain.HandleStripeEventUseCase
import run.ostrom.server.billing.domain.ListInvoicesUseCase
import run.ostrom.server.billing.domain.OpenCustomerPortalUseCase
import run.ostrom.server.billing.domain.SetCancellationUseCase
import run.ostrom.server.billing.domain.StartCheckoutUseCase
import run.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

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
