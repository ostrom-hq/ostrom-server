// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.domain

import dev.ostrom.server.kernel.auth.OrgPrincipal
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Starts the purchase of a plan: a Stripe Checkout session with automatic tax. */
interface StartCheckoutUseCase {
    /**
     * The buyer's e-mail, name and address are asked by Checkout and saved on the customer.
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
) : StartCheckoutUseCase {
    override suspend fun invoke(caller: OrgPrincipal, orgId: Uuid, plan: Plan, interval: BillingInterval): String {
        caller.requireBillingAdminOf(orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        if (!plan.selfServe) throw PlanNotPurchasableException(plan)
        val now = clock.now()
        val existing = repository.get(orgId)
        if (existing?.hasLiveSubscription == true) throw SubscriptionAlreadyActiveException()
        val customerId = existing?.stripeCustomerId ?: stripe.createCustomer(orgId, null, null).also { id ->
            val base = existing ?: OrgBilling(orgId = orgId, updatedAt = now)
            repository.save(base.copy(stripeCustomerId = id, updatedAt = now))
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
 * The payment issue is left as it was: only the invoice events set or clear it, since an active subscription can
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
        val customerId = repository.get(orgId)?.stripeCustomerId ?: return emptyList()
        return stripe.listInvoices(customerId, limit.coerceIn(1, MAX_INVOICES))
    }
}
