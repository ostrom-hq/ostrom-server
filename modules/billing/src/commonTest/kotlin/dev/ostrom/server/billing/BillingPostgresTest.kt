package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BillingAccessPolicy
import dev.ostrom.server.billing.domain.BillingInterval
import dev.ostrom.server.billing.domain.BillingRepository
import dev.ostrom.server.billing.domain.Invoice
import dev.ostrom.server.billing.domain.OrgBilling
import dev.ostrom.server.billing.domain.OutboxKind
import dev.ostrom.server.billing.domain.OutboxTask
import dev.ostrom.server.billing.domain.PaymentIssue
import dev.ostrom.server.billing.domain.PaymentIssueKind
import dev.ostrom.server.billing.domain.Plan
import dev.ostrom.server.billing.domain.StripeGateway
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.domain.Usage
import dev.ostrom.server.billing.fakes.FakeAccessPolicy
import dev.ostrom.server.billing.fakes.FakeStripeGateway
import dev.ostrom.server.billing.fakes.FixedClock
import dev.ostrom.server.billing.fakes.NOW
import dev.ostrom.server.billing.fakes.stripeSubscription
import dev.ostrom.server.billing.infrastructure.hmacSha256Hex
import dev.ostrom.server.billing.presentation.BillingOverviewResponse
import dev.ostrom.server.billing.presentation.InvoicesResponse
import dev.ostrom.server.billing.presentation.PlanRequest
import dev.ostrom.server.billing.presentation.RedirectResponse
import dev.ostrom.server.billing.presentation.WebhookResponse
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.koin.dsl.module

private val ORG_A = Uuid.parse("0195f7a2-0000-7000-8000-00000000000a")
private val ORG_B = Uuid.parse("0195f7a2-0000-7000-8000-00000000000b")
private const val WEBHOOK_SECRET = "whsec_postgres_test"

/** Tokens `admin-a`, `member-a`, `admin-b`; the subject id is the token. */
private class TokensAuthenticator : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
        if (token in setOf("admin-a", "member-a", "admin-b")) AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet()) else null
}

class BillingPostgresTest {
    private val stripe = FakeStripeGateway()
    private val clock = FixedClock()
    private val overrides = module {
        single<ConfigSource> {
            MapConfigSource(mapOf("OSTROM_STRIPE_SECRET_KEY" to "sk_test_x", "OSTROM_STRIPE_WEBHOOK_SECRET" to WEBHOOK_SECRET))
        }
        single<Clock> { clock }
        single<StripeGateway> { stripe }
        single<TokenAuthenticator> { TokensAuthenticator() }
        single<BillingAccessPolicy> {
            FakeAccessPolicy(
                members = mutableSetOf("member-a" to ORG_A),
                admins = mutableSetOf("admin-a" to ORG_A, "admin-b" to ORG_B),
            )
        }
    }

    @Test
    fun theRepositoryRoundTripsEveryField() = serverTest(listOf(BillingModule), overrides) { server ->
        val repository = server.koin.get<BillingRepository>()
        assertNull(repository.get(ORG_A))
        val full = OrgBilling(
            orgId = ORG_A,
            stripeCustomerId = "cus_a",
            stripeSubscriptionId = "sub_a",
            subscriptionItemId = "si_a",
            plan = Plan.BUSINESS,
            interval = BillingInterval.YEAR,
            status = SubscriptionStatus.PAST_DUE,
            currentPeriodStart = Instant.parse("2026-10-15T00:00:00Z"),
            currentPeriodEnd = Instant.parse("2027-10-15T00:00:00Z"),
            cancelAtPeriodEnd = true,
            committedPeople = 7,
            paymentIssue = PaymentIssue(PaymentIssueKind.ACTION_REQUIRED, "https://pay", NOW),
            updatedAt = NOW,
        )
        repository.save(full)
        assertEquals(full, repository.get(ORG_A))
        assertEquals(full, repository.findByCustomer("cus_a"))
        val bare = OrgBilling(orgId = ORG_B, updatedAt = NOW)
        repository.save(bare)
        assertEquals(bare, repository.get(ORG_B))
        repository.save(full.copy(paymentIssue = null, status = SubscriptionStatus.ACTIVE))
        assertNull(repository.get(ORG_A)?.paymentIssue)
        assertNull(repository.findByCustomer("cus_unknown"))
    }

