package dev.ostrom.server.billing.domain

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The thresholds of agent-hour alerts, in percent of the period's hours. */
val ALERT_THRESHOLDS = listOf(80, 100)

/** Upper bound of the people read at once (a backfill, a listing). */
const val MAX_PEOPLE_READ = 1_000

/**
 * The billing of an organization as the console shows it.
 *
 * @property billing The stored state (a fresh Free row when nothing is stored).
 * @property effectivePlan The plan whose limits apply now.
 * @property limits Those limits.
 * @property period The current billing period.
 * @property activePeople The people active in the period.
 * @property usage The cloud agent usage of the period.
 * @property alerts The usage thresholds reached in the period.
 * @property stripeEnabled Whether plans can be bought on this instance.
 */
data class BillingOverview(
    val billing: OrgBilling,
    val effectivePlan: Plan,
    val limits: PlanLimits,
    val period: BillingPeriod,
    val activePeople: Int,
    val usage: Usage,
    val alerts: List<Int>,
    val stripeEnabled: Boolean,
)

/** Reads the limits of an organization: its plan on Ostrom Cloud, its license on a self-hosted instance. */
interface GetPlanLimitsUseCase {
    /** @return The limits in force now. */
    suspend operator fun invoke(orgId: Uuid): PlanLimits
}

/**
 * [GetPlanLimitsUseCase]. A self-hosted instance has no cloud costs to protect: people, agents and hours are
 * unlimited (or the license's people), and SSO, policies and long retention need a license that grants them.
 */
class GetPlanLimitsUseCaseImpl(
    private val repository: BillingRepository,
    private val licenses: LicenseProvider,
    private val settings: BillingSettings,
    private val clock: Clock,
) : GetPlanLimitsUseCase {
    override suspend fun invoke(orgId: Uuid): PlanLimits {
        if (!settings.stripeEnabled) return selfHostedLimits(licenses.current())
        val billing = repository.get(orgId)
        val period = billingPeriod(billing, clock.now())
        val plan = billing?.effectivePlan ?: Plan.FREE
        val people = maxOf(repository.countActivePeople(orgId, period.start), billing?.committedPeople ?: 0)
        return PlanCatalog.limits(plan, people)
    }

    private fun selfHostedLimits(license: LicenseGrant?): PlanLimits {
        val features = license?.features.orEmpty()
        return PlanLimits(
            maxPeople = license?.maxPeople,
            maxConcurrentCloudAgents = null,
            agentHoursPerPeriod = null,
            sso = "sso" in features,
            policies = "policies" in features,
            receiptRetentionDays = if ("retention" in features) null else 30,
        )
    }
}

/** Builds the console's view of an organization's billing. */
interface GetBillingOverviewUseCase {
    /** @throws BillingAccessDeniedException When [subjectId] is not a member. */
    suspend operator fun invoke(subjectId: String, orgId: Uuid): BillingOverview
}

/** [GetBillingOverviewUseCase]. */
class GetBillingOverviewUseCaseImpl(
    private val repository: BillingRepository,
    private val access: BillingAccessPolicy,
    private val limits: GetPlanLimitsUseCase,
    private val settings: BillingSettings,
    private val clock: Clock,
) : GetBillingOverviewUseCase {
    override suspend fun invoke(subjectId: String, orgId: Uuid): BillingOverview {
        access.requireMember(subjectId, orgId)
        val now = clock.now()
        val billing = repository.get(orgId) ?: OrgBilling(orgId = orgId, updatedAt = now)
        val period = billingPeriod(billing, now)
        return BillingOverview(
            billing = billing,
            effectivePlan = billing.effectivePlan,
            limits = limits(orgId),
            period = period,
            activePeople = repository.countActivePeople(orgId, period.start),
            usage = repository.usage(orgId, period.start),
            alerts = repository.alerts(orgId, period.start),
            stripeEnabled = settings.stripeEnabled,
        )
    }
}

