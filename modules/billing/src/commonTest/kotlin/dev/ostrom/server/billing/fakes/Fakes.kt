package dev.ostrom.server.billing.fakes

import dev.ostrom.server.billing.domain.BillingNotifier
import dev.ostrom.server.billing.domain.BillingRepository
import dev.ostrom.server.billing.domain.CheckoutRequest
import dev.ostrom.server.billing.domain.Invoice
import dev.ostrom.server.billing.domain.LicenseGrant
import dev.ostrom.server.billing.domain.LicenseProvider
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.OutboxTask
import dev.ostrom.server.billing.domain.PaymentIssue
import dev.ostrom.server.billing.domain.Proration
import dev.ostrom.server.billing.domain.StripeGateway
import dev.ostrom.server.billing.domain.StripeSubscription
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.domain.Usage
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.uuid.Uuid

/** 2026-10-30T10:00:00Z. */
val NOW: Instant = Instant.parse("2026-10-30T10:00:00Z")

/** A clock the test moves by hand. */
class FixedClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now

    /** Moves the clock forward. */
    fun advance(by: Duration) {
        now += by
    }
}

/** An in-memory [BillingRepository]. */
class FakeBillingRepository : BillingRepository {
    val accounts = mutableMapOf<Uuid, OrgBilling>()
    val events = mutableMapOf<String, String>()
    val active = mutableMapOf<Pair<Uuid, Instant>, MutableList<String>>()
    val usages = mutableMapOf<Pair<Uuid, Instant>, Usage>()
    val alertsRaised = mutableMapOf<Pair<Uuid, Instant>, MutableList<Int>>()
    val tasks = mutableListOf<OutboxTask>()
    val done = mutableSetOf<Uuid>()
    val retries = mutableMapOf<Uuid, Instant>()

    override suspend fun get(orgId: Uuid): OrgBilling? = accounts[orgId]

    override suspend fun findByCustomer(customerId: String): OrgBilling? =
        accounts.values.firstOrNull { it.stripeCustomerId == customerId }

    override suspend fun save(billing: OrgBilling) {
        accounts[billing.orgId] = billing
    }

    override suspend fun isEventProcessed(eventId: String): Boolean = eventId in events

    override suspend fun markEventProcessed(eventId: String, type: String, at: Instant) {
        events[eventId] = type
    }

    override suspend fun recordActivePerson(orgId: Uuid, periodStart: Instant, userId: String, at: Instant): Boolean {
        val people = active.getOrPut(orgId to periodStart) { mutableListOf() }
        if (userId in people) return false
        people += userId
        return true
    }

    override suspend fun countActivePeople(
        orgId: Uuid,
        periodStart: Instant,
    ): Int = active[orgId to periodStart]?.size ?: 0

    override suspend fun listActivePeople(orgId: Uuid, periodStart: Instant, limit: Int): List<String> =
        active[orgId to periodStart].orEmpty().take(limit)

    override suspend fun addUsage(orgId: Uuid, periodStart: Instant, agentSeconds: Long, sessions: Long): Usage {
        val current = usages[orgId to periodStart] ?: Usage(periodStart, 0, 0)
        val updated = current.copy(
            agentSeconds = current.agentSeconds + agentSeconds,
            sessions = current.sessions + sessions,
        )
        usages[orgId to periodStart] = updated
        return updated
    }

    override suspend fun usage(
        orgId: Uuid,
        periodStart: Instant,
    ): Usage = usages[orgId to periodStart] ?: Usage(periodStart, 0, 0)

    override suspend fun recordAlert(orgId: Uuid, periodStart: Instant, percent: Int, at: Instant): Boolean {
        val list = alertsRaised.getOrPut(orgId to periodStart) { mutableListOf() }
        if (percent in list) return false
        list += percent
        return true
    }

    override suspend fun alerts(
        orgId: Uuid,
        periodStart: Instant,
    ): List<Int> = alertsRaised[orgId to periodStart].orEmpty().sorted()

    override suspend fun enqueue(task: OutboxTask) {
        if (tasks.none { it.identifier == task.identifier }) tasks += task
    }

    override suspend fun dueTasks(now: Instant, limit: Int): List<OutboxTask> =
        tasks.filter { it.id !in done && (retries[it.id] ?: it.at) <= now }.take(limit)

    override suspend fun completeTask(id: Uuid, at: Instant) {
        done += id
    }