    @Test
    fun peopleUsageAlertsEventsAndTasksAreIdempotent() = serverTest(listOf(BillingModule), overrides) { server ->
        val repository = server.koin.get<BillingRepository>()
        val period = Instant.parse("2026-10-01T00:00:00Z")
        assertTrue(repository.recordActivePerson(ORG_A, period, "alice", NOW))
        assertFalse(repository.recordActivePerson(ORG_A, period, "alice", NOW))
        assertTrue(repository.recordActivePerson(ORG_A, period, "bob", NOW + 1.minutes))
        assertTrue(repository.recordActivePerson(ORG_B, period, "alice", NOW))
        assertEquals(2, repository.countActivePeople(ORG_A, period))
        assertEquals(listOf("alice"), repository.listActivePeople(ORG_A, period, 1))
        assertEquals(Usage(period, 0, 0), repository.usage(ORG_A, period))
        repository.addUsage(ORG_A, period, 100, 1)
        assertEquals(Usage(period, 150, 3), repository.addUsage(ORG_A, period, 50, 2))
        assertEquals(Usage(period, 0, 0), repository.usage(ORG_B, period), "Another organization's usage is not seen")
        assertTrue(repository.recordAlert(ORG_A, period, 100, NOW))
        assertTrue(repository.recordAlert(ORG_A, period, 80, NOW))
        assertFalse(repository.recordAlert(ORG_A, period, 80, NOW))
        assertEquals(listOf(80, 100), repository.alerts(ORG_A, period))
        assertEquals(emptyList(), repository.alerts(ORG_B, period))
        assertFalse(repository.isEventProcessed("evt_1"))
        repository.markEventProcessed("evt_1", "invoice.paid", NOW)
        repository.markEventProcessed("evt_1", "invoice.paid", NOW)
        assertTrue(repository.isEventProcessed("evt_1"))
        val task = OutboxTask(Uuid.random(), ORG_A, OutboxKind.ACTIVE_PERSON, "ostrom-a-1-alice", NOW, 0)
        repository.enqueue(task)
        repository.enqueue(task.copy(id = Uuid.random()))
        assertEquals(listOf(task), repository.dueTasks(NOW, 10))
        assertEquals(emptyList(), repository.dueTasks(NOW - 1.minutes, 10))
        repository.failTask(task.id, NOW + 1.minutes)
        assertEquals(emptyList(), repository.dueTasks(NOW, 10))
        assertEquals(1, repository.dueTasks(NOW + 1.minutes, 10).single().attempts)
        repository.completeTask(task.id, NOW)
        assertEquals(emptyList(), repository.dueTasks(NOW + 1.minutes, 10))
    }

