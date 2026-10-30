// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

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
import dev.ostrom.server.billing.fakes.FakeOrgResolver
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
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val ORG_A = Uuid.parse("0195f7a2-0000-7000-8000-00000000000a")
private val ORG_B = Uuid.parse("0195f7a2-0000-7000-8000-00000000000b")
private const val WEBHOOK_SECRET = "whsec_postgres_test"
private val TOKENS = setOf("admin-a", "member-a", "admin-b")

/** A person's read-only token and a device token, both of organization A's billing admin. */
private val SCOPED_TOKENS = mapOf(
    "reader-a" to AuthenticatedPrincipal("admin-a", PrincipalKind.USER, setOf("billing:read")),
    "device-a" to AuthenticatedPrincipal("admin-a", PrincipalKind.DEVICE, setOf("user:*")),
)

/** A Stripe-Signature header for [payload], signed now with the test secret. */
private suspend fun sign(payload: String): String {
    val signed = "${NOW.epochSeconds}.$payload".encodeToByteArray()
    return "t=${NOW.epochSeconds},v1=${hmacSha256Hex(WEBHOOK_SECRET, signed)}"
}

/** The checkout of organization A, completed. */
private fun checkoutCompleted() = """{"id":"evt_checkout","type":"checkout.session.completed",
    "created":${NOW.epochSeconds},"data":{"object":{"object":"checkout.session","customer":"cus_1",
    "subscription":"sub_1","client_reference_id":"$ORG_A"}}}"""

/** Posts [payload] to the webhook, signed with the test secret. */
private suspend fun HttpClient.webhook(payload: String): HttpResponse = webhook(payload, sign(payload))

/** Posts [payload] to the webhook with [signature], or without the header when it is null. */
private suspend fun HttpClient.webhook(payload: String, signature: String?): HttpResponse =
    post("/v1/billing/stripe/webhook") {
        signature?.let { header("Stripe-Signature", it) }
        setBody(payload)
    }

/** The billing overview of [org] read with [token]. */
private suspend fun HttpClient.overview(org: Uuid, token: String): BillingOverviewResponse =
    get("/v1/orgs/$org/billing") { bearerAuth(token) }.body()

/** Posts to [path] as organization A's billing admin. */
private suspend inline fun <reified T> HttpClient.postAs(path: String): T = post(path) { bearerAuth("admin-a") }.body()

/** Console sessions `admin-a`, `member-a`, `admin-b` (the subject id is the token), and [SCOPED_TOKENS]. */
private class TokensAuthenticator : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
        SCOPED_TOKENS[token]
            ?: AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*")).takeIf { token in TOKENS }
}

class BillingPostgresTest {
    private val stripe = FakeStripeGateway()
    private val clock = FixedClock()
    private val overrides = module {
        single<ConfigSource> {
            MapConfigSource(
                mapOf("OSTROM_STRIPE_SECRET_KEY" to "sk_test_x", "OSTROM_STRIPE_WEBHOOK_SECRET" to WEBHOOK_SECRET)
            )
        }
        single<Clock> { clock }
        single<StripeGateway> { stripe }
        single<TokenAuthenticator> { TokensAuthenticator() }
        single<PrincipalOrgResolver> {
            FakeOrgResolver(
                mapOf(
                    ("member-a" to ORG_A) to OrgRole.MEMBER,
                    ("admin-a" to ORG_A) to OrgRole.BILLING,
                    ("admin-b" to ORG_B) to OrgRole.OWNER,
                ),
            )
        }
    }