    override suspend fun failTask(id: Uuid, retryAt: Instant) {
        retries[id] = retryAt
        val index = tasks.indexOfFirst { it.id == id }
        tasks[index] = tasks[index].copy(attempts = tasks[index].attempts + 1)
    }
}

/** A [StripeGateway] that records calls and answers what the test set. */
class FakeStripeGateway : StripeGateway {
    val calls = mutableListOf<String>()
    var subscription: StripeSubscription? = null
    var checkouts = mutableListOf<CheckoutRequest>()
    var failure: Exception? = null
    val meterEvents = mutableListOf<Triple<String, String, Instant>>()
    var invoices = listOf<Invoice>()
    var customers = 0

    private fun record(call: String) {
        calls += call
        failure?.let { throw it }
    }

    override suspend fun createCustomer(orgId: Uuid, name: String?, email: String?): String {
        record("createCustomer $orgId $name $email")
        customers++
        return "cus_$customers"
    }

    override suspend fun priceId(lookupKey: String): String {
        record("priceId $lookupKey")
        return "price_$lookupKey"
    }

    override suspend fun createCheckoutSession(request: CheckoutRequest): String {
        record("checkout ${request.priceId} ${request.quantity}")
        checkouts += request
        return "https://checkout.stripe.com/c/pay/cs_test_1"
    }

    override suspend fun createPortalSession(customerId: String, returnUrl: String): String {
        record("portal $customerId $returnUrl")
        return "https://billing.stripe.com/p/session/test_1"
    }

    override suspend fun getSubscription(subscriptionId: String): StripeSubscription {
        record("getSubscription $subscriptionId")
        return checkNotNull(subscription) { "no subscription set" }
    }

    override suspend fun changePrice(
        subscriptionId: String,
        itemId: String,
        priceId: String,
        quantity: Int?,
        proration: Proration,
    ) {
        record("changePrice $subscriptionId $itemId $priceId $quantity ${proration.stripe}")
    }

    override suspend fun setCancelAtPeriodEnd(subscriptionId: String, cancel: Boolean) {
        record("cancelAtPeriodEnd $subscriptionId $cancel")
    }

    override suspend fun setQuantity(subscriptionId: String, itemId: String, quantity: Int) {
        record("setQuantity $subscriptionId $itemId $quantity")
    }

    override suspend fun recordActivePerson(customerId: String, identifier: String, at: Instant) {
        record("meter $customerId $identifier")
        meterEvents += Triple(customerId, identifier, at)
    }

    override suspend fun listInvoices(customerId: String, limit: Int): List<Invoice> {
        record("invoices $customerId $limit")
        return invoices
    }
}

/** Members by subject and organization, with their role; the member id is derived from the subject. */
class FakeOrgResolver(private val members: Map<Pair<String, Uuid>, OrgRole>) : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? = orgId?.let { org ->
        members[principal.subjectId to org]?.let { role ->
            OrgPrincipal(org, Uuid.fromLongs(principal.subjectId.hashCode().toLong(), 0L), role)
        }
    }
}

/** A fixed license. */
class FakeLicenseProvider(var grant: LicenseGrant? = null) : LicenseProvider {
    override suspend fun current(): LicenseGrant? = grant
}

/** Records the notifications. */
class FakeNotifier : BillingNotifier {
    val hours = mutableListOf<Pair<Uuid, Int>>()
    val issues = mutableListOf<Pair<Uuid, PaymentIssue>>()

    override suspend fun agentHoursReached(orgId: Uuid, percent: Int, usedHours: Long, limitHours: Int) {
        hours += orgId to percent
    }

    override suspend fun paymentIssue(orgId: Uuid, issue: PaymentIssue) {
        issues += orgId to issue
    }
}

/** A live subscription as Stripe returns it. */
fun stripeSubscription(
    lookupKey: String? = "ostrom_team_monthly",
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    quantity: Int? = null,
    cancelAtPeriodEnd: Boolean = false,
) = StripeSubscription(
    id = "sub_1",
    customerId = "cus_1",
    status = status,
    itemId = "si_1",
    priceLookupKey = lookupKey,
    quantity = quantity,
    currentPeriodStart = Instant.parse("2026-10-15T00:00:00Z"),
    currentPeriodEnd = Instant.parse("2026-11-15T00:00:00Z"),
    cancelAtPeriodEnd = cancelAtPeriodEnd,
    orgId = null,
)