    @Test
    fun theBillingRoutesAnswerByRole() = serverTest(listOf(BillingModule), overrides) { server ->
        val client = server.client
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/orgs/$ORG_A/billing").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/orgs/$ORG_A/billing") { bearerAuth("admin-b") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/orgs/not-a-uuid/billing") { bearerAuth("admin-a") }.status)

        val overview = client.get("/v1/orgs/$ORG_A/billing") { bearerAuth("member-a") }.body<BillingOverviewResponse>()
        assertEquals("free", overview.effectivePlan)
        assertEquals(5, overview.limits.maxPeople)
        assertEquals(0, overview.usage.percentOfHours)
        assertTrue(overview.purchasable)
        assertEquals(4, overview.prices.size)

        val forbidden = client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("member-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("team", "month"))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        val bad = client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("gold", "month"))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val badInterval = client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("team", "week"))
        }
        assertEquals(HttpStatusCode.BadRequest, badInterval.status)
        val checkout = client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("team", "month"))
        }.body<RedirectResponse>()
        assertEquals("https://checkout.stripe.com/c/pay/cs_test_1", checkout.url)
        val noSubscription = client.post("/v1/orgs/$ORG_B/billing/portal") { bearerAuth("admin-b") }
        assertEquals(HttpStatusCode.Conflict, noSubscription.status)
        assertTrue(noSubscription.bodyAsText().contains("\"code\":\"conflict\""))
    }

    @Test
    fun aSignedWebhookActivatesThePlanAndTheConsoleShowsIt() = serverTest(listOf(BillingModule), overrides) { server ->
        val client = server.client
        client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("business", "month"))
        }
        stripe.subscription = stripeSubscription("ostrom_business_monthly")
        val payload = """{"id":"evt_checkout","type":"checkout.session.completed","created":${NOW.epochSeconds},
            "data":{"object":{"object":"checkout.session","customer":"cus_1","subscription":"sub_1","client_reference_id":"$ORG_A"}}}"""
        val signature = "t=${NOW.epochSeconds},v1=${hmacSha256Hex(WEBHOOK_SECRET, "${NOW.epochSeconds}.$payload".encodeToByteArray())}"

        val unsigned = client.post("/v1/billing/stripe/webhook") { setBody(payload) }
        assertEquals(HttpStatusCode.BadRequest, unsigned.status)
        val first = client.post("/v1/billing/stripe/webhook") { header("Stripe-Signature", signature); setBody(payload) }
        assertEquals(WebhookResponse(true, "processed"), first.body<WebhookResponse>())
        val replay = client.post("/v1/billing/stripe/webhook") { header("Stripe-Signature", signature); setBody(payload) }
        assertEquals(WebhookResponse(true, "duplicate"), replay.body<WebhookResponse>())
        val tooLarge = client.post("/v1/billing/stripe/webhook") { header("Stripe-Signature", signature); setBody("x".repeat(300_000)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)

        val overview = client.get("/v1/orgs/$ORG_A/billing") { bearerAuth("member-a") }.body<BillingOverviewResponse>()
        assertEquals("business", overview.effectivePlan)
        assertEquals("active", overview.status)
        assertEquals(true, overview.limits.sso)

        stripe.subscription = stripeSubscription("ostrom_team_monthly")
        val changed = client.post("/v1/orgs/$ORG_A/billing/plan") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("team", "month"))
        }.body<BillingOverviewResponse>()
        assertEquals("team", changed.plan)

        stripe.subscription = stripeSubscription("ostrom_team_monthly", cancelAtPeriodEnd = true)
        assertTrue(client.post("/v1/orgs/$ORG_A/billing/cancel") { bearerAuth("admin-a") }.body<BillingOverviewResponse>().cancelAtPeriodEnd)
        stripe.subscription = stripeSubscription("ostrom_team_monthly", cancelAtPeriodEnd = false)
        assertFalse(client.post("/v1/orgs/$ORG_A/billing/resume") { bearerAuth("admin-a") }.body<BillingOverviewResponse>().cancelAtPeriodEnd)

        assertEquals("https://billing.stripe.com/p/session/test_1", client.post("/v1/orgs/$ORG_A/billing/portal") { bearerAuth("admin-a") }.body<RedirectResponse>().url)
        stripe.invoices = listOf(Invoice("in_1", "A-1", "paid", 4_200, 700, "usd", NOW, "https://pay", null))
        val invoices = client.get("/v1/orgs/$ORG_A/billing/invoices?limit=5") { bearerAuth("admin-a") }.body<InvoicesResponse>()
        assertEquals("in_1", invoices.invoices.single().id)
        assertEquals(NOW.toString(), invoices.invoices.single().created)

        stripe.subscription = stripeSubscription("ostrom_team_monthly", status = SubscriptionStatus.PAST_DUE)
        val failedPayload = """{"id":"evt_failed","type":"invoice.payment_failed","created":${NOW.epochSeconds},
            "data":{"object":{"object":"invoice","customer":"cus_1","hosted_invoice_url":"https://invoice.stripe.com/i/9",
            "parent":{"subscription_details":{"subscription":"sub_1"}}}}}"""
        val failedSignature = "t=${NOW.epochSeconds},v1=${hmacSha256Hex(WEBHOOK_SECRET, "${NOW.epochSeconds}.$failedPayload".encodeToByteArray())}"
        client.post("/v1/billing/stripe/webhook") { header("Stripe-Signature", failedSignature); setBody(failedPayload) }
        val pastDue = client.get("/v1/orgs/$ORG_A/billing") { bearerAuth("member-a") }.body<BillingOverviewResponse>()
        assertEquals("payment_failed", pastDue.paymentIssue?.kind)
        assertEquals("https://invoice.stripe.com/i/9", pastDue.paymentIssue?.hostedInvoiceUrl)
        assertEquals("team", pastDue.effectivePlan)

        val other = client.get("/v1/orgs/$ORG_B/billing") { bearerAuth("admin-b") }.body<BillingOverviewResponse>()
        assertEquals("free", other.effectivePlan, "Organization B does not see A's subscription")
    }
}
