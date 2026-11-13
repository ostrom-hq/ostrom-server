// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.domain

import run.ostrom.server.kernel.auth.MemberEmails
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.errors.Pages
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** [MemberEmails] while the orgs module does not bind it: nobody's address is known, Checkout asks for it. */
object NoMemberEmails : MemberEmails {
    override suspend fun emailOf(orgId: Uuid, memberId: Uuid): String? = null
}

/** Starts the purchase of a plan: a Stripe Checkout session with automatic tax. */
interface StartCheckoutUseCase {
    /**
     * The customer carries the verified e-mail of who pays, so Checkout shows it instead of asking it; the buyer's
     * name and address are asked by Checkout and saved on the customer.
     *
     * @return The Checkout URL.
     * @throws SubscriptionAlreadyActiveException When a subscription is running.
     */
    suspend operator fun invoke(caller: OrgPrincipal, orgId: Uuid, plan: Plan, interval: BillingInterval): String
}

/** [StartCheckoutUseCase]. A yearly plan is bought for the people already active, at least one. */
class StartCheckoutUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val settings: BillingSettings,
    private val clock: Clock,
    private val emails: MemberEmails,
    private val emailUpdateFailed: (String, Exception) -> Unit,
) : StartCheckoutUseCase {
    override suspend fun invoke(caller: OrgPrincipal, orgId: Uuid, plan: Plan, interval: BillingInterval): String {
        caller.requireBillingAdminOf(orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        if (!plan.selfServe) throw PlanNotPurchasableException(plan)
        val now = clock.now()
        val existing = repository.get(orgId)
        if (existing?.hasLiveSubscription == true) throw SubscriptionAlreadyActiveException()
        // Without a verified address (or without the orgs module), Checkout asks for one.
        val email = emails.emailOf(orgId, caller.memberId)
        val stored = existing?.stripeCustomerId
        val customerId = if (stored != null) {
            // A customer made without an address (none verified then) gets the payer's now.
            email?.let { fillEmail(stored, it) }
            stored
        } else {
            stripe.createCustomer(orgId, null, email).also { id ->
                val base = existing ?: OrgBilling(orgId = orgId, updatedAt = now)
                repository.save(base.copy(stripeCustomerId = id, updatedAt = now))
            }
        }
        val quantity = if (interval == BillingInterval.YEAR) {
            maxOf(1, repository.countActivePeople(orgId, billingPeriod(existing, now).start))
        } else {
            null
        }
        val page = settings.billingPageUrl
        return stripe.createCheckoutSession(
            CheckoutRequest(
                orgId = orgId,
                customerId = customerId,
                priceId = stripe.priceId(PlanCatalog.lookupKey(plan, interval)),
                quantity = quantity,
                successUrl = "$page?checkout=success",
                cancelUrl = "$page?checkout=canceled",
            ),
        )
    }

    /**
     * Fills a missing address only: one already set (an accounting mailbox put in the portal) is never replaced by a
     * member's. The address only spares the payer a field: a refusal (Stripe down, customer deleted) never blocks
     * paying.
     */
    private suspend fun fillEmail(customerId: String, email: String) {
        try {
            if (stripe.customerEmail(customerId).isNullOrBlank()) stripe.updateCustomerEmail(customerId, email)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emailUpdateFailed(customerId, error)
        }
    }
}

/** Opens Stripe's customer portal: payment methods, invoices and receipts, billing details, cancellation. */
interface OpenCustomerPortalUseCase {
    /** @return The portal URL. @throws NoActiveSubscriptionException When the organization never bought. */
    suspend operator fun invoke(caller: OrgPrincipal, orgId: Uuid): String
}

/** [OpenCustomerPortalUseCase]. */
class OpenCustomerPortalUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val settings: BillingSettings,
) : OpenCustomerPortalUseCase {
    override suspend fun invoke(caller: OrgPrincipal, orgId: Uuid): String {
        caller.requireBillingAdminOf(orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        val customerId = repository.get(orgId)?.stripeCustomerId ?: throw NoActiveSubscriptionException()
        return stripe.createPortalSession(customerId, settings.billingPageUrl)
    }
}

/** Reads a subscription back from Stripe and stores what it says. Shared by the webhook and the change routes. */
interface SyncSubscriptionUseCase {
    /** @return The stored billing after the sync. */
    suspend operator fun invoke(billing: OrgBilling, subscriptionId: String): OrgBilling
}

/**
 * [SyncSubscriptionUseCase]. A price that is not ours keeps the previous plan and is reported to [onUnknownPrice].
 * The payment issue is left as it was, unless the subscription ended (canceled, or its first payment expired):
 * then nothing is left to pay for. Otherwise only the invoice events set or clear it, since an active subscription can
 * still have an invoice that failed or waits for the customer.
 */
class SyncSubscriptionUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val clock: Clock,
    private val onUnknownPrice: (subscriptionId: String, lookupKey: String?) -> Unit,
) : SyncSubscriptionUseCase {
    override suspend fun invoke(billing: OrgBilling, subscriptionId: String): OrgBilling {
        val subscription = stripe.getSubscription(subscriptionId)
        val known = PlanCatalog.fromLookupKey(subscription.priceLookupKey)
        if (known == null) onUnknownPrice(subscription.id, subscription.priceLookupKey)
        val (plan, interval) = known ?: (billing.plan to billing.interval)
        val ended = subscription.status == SubscriptionStatus.CANCELED ||
            subscription.status == SubscriptionStatus.INCOMPLETE_EXPIRED
        val synced = billing.copy(
            stripeCustomerId = subscription.customerId,
            stripeSubscriptionId = subscription.id,
            subscriptionItemId = subscription.itemId,
            plan = plan,
            interval = interval,
            status = subscription.status,
            currentPeriodStart = subscription.currentPeriodStart,
            currentPeriodEnd = subscription.currentPeriodEnd,
            cancelAtPeriodEnd = subscription.cancelAtPeriodEnd,
            committedPeople = if (interval == BillingInterval.YEAR) subscription.quantity ?: 0 else 0,
            paymentIssue = if (ended) null else billing.paymentIssue,
            updatedAt = clock.now(),
        )
        repository.save(synced)
        return synced
    }
}