    @Test
    fun rowLevelSecurityHidesOtherOrganizationsEvenWithoutAWhereClause() =
        serverTest(listOf(BillingModule), overrides) { server ->
            val repository = server.koin.get<BillingRepository>()
            repository.save(OrgBilling(orgId = ORG_A, stripeCustomerId = "cus_a", updatedAt = NOW))
            repository.recordActivePerson(ORG_A, NOW, "alice", NOW)
            assertEquals(ORG_A, repository.findByCustomer("cus_a")?.orgId, "The webhook finds the org by customer")
            // The CI database user is a superuser, which bypasses row level security: the proof runs as a plain role.
            server.driver.execute(
                "DO \$\$ BEGIN CREATE ROLE ostrom_billing_rls_probe NOLOGIN; " +
                    "EXCEPTION WHEN duplicate_object THEN NULL; END \$\$",
            ).getOrThrow()
            server.driver.execute(
                "GRANT SELECT, INSERT ON billing_accounts, billing_active_people, billing_usage, billing_alerts " +
                    "TO ostrom_billing_rls_probe",
            ).getOrThrow()
            val counts = server.driver.transaction {
                execute("SET LOCAL ROLE ostrom_billing_rls_probe").getOrThrow()
                val seen = mutableListOf<String>()
                for (org in listOf(null, ORG_B, ORG_A)) {
                    org?.let {
                        fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", "$it"))
                            .getOrThrow()
                    }
                    val accounts = fetchAll("SELECT COUNT(*) AS n FROM billing_accounts").getOrThrow()
                    val people = fetchAll("SELECT COUNT(*) AS n FROM billing_active_people").getOrThrow()
                    seen += "${accounts.first().get("n").asString()}/${people.first().get("n").asString()}"
                }
                seen
            }
            assertEquals(listOf("0/0", "0/0", "1/1"), counts, "No org set: nothing; org B: nothing; org A: its rows")
            val forged = server.driver.transaction {
                execute("SET LOCAL ROLE ostrom_billing_rls_probe").getOrThrow()
                fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", "$ORG_B"))
                    .getOrThrow()
                execute(
                    Statement.create(
                        "INSERT INTO billing_alerts (org_id, period_start, percent, raised_at) " +
                            "SELECT org_id, NOW(), 80, NOW() FROM billing_accounts LIMIT 1",
                    ),
                )
            }
            assertEquals(0L, forged.getOrThrow(), "Org B can neither see nor write org A's rows")
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
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/orgs/not-a-uuid/billing") { bearerAuth("admin-a") }.status,
        )

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
    fun onlyAPersonsTokenWithTheBillingScopeGetsIn() = serverTest(listOf(BillingModule), overrides) { server ->
        val client = server.client
        assertEquals(HttpStatusCode.OK, client.get("/v1/orgs/$ORG_A/billing") { bearerAuth("reader-a") }.status)
        val readOnly = client.post("/v1/orgs/$ORG_A/billing/portal") { bearerAuth("reader-a") }
        assertEquals(HttpStatusCode.Forbidden, readOnly.status)
        assertTrue(readOnly.bodyAsText().contains("billing:manage"))
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/orgs/$ORG_A/billing") { bearerAuth("device-a") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/orgs/$ORG_A/billing") { bearerAuth("nobody") }.status)
    }

    @Test
    fun aSignedWebhookActivatesThePlanAndTheConsoleShowsIt() = serverTest(listOf(BillingModule), overrides) { server ->
        val client = server.client
        client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("business", "month"))
        }
        stripe.subscription = stripeSubscription("ostrom_business_monthly")
        val payload = checkoutCompleted()

        assertEquals(HttpStatusCode.BadRequest, client.webhook(payload, signature = null).status)
        assertEquals(WebhookResponse(true, "processed"), client.webhook(payload).body<WebhookResponse>())
        assertEquals(WebhookResponse(true, "duplicate"), client.webhook(payload).body<WebhookResponse>())
        val signature = sign(payload)
        val tooLarge = client.post("/v1/billing/stripe/webhook") {
            header("Stripe-Signature", signature)
            setBody("x".repeat(300_000))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)

        val overview = client.overview(ORG_A, "member-a")
        assertEquals("business", overview.effectivePlan)
        assertEquals("active", overview.status)
        assertEquals(true, overview.limits.sso)
        assertEquals("free", client.overview(ORG_B, "admin-b").effectivePlan, "B does not see A's subscription")
    }

    @Test
    fun theOwnerChangesCancelsResumesAndReadsInvoices() = serverTest(listOf(BillingModule), overrides) { server ->
        val client = server.client
        client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("business", "month"))
        }
        stripe.subscription = stripeSubscription("ostrom_business_monthly")
        client.webhook(checkoutCompleted())

        stripe.subscription = stripeSubscription("ostrom_team_monthly")
        val changed = client.post("/v1/orgs/$ORG_A/billing/plan") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("team", "month"))
        }.body<BillingOverviewResponse>()
        assertEquals("team", changed.plan)
        stripe.subscription = stripeSubscription("ostrom_team_monthly", cancelAtPeriodEnd = true)
        assertTrue(client.postAs<BillingOverviewResponse>("/v1/orgs/$ORG_A/billing/cancel").cancelAtPeriodEnd)
        stripe.subscription = stripeSubscription("ostrom_team_monthly", cancelAtPeriodEnd = false)
        assertFalse(client.postAs<BillingOverviewResponse>("/v1/orgs/$ORG_A/billing/resume").cancelAtPeriodEnd)
        assertEquals(
            "https://billing.stripe.com/p/session/test_1",
            client.postAs<RedirectResponse>("/v1/orgs/$ORG_A/billing/portal").url,
        )
        stripe.invoices = listOf(Invoice("in_1", "A-1", "paid", 4_200, 700, "usd", NOW, "https://pay", null))
        val invoices = client.get("/v1/orgs/$ORG_A/billing/invoices?limit=5") { bearerAuth("admin-a") }
            .body<InvoicesResponse>()
        assertEquals("in_1", invoices.invoices.single().id)
        assertEquals(NOW.toString(), invoices.invoices.single().created)
    }

    @Test
    fun aFailedPaymentKeepsThePlanAndShowsTheInvoice() = serverTest(listOf(BillingModule), overrides) { server ->
        val client = server.client
        client.post("/v1/orgs/$ORG_A/billing/checkout") {
            bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("team", "month"))
        }
        stripe.subscription = stripeSubscription("ostrom_team_monthly")
        client.webhook(checkoutCompleted())

        stripe.subscription = stripeSubscription("ostrom_team_monthly", status = SubscriptionStatus.PAST_DUE)
        client.webhook(
            """{"id":"evt_failed","type":"invoice.payment_failed","created":${NOW.epochSeconds},
            "data":{"object":{"object":"invoice","customer":"cus_1",
            "hosted_invoice_url":"https://invoice.stripe.com/i/9",
            "parent":{"subscription_details":{"subscription":"sub_1"}}}}}""",
        )
        val pastDue = client.overview(ORG_A, "member-a")
        assertEquals("payment_failed", pastDue.paymentIssue?.kind)
        assertEquals("https://invoice.stripe.com/i/9", pastDue.paymentIssue?.hostedInvoiceUrl)
        assertEquals("team", pastDue.effectivePlan)
    }
}
