// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.BackfillActivePeopleUseCaseImpl
import run.ostrom.server.billing.domain.BillingInterval
import run.ostrom.server.billing.domain.BillingMetrics
import run.ostrom.server.billing.domain.BillingSettings
import run.ostrom.server.billing.domain.ChangePlanUseCaseImpl
import run.ostrom.server.billing.domain.CheckEntitlementUseCaseImpl
import run.ostrom.server.billing.domain.EndDeletedOrgSubscriptionUseCaseImpl
import run.ostrom.server.billing.domain.GetAgentUsageUseCaseImpl
import run.ostrom.server.billing.domain.GetBillingOverviewUseCaseImpl
import run.ostrom.server.billing.domain.GetPlanLimitsUseCaseImpl
import run.ostrom.server.billing.domain.HandleStripeEventUseCaseImpl
import run.ostrom.server.billing.domain.ListInvoicesUseCaseImpl
import run.ostrom.server.billing.domain.OpenCustomerPortalUseCaseImpl
import run.ostrom.server.billing.domain.OrgBilling
import run.ostrom.server.billing.domain.Plan
import run.ostrom.server.billing.domain.ProcessOutboxUseCaseImpl
import run.ostrom.server.billing.domain.RecordAgentUsageUseCaseImpl
import run.ostrom.server.billing.domain.RecordMemberActivityUseCaseImpl
import run.ostrom.server.billing.domain.SetCancellationUseCaseImpl
import run.ostrom.server.billing.domain.StartCheckoutUseCaseImpl
import run.ostrom.server.billing.domain.StripeEvent
import run.ostrom.server.billing.domain.SubscriptionStatus
import run.ostrom.server.billing.domain.SyncSubscriptionUseCaseImpl
import run.ostrom.server.billing.fakes.FakeBillingRepository
import run.ostrom.server.billing.fakes.FakeLicenseProvider
import run.ostrom.server.billing.fakes.FakeMemberEmails
import run.ostrom.server.billing.fakes.FakeNotifier
import run.ostrom.server.billing.fakes.FakeStripeGateway
import run.ostrom.server.billing.fakes.FixedClock
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.fakes.stripeSubscription
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Every use case wired on fakes, as the module wires them on real implementations. */
internal class World(stripeEnabled: Boolean = true) {
    val org: Uuid = Uuid.parse("0195f7a2-0000-7000-8000-000000000001")
    val repository = FakeBillingRepository()
    val stripe = FakeStripeGateway()
    val admin = OrgPrincipal(org, Uuid.parse("0195f7a2-0000-7000-8000-0000000000a1"), OrgRole.ADMIN)
    val member = OrgPrincipal(org, Uuid.parse("0195f7a2-0000-7000-8000-0000000000a2"), OrgRole.MEMBER)
    val stranger = OrgPrincipal(Uuid.parse("0195f7a2-0000-7000-8000-000000000002"), admin.memberId, OrgRole.OWNER)
    val license = FakeLicenseProvider()
    val notifier = FakeNotifier()
    val clock = FixedClock()
    val settings = BillingSettings(stripeEnabled, "https://app.ostrom.run/")
    val limits = GetPlanLimitsUseCaseImpl(repository, license, settings, clock)
    val overview = GetBillingOverviewUseCaseImpl(repository, limits, settings, clock)
    val emails = FakeMemberEmails()
    val emailFailures = mutableListOf<String>()
    val checkout = StartCheckoutUseCaseImpl(repository, stripe, settings, clock, emails) { customer, error ->
        emailFailures += "$customer ${error::class.simpleName}"
    }
    val portal = OpenCustomerPortalUseCaseImpl(repository, stripe, settings)
    val unknownPrices = mutableListOf<Pair<String, String?>>()
    val sync = SyncSubscriptionUseCaseImpl(repository, stripe, clock) { id, key -> unknownPrices += id to key }
    val changePlan = ChangePlanUseCaseImpl(repository, stripe, sync, settings, clock)
    val cancellation = SetCancellationUseCaseImpl(repository, stripe, sync, settings)
    val invoices = ListInvoicesUseCaseImpl(repository, stripe)
    val endSubscription = EndDeletedOrgSubscriptionUseCaseImpl(repository, stripe, sync, settings)
    val backfill = BackfillActivePeopleUseCaseImpl(repository, clock)
    val metrics = FakeBillingMetrics()
    val events = HandleStripeEventUseCaseImpl(repository, sync, notifier, backfill, metrics, clock)
    val activity = RecordMemberActivityUseCaseImpl(repository)
    val usage = RecordAgentUsageUseCaseImpl(repository, limits, notifier, clock)
    val readUsage = GetAgentUsageUseCaseImpl(repository, clock)
    val entitlements = CheckEntitlementUseCaseImpl(repository, limits, settings, clock)
    val failures = mutableListOf<String>()
    val outbox = ProcessOutboxUseCaseImpl(
        repository,
        stripe,
        clock,
    ) { task, error -> failures += "${task.kind} ${error.message}" }

    /** Stores the billing row an organization gets when it opens a checkout, before Stripe knows it. */
    suspend fun opensACheckout() {
        repository.save(OrgBilling(orgId = org, updatedAt = NOW))
    }

    /** Stores a live subscription of [plan] and makes Stripe return it. */
    suspend fun subscribe(
        plan: Plan = Plan.TEAM,
        interval: BillingInterval = BillingInterval.MONTH,
        committed: Int = 0,
    ): OrgBilling {
        val billing = OrgBilling(
            orgId = org,
            stripeCustomerId = "cus_1",
            stripeSubscriptionId = "sub_1",
            subscriptionItemId = "si_1",
            plan = plan,
            interval = interval,
            status = SubscriptionStatus.ACTIVE,
            currentPeriodStart = Instant.parse("2026-10-15T00:00:00Z"),
            currentPeriodEnd = Instant.parse("2026-11-15T00:00:00Z"),
            committedPeople = committed,
            updatedAt = NOW,
        )
        repository.save(billing)
        stripe.subscription = stripeSubscription(
            lookupKey = "ostrom_${plan.key}_${if (interval == BillingInterval.MONTH) "monthly" else "yearly"}",
            quantity = if (interval == BillingInterval.YEAR) committed else null,
        )
        return billing
    }

    /** An event of [type] about `cus_1` and `sub_1`; tests change the rest with `copy`. */
    fun event(type: String, id: String = "evt_1") = StripeEvent(id, type, NOW, "cus_1", "sub_1", null, null)
}

/** The start of the subscription period the tests use. */
internal val PERIOD = Instant.parse("2026-10-15T00:00:00Z")
/** The calendar month of [NOW]. */
internal val MONTH = Instant.parse("2026-10-01T00:00:00Z")

/** Records the events billing counted. */
internal class FakeBillingMetrics : BillingMetrics {
    val unknownOrganizations = mutableListOf<String>()

    override fun stripeEventForUnknownOrganization(eventId: String, eventType: String) {
        unknownOrganizations += "$eventId $eventType"
    }
}