/** Changes the plan or the interval of a running subscription. */
interface ChangePlanUseCase {
    /** @return The billing after the change. */
    suspend operator fun invoke(caller: OrgPrincipal, orgId: Uuid, plan: Plan, interval: BillingInterval): OrgBilling
}

/**
 * [ChangePlanUseCase]. An upgrade (to Business, or to yearly) is invoiced now; a downgrade is credited on the next
 * invoice. A yearly plan is set to the people already active, at least the people committed before.
 */
class ChangePlanUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val sync: SyncSubscriptionUseCase,
    private val settings: BillingSettings,
    private val clock: Clock,
) : ChangePlanUseCase {
    override suspend fun invoke(caller: OrgPrincipal, orgId: Uuid, plan: Plan, interval: BillingInterval): OrgBilling {
        caller.requireBillingAdminOf(orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        if (!plan.selfServe) throw PlanNotPurchasableException(plan)
        val live = repository.get(orgId).live() ?: throw NoActiveSubscriptionException()
        val billing = live.billing
        val subscriptionId = live.subscriptionId
        if (billing.plan == plan && billing.interval == interval) return billing
        val itemId = billing.subscriptionItemId
            ?: throw SubscriptionStateException("The subscription has no plan item.")
        val quantity = if (interval == BillingInterval.YEAR) {
            val active = repository.countActivePeople(orgId, billingPeriod(billing, clock.now()).start)
            maxOf(1, active, billing.committedPeople)
        } else {
            null
        }
        val upgrade = plan.ordinal > billing.plan.ordinal ||
            (interval == BillingInterval.YEAR && billing.interval != BillingInterval.YEAR)
        val proration = if (upgrade) Proration.ALWAYS_INVOICE else Proration.CREATE_PRORATIONS
        stripe.changePrice(
            subscriptionId,
            itemId,
            stripe.priceId(PlanCatalog.lookupKey(plan, interval)),
            quantity,
            proration,
        )
        return sync(billing, subscriptionId)
    }
}

/** Ends the subscription at the end of the period (the team keeps its plan until then), or undoes that. */
interface SetCancellationUseCase {
    /**
     * @param cancel True to end at the period end, false to keep the subscription (reactivation).
     * @return The billing after the change.
     */
    suspend operator fun invoke(caller: OrgPrincipal, orgId: Uuid, cancel: Boolean): OrgBilling
}

/** [SetCancellationUseCase]. A subscription already gone is reactivated by a new checkout, not here. */
class SetCancellationUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val sync: SyncSubscriptionUseCase,
    private val settings: BillingSettings,
) : SetCancellationUseCase {
    override suspend fun invoke(caller: OrgPrincipal, orgId: Uuid, cancel: Boolean): OrgBilling {
        caller.requireBillingAdminOf(orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        val live = repository.get(orgId).live() ?: throw NoActiveSubscriptionException()
        val billing = live.billing
        val subscriptionId = live.subscriptionId
        if (billing.cancelAtPeriodEnd == cancel) {
            throw SubscriptionStateException(
                if (cancel) {
                    "The subscription already ends at the end of the period."
                } else {
                    "The subscription is not ending."
                },
            )
        }
        stripe.setCancelAtPeriodEnd(subscriptionId, cancel)
        return sync(billing, subscriptionId)
    }
}

/**
 * Ends the subscription of a deleted organization now (the orgs module tells billing of the deletion, and again at
 * the purge): a deleted organization is never billed again. Every subscription that has not ended is ended, whatever
 * its status (a checkout paid after the deletion, a paused or unpaid one would otherwise resume). Idempotent: the
 * subscription is read from Stripe first, and one that already ended is left alone.
 */
interface EndDeletedOrgSubscriptionUseCase {
    /**
     * Ends it, if there is one.
     *
     * @param orgId The deleted organization.
     */
    suspend operator fun invoke(orgId: Uuid)
}

/**
 * [EndDeletedOrgSubscriptionUseCase]. Nothing to do on a self-hosted server (no Stripe) or for an organization without
 * a running subscription. The invoices stay with Stripe and the billing row stays here: accounting keeps them.
 *
 * @param repository The billing rows.
 * @param stripe Stripe.
 * @param sync Reads the subscription back from Stripe.
 * @param settings Whether Stripe is configured.
 */
class EndDeletedOrgSubscriptionUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val sync: SyncSubscriptionUseCase,
    private val settings: BillingSettings,
) : EndDeletedOrgSubscriptionUseCase {
    override suspend fun invoke(orgId: Uuid) {
        if (!settings.stripeEnabled) return
        val (billing, subscriptionId) = notEnded(orgId) ?: return
        // Read first: a retry after a cancellation whose sync failed must not cancel twice.
        val current = sync(billing, subscriptionId)
        if (!current.ended()) {
            stripe.cancelNow(subscriptionId)
            sync(current, subscriptionId)
        }
    }

    // The billing row and its subscription, unless there is none or it ended.
    private suspend fun notEnded(orgId: Uuid): Pair<OrgBilling, String>? {
        val billing = repository.get(orgId)?.takeUnless { it.ended() } ?: return null
        return billing.stripeSubscriptionId?.let { billing to it }
    }

    private fun OrgBilling.ended(): Boolean =
        status == SubscriptionStatus.CANCELED || status == SubscriptionStatus.INCOMPLETE_EXPIRED
}

/** Lists an organization's invoices and receipts. */
interface ListInvoicesUseCase {
    /** @return The latest invoices, newest first; empty for an organization that never bought. */
    suspend operator fun invoke(caller: OrgPrincipal, orgId: Uuid, limit: Int): List<Invoice>
}

/** [ListInvoicesUseCase]. */
class ListInvoicesUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
) : ListInvoicesUseCase {
    override suspend fun invoke(caller: OrgPrincipal, orgId: Uuid, limit: Int): List<Invoice> {
        caller.requireBillingAdminOf(orgId)
        val size = Pages.size(limit, MAX_INVOICES, MAX_INVOICES)
        val customerId = repository.get(orgId)?.stripeCustomerId ?: return emptyList()
        return stripe.listInvoices(customerId, size)
    }
}