/** Starts the purchase of a plan: a Stripe Checkout session with automatic tax. */
interface StartCheckoutUseCase {
    /**
     * @param email The buyer's e-mail, prefilled on the customer.
     * @return The Checkout URL.
     * @throws SubscriptionAlreadyActiveException When a subscription is running.
     */
    suspend operator fun invoke(subjectId: String, orgId: Uuid, orgName: String?, email: String?, plan: Plan, interval: BillingInterval): String
}

/** [StartCheckoutUseCase]. A yearly plan is bought for the people already active, at least one. */
class StartCheckoutUseCaseImpl(
    private val repository: BillingRepository,
    private val access: BillingAccessPolicy,
    private val stripe: StripeGateway,
    private val settings: BillingSettings,
    private val clock: Clock,
) : StartCheckoutUseCase {
    override suspend fun invoke(
        subjectId: String,
        orgId: Uuid,
        orgName: String?,
        email: String?,
        plan: Plan,
        interval: BillingInterval,
    ): String {
        access.requireBillingAdmin(subjectId, orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        if (!plan.selfServe) throw PlanNotPurchasableException(plan)
        val now = clock.now()
        val existing = repository.get(orgId)
        if (existing?.hasLiveSubscription == true) throw SubscriptionAlreadyActiveException()
        val customerId = existing?.stripeCustomerId ?: stripe.createCustomer(orgId, orgName, email).also { id ->
            repository.save((existing ?: OrgBilling(orgId = orgId, updatedAt = now)).copy(stripeCustomerId = id, updatedAt = now))
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
    suspend operator fun invoke(subjectId: String, orgId: Uuid): String
}

/** [OpenCustomerPortalUseCase]. */
class OpenCustomerPortalUseCaseImpl(
    private val repository: BillingRepository,
    private val access: BillingAccessPolicy,
    private val stripe: StripeGateway,
    private val settings: BillingSettings,
) : OpenCustomerPortalUseCase {
    override suspend fun invoke(subjectId: String, orgId: Uuid): String {
        access.requireBillingAdmin(subjectId, orgId)
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

/** [SyncSubscriptionUseCase]. A price that is not ours keeps the previous plan, and is logged by the caller. */
class SyncSubscriptionUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val clock: Clock,
) : SyncSubscriptionUseCase {
    override suspend fun invoke(billing: OrgBilling, subscriptionId: String): OrgBilling {
        val subscription = stripe.getSubscription(subscriptionId)
        val planAndInterval = PlanCatalog.fromLookupKey(subscription.priceLookupKey)
        val interval = planAndInterval?.second ?: billing.interval
        val synced = billing.copy(
            stripeCustomerId = subscription.customerId,
            stripeSubscriptionId = subscription.id,
            subscriptionItemId = subscription.itemId,
            plan = planAndInterval?.first ?: billing.plan,
            interval = interval,
            status = subscription.status,
            currentPeriodStart = subscription.currentPeriodStart,
            currentPeriodEnd = subscription.currentPeriodEnd,
            cancelAtPeriodEnd = subscription.cancelAtPeriodEnd,
            committedPeople = if (interval == BillingInterval.YEAR) subscription.quantity ?: 0 else 0,
            paymentIssue = if (subscription.status == SubscriptionStatus.ACTIVE) null else billing.paymentIssue,
            updatedAt = clock.now(),
        )
        repository.save(synced)
        return synced
    }
}

/** Changes the plan or the interval of a running subscription. */
interface ChangePlanUseCase {
    /** @return The billing after the change. */
    suspend operator fun invoke(subjectId: String, orgId: Uuid, plan: Plan, interval: BillingInterval): OrgBilling
}

/**
 * [ChangePlanUseCase]. An upgrade (to Business, or to yearly) is invoiced now; a downgrade is credited on the next
 * invoice. A yearly plan is set to the people already active, at least the people committed before.
 */
class ChangePlanUseCaseImpl(
    private val repository: BillingRepository,
    private val access: BillingAccessPolicy,
    private val stripe: StripeGateway,
    private val sync: SyncSubscriptionUseCase,
    private val settings: BillingSettings,
    private val clock: Clock,
) : ChangePlanUseCase {
    override suspend fun invoke(subjectId: String, orgId: Uuid, plan: Plan, interval: BillingInterval): OrgBilling {
        access.requireBillingAdmin(subjectId, orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        if (!plan.selfServe) throw PlanNotPurchasableException(plan)
        val billing = repository.get(orgId)?.takeIf { it.hasLiveSubscription } ?: throw NoActiveSubscriptionException()
        val subscriptionId = checkNotNull(billing.stripeSubscriptionId) { "A live subscription has an id." }
        if (billing.plan == plan && billing.interval == interval) return billing
        val itemId = billing.subscriptionItemId ?: throw SubscriptionStateException("The subscription has no plan item.")
        val quantity = if (interval == BillingInterval.YEAR) {
            val active = repository.countActivePeople(orgId, billingPeriod(billing, clock.now()).start)
            maxOf(1, active, billing.committedPeople)
        } else {
            null
        }
        val upgrade = plan.ordinal > billing.plan.ordinal ||
            (interval == BillingInterval.YEAR && billing.interval != BillingInterval.YEAR)
        val proration = if (upgrade) Proration.ALWAYS_INVOICE else Proration.CREATE_PRORATIONS
        stripe.changePrice(subscriptionId, itemId, stripe.priceId(PlanCatalog.lookupKey(plan, interval)), quantity, proration)
        return sync(billing, subscriptionId)
    }
}

/** Ends the subscription at the end of the period (the team keeps its plan until then), or undoes that. */
interface SetCancellationUseCase {
    /**
     * @param cancel True to end at the period end, false to keep the subscription (reactivation).
     * @return The billing after the change.
     */
    suspend operator fun invoke(subjectId: String, orgId: Uuid, cancel: Boolean): OrgBilling
}

/** [SetCancellationUseCase]. A subscription already gone is reactivated by a new checkout, not here. */
class SetCancellationUseCaseImpl(
    private val repository: BillingRepository,
    private val access: BillingAccessPolicy,
    private val stripe: StripeGateway,
    private val sync: SyncSubscriptionUseCase,
    private val settings: BillingSettings,
) : SetCancellationUseCase {
    override suspend fun invoke(subjectId: String, orgId: Uuid, cancel: Boolean): OrgBilling {
        access.requireBillingAdmin(subjectId, orgId)
        if (!settings.stripeEnabled) throw BillingNotConfiguredException()
        val billing = repository.get(orgId)?.takeIf { it.hasLiveSubscription } ?: throw NoActiveSubscriptionException()
        val subscriptionId = checkNotNull(billing.stripeSubscriptionId) { "A live subscription has an id." }
        if (billing.cancelAtPeriodEnd == cancel) {
            throw SubscriptionStateException(
                if (cancel) "The subscription already ends at the end of the period." else "The subscription is not ending.",
            )
        }
        stripe.setCancelAtPeriodEnd(subscriptionId, cancel)
        return sync(billing, subscriptionId)
    }
}

/** Lists an organization's invoices and receipts. */
interface ListInvoicesUseCase {
    /** @return The latest invoices, newest first; empty for an organization that never bought. */
    suspend operator fun invoke(subjectId: String, orgId: Uuid, limit: Int): List<Invoice>
}

/** [ListInvoicesUseCase]. */
class ListInvoicesUseCaseImpl(
    private val repository: BillingRepository,
    private val access: BillingAccessPolicy,
    private val stripe: StripeGateway,
) : ListInvoicesUseCase {
    override suspend fun invoke(subjectId: String, orgId: Uuid, limit: Int): List<Invoice> {
        access.requireBillingAdmin(subjectId, orgId)
        val customerId = repository.get(orgId)?.stripeCustomerId ?: return emptyList()
        return stripe.listInvoices(customerId, limit.coerceIn(1, 100))
    }
}

/** What happened to an event. */
enum class EventOutcome { PROCESSED, DUPLICATE, IGNORED }

/** Applies a verified Stripe event, once. */
interface HandleStripeEventUseCase {
    /** @return What was done. */
    suspend operator fun invoke(event: StripeEvent): EventOutcome
}

/**
 * [HandleStripeEventUseCase]. Every event that concerns a subscription re-reads it from Stripe, so the order of
 * delivery does not matter and a replayed event changes nothing. The event is marked processed only after the state
 * is saved: if the server dies in between, Stripe's retry processes it again, harmlessly.
 */
class HandleStripeEventUseCaseImpl(
    private val repository: BillingRepository,
    private val sync: SyncSubscriptionUseCase,
    private val notifier: BillingNotifier,
    private val backfill: BackfillActivePeopleUseCase,
    private val clock: Clock,
) : HandleStripeEventUseCase {
    override suspend fun invoke(event: StripeEvent): EventOutcome {
        if (repository.isEventProcessed(event.id)) return EventOutcome.DUPLICATE
        val outcome = apply(event)
        repository.markEventProcessed(event.id, event.type, clock.now())
        return outcome
    }

    private suspend fun apply(event: StripeEvent): EventOutcome {
        val billing = findBilling(event) ?: return EventOutcome.IGNORED
        val subscriptionId = event.subscriptionId ?: billing.stripeSubscriptionId ?: return EventOutcome.IGNORED
        return when (event.type) {
            "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                val synced = sync(billing.copy(stripeCustomerId = event.customerId ?: billing.stripeCustomerId), subscriptionId)
                backfill(synced)
                EventOutcome.PROCESSED
            }
            "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted",
            "customer.subscription.paused", "customer.subscription.resumed", "checkout.session.async_payment_failed",
            "invoice.finalized",
            -> {
                sync(billing, subscriptionId)
                EventOutcome.PROCESSED
            }
            "invoice.paid" -> {
                sync(billing.copy(paymentIssue = null), subscriptionId)
                EventOutcome.PROCESSED
            }
            "invoice.payment_failed", "invoice.payment_action_required" -> {
                val kind = if (event.type == "invoice.payment_failed") PaymentIssueKind.FAILED else PaymentIssueKind.ACTION_REQUIRED
                val issue = PaymentIssue(kind, event.hostedInvoiceUrl, event.created)
                sync(billing.copy(paymentIssue = issue), subscriptionId)
                notifier.paymentIssue(billing.orgId, issue)
                EventOutcome.PROCESSED
            }
            else -> EventOutcome.IGNORED
        }
    }

    private suspend fun findBilling(event: StripeEvent): OrgBilling? {
        event.customerId?.let { customerId -> repository.findByCustomer(customerId)?.let { return it } }
        val orgId = event.orgId?.let(::parseUuid) ?: return null
        return repository.get(orgId) ?: OrgBilling(orgId = orgId, updatedAt = clock.now())
    }
}

/**
 * Counts the people already active in the new period once a subscription starts: they were active before the
 * subscription existed, so no meter event was sent for them.
 */
interface BackfillActivePeopleUseCase {
    /** Queues what the new subscription must be told. */
    suspend operator fun invoke(billing: OrgBilling)
}

/** [BackfillActivePeopleUseCase]. */
class BackfillActivePeopleUseCaseImpl(
    private val repository: BillingRepository,
    private val clock: Clock,
) : BackfillActivePeopleUseCase {
    override suspend fun invoke(billing: OrgBilling) {
        if (!billing.hasLiveSubscription) return
        val now = clock.now()
        val period = billingPeriod(billing, now)
        val people = repository.listActivePeople(billing.orgId, calendarMonth(now).start, MAX_PEOPLE_READ)
        for (userId in people) {
            if (repository.recordActivePerson(billing.orgId, period.start, userId, now)) {
                queueActivity(repository, billing, period, userId, now)
            }
        }
    }
}

/** Records that a member launched an agent or answered a gate: they are an active person this period. */
interface RecordMemberActivityUseCase {
    /** Never fails because of Stripe: what Stripe must know is queued. */
    suspend operator fun invoke(orgId: Uuid, userId: String, at: Instant)
}

/** [RecordMemberActivityUseCase]. */
class RecordMemberActivityUseCaseImpl(
    private val repository: BillingRepository,
) : RecordMemberActivityUseCase {
    override suspend fun invoke(orgId: Uuid, userId: String, at: Instant) {
        val billing = repository.get(orgId)
        val period = billingPeriod(billing, at)
        // Free organizations are counted by calendar month too: the count shows in the console and is the base of a
        // backfill if they subscribe this month.
        val first = repository.recordActivePerson(orgId, period.start, userId, at)
        if (first && billing?.hasLiveSubscription == true) queueActivity(repository, billing, period, userId, at)
    }
}

private suspend fun queueActivity(
    repository: BillingRepository,
    billing: OrgBilling,
    period: BillingPeriod,
    userId: String,
    at: Instant,
) {
    val periodKey = period.start.epochSeconds
    when (billing.interval) {
        BillingInterval.MONTH -> repository.enqueue(
            OutboxTask(Uuid.random(), billing.orgId, OutboxKind.ACTIVE_PERSON, "ostrom-${billing.orgId}-$periodKey-$userId", at, 0),
        )
        BillingInterval.YEAR -> if (repository.countActivePeople(billing.orgId, period.start) > billing.committedPeople) {
            repository.enqueue(
                OutboxTask(Uuid.random(), billing.orgId, OutboxKind.TRUE_UP, "ostrom-${billing.orgId}-$periodKey-trueup-$userId", at, 0),
            )
        }
        null -> Unit
    }
}

/** Adds the time of a finished (or running) cloud agent to the period and raises the 80 % and 100 % alerts once. */
interface RecordAgentUsageUseCase {
    /** @return The period's usage after the addition. */
    suspend operator fun invoke(orgId: Uuid, agentSeconds: Long, sessions: Long): Usage
}

/** [RecordAgentUsageUseCase]. */
class RecordAgentUsageUseCaseImpl(
    private val repository: BillingRepository,
    private val limits: GetPlanLimitsUseCase,
    private val notifier: BillingNotifier,
    private val clock: Clock,
) : RecordAgentUsageUseCase {
    override suspend fun invoke(orgId: Uuid, agentSeconds: Long, sessions: Long): Usage {
        require(agentSeconds >= 0 && sessions >= 0) { "Usage is never negative." }
        val now = clock.now()
        val period = billingPeriod(repository.get(orgId), now)
        val usage = repository.addUsage(orgId, period.start, agentSeconds, sessions)
        val limit = limits(orgId).agentHoursPerPeriod ?: return usage
        val percent = (usage.agentSeconds * 100 / (limit * 3600L)).toInt()
        for (threshold in ALERT_THRESHOLDS.filter { it <= percent }) {
            if (repository.recordAlert(orgId, period.start, threshold, now)) {
                notifier.agentHoursReached(orgId, threshold, usage.agentHours, limit)
            }
        }
        return usage
    }
}

/** Checks a use of the plan before it happens. Called by orgs (members), runners (cloud agents), identity (SSO). */
interface CheckEntitlementUseCase {
    /** @throws PlanLimitException With a message saying the limit, the use, and where to upgrade. */
    suspend operator fun invoke(orgId: Uuid, entitlement: Entitlement)
}

/** [CheckEntitlementUseCase]. */
class CheckEntitlementUseCaseImpl(
    private val repository: BillingRepository,
    private val limits: GetPlanLimitsUseCase,
    private val settings: BillingSettings,
    private val clock: Clock,
) : CheckEntitlementUseCase {
    override suspend fun invoke(orgId: Uuid, entitlement: Entitlement) {
        val limits = limits(orgId)
        val billing = repository.get(orgId)
        val planName = if (settings.stripeEnabled) "The ${(billing?.effectivePlan ?: Plan.FREE).displayName} plan" else "This instance's license"
        val upgrade = if (settings.stripeEnabled) "Upgrade at ${settings.billingPageUrl}" else "Contact sales@ostrom.run for a license"
        when (entitlement) {
            is Entitlement.AddPerson -> limits.maxPeople?.let { max ->
                if (entitlement.currentPeople >= max) {
                    throw PlanLimitException("$planName includes $max people and this organization has ${entitlement.currentPeople}. $upgrade.")
                }
            }
            is Entitlement.StartCloudAgent -> {
                limits.maxConcurrentCloudAgents?.let { max ->
                    if (entitlement.running >= max) {
                        throw PlanLimitException(
                            "$planName runs $max cloud agents at the same time and ${entitlement.running} are running. " +
                                "Wait for one to finish, or upgrade at ${settings.billingPageUrl}.",
                        )
                    }
                }
                limits.agentHoursPerPeriod?.let { hours ->
                    val period = billingPeriod(billing, clock.now())
                    if (repository.usage(orgId, period.start).agentSeconds >= hours * 3600L) {
                        throw PlanLimitException(
                            "This organization used the $hours cloud agent hours of its plan for this period " +
                                "(it renews on ${period.end.toString().substring(0, 10)}). $upgrade.",
                        )
                    }
                }
            }
            Entitlement.Sso -> if (!limits.sso) throw PlanLimitException("Single sign-on is part of the Business plan. $upgrade.")
            Entitlement.Policies -> if (!limits.policies) {
                throw PlanLimitException("Organization policies are part of the Business plan. $upgrade.")
            }
        }
    }
}

/** The plan's name as people read it. */
val Plan.displayName: String get() = key.replaceFirstChar { it.uppercase() }

/** Sends the queued tasks to Stripe, with a growing delay between attempts. */
interface ProcessOutboxUseCase {
    /** @return How many tasks were sent. */
    suspend operator fun invoke(limit: Int): Int
}

/**
 * [ProcessOutboxUseCase]. A failure is retried after 30 s, 1 min, 2 min… up to 1 h; Stripe accepts meter events up to
 * 35 days old, and the identifier makes a retry count once.
 */
class ProcessOutboxUseCaseImpl(
    private val repository: BillingRepository,
    private val stripe: StripeGateway,
    private val clock: Clock,
    private val onFailure: (OutboxTask, Exception) -> Unit,
) : ProcessOutboxUseCase {
    override suspend fun invoke(limit: Int): Int {
        var sent = 0
        for (task in repository.dueTasks(clock.now(), limit)) {
            try {
                send(task)
                repository.completeTask(task.id, clock.now())
                sent++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onFailure(task, error)
                val delay = (30.seconds * (1 shl task.attempts.coerceAtMost(7))).coerceAtMost(60.minutes)
                repository.failTask(task.id, clock.now() + delay)
            }
        }
        return sent
    }

    private suspend fun send(task: OutboxTask) {
        val billing = repository.get(task.orgId)?.takeIf { it.hasLiveSubscription } ?: return
        val customerId = checkNotNull(billing.stripeCustomerId) { "A live subscription has a customer." }
        when (task.kind) {
            OutboxKind.ACTIVE_PERSON -> stripe.recordActivePerson(customerId, task.identifier, task.at)
            OutboxKind.TRUE_UP -> {
                val period = billingPeriod(billing, clock.now())
                val active = repository.countActivePeople(task.orgId, period.start)
                val itemId = billing.subscriptionItemId
                if (billing.interval == BillingInterval.YEAR && itemId != null && active > billing.committedPeople) {
                    stripe.setQuantity(checkNotNull(billing.stripeSubscriptionId), itemId, active)
                    repository.save(billing.copy(committedPeople = active, updatedAt = clock.now()))
                }
            }
        }
    }
}

private fun parseUuid(value: String): Uuid? = try {
    Uuid.parse(value)
} catch (_: IllegalArgumentException) {
    null
}
