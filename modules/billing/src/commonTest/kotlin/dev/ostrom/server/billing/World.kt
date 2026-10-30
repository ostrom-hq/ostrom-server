// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BackfillActivePeopleUseCaseImpl
import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingSettings
import dev.ostrom.server.billing.domain.ChangePlanUseCaseImpl
import dev.ostrom.server.billing.domain.CheckEntitlementUseCaseImpl
import dev.ostrom.server.billing.domain.GetBillingOverviewUseCaseImpl
import dev.ostrom.server.billing.domain.GetPlanLimitsUseCaseImpl
import dev.ostrom.server.billing.domain.HandleStripeEventUseCaseImpl
import dev.ostrom.server.billing.domain.ListInvoicesUseCaseImpl
import dev.ostrom.server.billing.domain.OpenCustomerPortalUseCaseImpl
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.ProcessOutboxUseCaseImpl
import dev.ostrom.server.billing.domain.RecordAgentUsageUseCaseImpl
import dev.ostrom.server.billing.domain.RecordMemberActivityUseCaseImpl
import dev.ostrom.server.billing.domain.SetCancellationUseCaseImpl
import dev.ostrom.server.billing.domain.StartCheckoutUseCaseImpl
import dev.ostrom.server.billing.domain.StripeEvent
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.domain.SyncSubscriptionUseCaseImpl
import dev.ostrom.server.billing.fakes.FakeBillingRepository
import dev.ostrom.server.billing.fakes.FakeLicenseProvider
import dev.ostrom.server.billing.fakes.FakeNotifier
import dev.ostrom.server.billing.fakes.FakeStripeGateway
import dev.ostrom.server.billing.fakes.FixedClock
import dev.ostrom.server.billing.fakes.NOW
import dev.ostrom.server.billing.fakes.stripeSubscription
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
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
    val checkout = StartCheckoutUseCaseImpl(repository, stripe, settings, clock)
    val portal = OpenCustomerPortalUseCaseImpl(repository, stripe, settings)
    val sync = SyncSubscriptionUseCaseImpl(repository, stripe, clock)
    val changePlan = ChangePlanUseCaseImpl(repository, stripe, sync, settings, clock)
    val cancellation = SetCancellationUseCaseImpl(repository, stripe, sync, settings)
    val invoices = ListInvoicesUseCaseImpl(repository, stripe)
    val backfill = BackfillActivePeopleUseCaseImpl(repository, clock)
    val events = HandleStripeEventUseCaseImpl(repository, sync, notifier, backfill, clock)
    val activity = RecordMemberActivityUseCaseImpl(repository)
    val usage = RecordAgentUsageUseCaseImpl(repository, limits, notifier, clock)
    val entitlements = CheckEntitlementUseCaseImpl(repository, limits, settings, clock)
    val failures = mutableListOf<String>()
    val outbox = ProcessOutboxUseCaseImpl(
        repository,
        stripe,
        clock,
    ) { task, error -> failures += "${task.kind} ${error.message}" }

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
