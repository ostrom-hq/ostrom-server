// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.Invoice
import run.ostrom.server.billing.domain.StripeGateway
import run.ostrom.server.billing.fakes.FakeOrgResolver
import run.ostrom.server.billing.fakes.FakeStripeGateway
import run.ostrom.server.billing.fakes.FixedClock
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.fakes.stripeSubscription
import run.ostrom.server.billing.infrastructure.hmacSha256Hex
import run.ostrom.server.billing.presentation.BillingOverviewResponse
import run.ostrom.server.billing.presentation.PlanRequest
import run.ostrom.server.billing.presentation.WebhookResponse
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Organization B's owner, signed in to the console, can neither read nor change organization A's billing through A's
 * path: every route answers 404, nothing reaches Stripe, and A's subscription is unchanged.
 */
class BillingIsolationPostgresTest {
    private val orgA = Uuid.parse("0195f7a2-0000-7000-8000-00000000000a")
    private val orgB = Uuid.parse("0195f7a2-0000-7000-8000-00000000000b")
    private val webhookSecret = "whsec_isolation_test"
    private val stripe = FakeStripeGateway()
    private val clock = FixedClock()

    // Console sessions `admin-a` (billing admin of A) and `admin-b` (owner of B): the subject id is the token.
    private val overrides = module {
        single<ConfigSource> {
            MapConfigSource(
                mapOf("OSTROM_STRIPE_SECRET_KEY" to "sk_test_x", "OSTROM_STRIPE_WEBHOOK_SECRET" to webhookSecret),
            )
        }
        single<Clock> { clock }
        single<StripeGateway> { stripe }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
                    AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"))
                        .takeIf { token == "admin-a" || token == "admin-b" }
            }
        }
        single<PrincipalOrgResolver> {
            FakeOrgResolver(mapOf(("admin-a" to orgA) to OrgRole.BILLING, ("admin-b" to orgB) to OrgRole.OWNER))
        }
    }

    // Organization A's checkout, completed, signed with the test secret.
    private suspend fun HttpClient.checkoutCompleted(): HttpResponse {
        val payload = """{"id":"evt_checkout","type":"checkout.session.completed",
            "created":${NOW.epochSeconds},"data":{"object":{"object":"checkout.session","customer":"cus_1",
            "subscription":"sub_1","client_reference_id":"$orgA"}}}"""
        val signed = "${NOW.epochSeconds}.$payload".encodeToByteArray()
        return post("/webhooks/stripe") {
            header("Stripe-Signature", "t=${NOW.epochSeconds},v1=${hmacSha256Hex(webhookSecret, signed)}")
            setBody(payload)
        }
    }

    private suspend fun HttpClient.overviewOfA(): BillingOverviewResponse =
        get("/api/v1/orgs/$orgA/billing") { bearerAuth("admin-a") }.body()

    @Test
    fun anotherOrganizationsOwnerNeitherReadsNorChangesTheBilling() =
        serverTest(listOf(BillingModule), overrides) { server ->
            val client = server.client
            val billingOfA = "/api/v1/orgs/$orgA/billing"
            client.post("$billingOfA/checkout") {
                bearerAuth("admin-a"); contentType(ContentType.Application.Json); setBody(PlanRequest("team", "month"))
            }
            stripe.subscription = stripeSubscription("ostrom_team_monthly")
            assertEquals(WebhookResponse(true, "processed"), client.checkoutCompleted().body<WebhookResponse>())
            stripe.invoices = listOf(Invoice("in_1", "A-1", "paid", 4_200, 700, "usd", NOW, "https://pay", null))
            val before = client.overviewOfA()
            assertEquals("team", before.effectivePlan)
            val stripeCallsBefore = stripe.calls.toList()

            for (path in listOf("checkout", "plan")) {
                val refused = client.post("$billingOfA/$path") {
                    bearerAuth("admin-b"); contentType(ContentType.Application.Json)
                    setBody(PlanRequest("business", "year"))
                }
                assertEquals(HttpStatusCode.NotFound, refused.status, path)
            }
            for (path in listOf("portal", "cancel", "resume")) {
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.post("$billingOfA/$path") { bearerAuth("admin-b") }.status,
                    path,
                )
            }
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("$billingOfA/invoices?limit=5") { bearerAuth("admin-b") }.status,
                "B reads none of A's invoices",
            )

            assertEquals(stripeCallsBefore, stripe.calls, "Nothing B asked reached Stripe")
            assertEquals(before, client.overviewOfA(), "A's subscription is unchanged")
            val ownOfB = client.get("/api/v1/orgs/$orgB/billing") { bearerAuth("admin-b") }
                .body<BillingOverviewResponse>()
            assertEquals("free", ownOfB.effectivePlan, "B's own organization got nothing of A's")
        }
}
