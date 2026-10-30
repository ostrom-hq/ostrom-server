// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.CheckoutRequest
import dev.ostrom.server.billing.domain.Proration
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.infrastructure.STRIPE_API_VERSION
import dev.ostrom.server.billing.infrastructure.STRIPE_JSON
import dev.ostrom.server.billing.infrastructure.StripeConfig
import dev.ostrom.server.billing.infrastructure.StripeGatewayImpl
import dev.ostrom.server.billing.infrastructure.array
import dev.ostrom.server.billing.infrastructure.obj
import dev.ostrom.server.billing.infrastructure.parseStripeEvent
import dev.ostrom.server.billing.infrastructure.string
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * End to end against the REAL Stripe test mode (not a mock): the gateway as the server runs it, natively, over TLS.
 * Needs `OSTROM_STRIPE_E2E_KEY` (a `sk_test_` key of the provisioned test account); CI sets it from the repository
 * secret and `OSTROM_STRIPE_E2E_REQUIRED=true`, so the test can never be skipped there. Test cards:
 * `pm_card_visa` (4242…), `pm_card_threeDSecure2Required` (3-D Secure), `pm_card_chargeCustomerFail` (declined).
 */
class StripeTestModeE2eTest {
    private val env = EnvironmentConfigSource()
    private val key = env["OSTROM_STRIPE_E2E_KEY"]
    private val client = HttpClient()

    private fun enabled(): Boolean {
        if (key != null) {
            check(key.startsWith("sk_test_") || key.startsWith("rk_test_")) { "The e2e test only runs on a test key." }
            return true
        }
        check(env["OSTROM_STRIPE_E2E_REQUIRED"] != "true") { "OSTROM_STRIPE_E2E_KEY is required in CI." }
        return false
    }

    private suspend fun raw(method: HttpMethod, path: String, vararg params: Pair<String, String>): JsonObject {
        val form = params.joinToString("&") { (k, v) -> "${k.encodeURLParameter()}=${v.encodeURLParameter()}" }
        val response = client.request(
            "https://api.stripe.com$path" + if (method == HttpMethod.Get && form.isNotEmpty()) "?$form" else ""
        ) {
            this.method = method
            basicAuth(checkNotNull(key), "")
            header("Stripe-Version", STRIPE_API_VERSION)
            if (method != HttpMethod.Get) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form)
            }
        }
        return STRIPE_JSON.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** A customer in France (Stripe Tax applies French VAT) with a test card as default. */
    private suspend fun customer(gateway: StripeGatewayImpl, card: String): String {
        val customerId = gateway.createCustomer(Uuid.random(), "Ostrom e2e", "e2e@ostrom.run")
        raw(
            HttpMethod.Post, "/v1/customers/$customerId",
            "address[line1]" to "1 rue de Rivoli", "address[postal_code]" to "75001",
            "address[city]" to "Paris", "address[country]" to "FR",
        )
        raw(HttpMethod.Post, "/v1/payment_methods/$card/attach", "customer" to customerId).string("id")
            ?.let {
                raw(HttpMethod.Post, "/v1/customers/$customerId", "invoice_settings[default_payment_method]" to it)
            }
        return customerId
    }

    private suspend fun subscribe(customerId: String, priceId: String, quantity: Int?): JsonObject {
        val params = mutableListOf(
            "customer" to customerId,
            "items[0][price]" to priceId,
            "automatic_tax[enabled]" to "true",
            "payment_behavior" to "allow_incomplete",
            "metadata[org_id]" to Uuid.random().toString(),
        )
        quantity?.let { params += "items[0][quantity]" to it.toString() }
        return raw(HttpMethod.Post, "/v1/subscriptions", *params.toTypedArray())
    }

    /**
     * Runs [block] against Stripe's test mode with a gateway, and deletes the customers it adds to the list (their
     * subscriptions end with them), even when it fails. Skipped without a key, outside CI.
     */
    private fun onStripe(block: suspend (StripeGatewayImpl, MutableList<String>) -> Unit): TestResult =
        runTest(timeout = 5.minutes) {
            if (!enabled()) return@runTest
            val gateway = StripeGatewayImpl(client, StripeConfig(checkNotNull(key)))
            val created = mutableListOf<String>()
            try {
                block(gateway, created)
            } finally {
                for (customerId in created) raw(HttpMethod.Delete, "/v1/customers/$customerId")
            }
        }

    @Test
    fun aYearlyPlanIsTaxedTrueUpUpgradedCanceledAndResumed() = onStripe { gateway, created ->
        // Prices exist by lookup key: the account was provisioned by ops/stripe/provision.py.
        val teamYearly = gateway.priceId("ostrom_team_yearly")
        val businessYearly = gateway.priceId("ostrom_business_yearly")

        // Card 4242: a yearly Team plan for 2 people is active, French VAT is computed by Stripe Tax.
        val paying = customer(gateway, "pm_card_visa").also { created += it }
        val subscriptionId = checkNotNull(subscribe(paying, teamYearly, 2).string("id"))
        val subscription = gateway.getSubscription(subscriptionId)
        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertEquals("ostrom_team_yearly", subscription.priceLookupKey)
        assertEquals(2, subscription.quantity)
        val itemId = checkNotNull(subscription.itemId)
        val first = gateway.listInvoices(paying, 10).single()
        assertEquals("paid", first.status)
        assertEquals(2 * 33_600L, first.total - first.tax, "Two people at 336 USD a year, before tax")
        assertEquals(2 * 33_600L * 20 / 100, first.tax, "French VAT at 20 %")

        // A third active person: the true-up is invoiced now, prorated.
        gateway.setQuantity(subscriptionId, itemId, 3)
        assertEquals(3, gateway.getSubscription(subscriptionId).quantity)
        // Upgrade to Business, invoiced now.
        gateway.changePrice(subscriptionId, itemId, businessYearly, 3, Proration.ALWAYS_INVOICE)
        assertEquals("ostrom_business_yearly", gateway.getSubscription(subscriptionId).priceLookupKey)
        assertTrue(gateway.listInvoices(paying, 10).size >= 3, "The true-up and the upgrade are invoiced")
        // Cancel at the period end, then reactivate.
        gateway.setCancelAtPeriodEnd(subscriptionId, true)
        assertTrue(gateway.getSubscription(subscriptionId).cancelAtPeriodEnd)
        gateway.setCancelAtPeriodEnd(subscriptionId, false)
        assertEquals(false, gateway.getSubscription(subscriptionId).cancelAtPeriodEnd)

        // Checkout and portal sessions are accepted with automatic tax and tax ids.
        val success = "https://app.ostrom.run/settings/billing?checkout=success"
        val back = "https://app.ostrom.run/settings/billing"
        val teamMonthly = gateway.priceId("ostrom_team_monthly")
        val request = CheckoutRequest(Uuid.random(), paying, teamMonthly, null, success, back)
        val checkout = gateway.createCheckoutSession(request)
        assertTrue(checkout.startsWith("https://checkout.stripe.com/"), checkout)
        assertTrue(gateway.createPortalSession(paying, back).startsWith("https://billing.stripe.com/"))
    }

    @Test
    fun aMonthlyPlanIsMeteredPerActivePerson() = onStripe { gateway, created ->
        val metered = customer(gateway, "pm_card_visa").also { created += it }
        val meteredSub = subscribe(metered, gateway.priceId("ostrom_team_monthly"), null)
        assertEquals("active", meteredSub.string("status"))
        val runId = Uuid.random().toString()
        gateway.recordActivePerson(metered, "e2e-$runId-alice", Clock.System.now())
        gateway.recordActivePerson(metered, "e2e-$runId-bob", Clock.System.now())
    }

    @Test
    fun threeDSecureAndADeclinedCardGrantNoPlan() = onStripe { gateway, created ->
        val started = Clock.System.now()
        val teamYearly = gateway.priceId("ostrom_team_yearly")
        // 3-D Secure: the subscription waits for the customer, which is not a paid plan.
        val secure = customer(gateway, "pm_card_threeDSecure2Required").also { created += it }
        val secureSub = gateway.getSubscription(checkNotNull(subscribe(secure, teamYearly, 1).string("id")))
        assertEquals(SubscriptionStatus.INCOMPLETE, secureSub.status)
        assertEquals(false, secureSub.status.grantsPlan)

        // Declined card: incomplete too, and Stripe emits invoice.payment_failed, which we parse as received.
        val declined = customer(gateway, "pm_card_chargeCustomerFail").also { created += it }
        val declinedSubId = checkNotNull(subscribe(declined, teamYearly, 1).string("id"))
        assertEquals(SubscriptionStatus.INCOMPLETE, gateway.getSubscription(declinedSubId).status)
        val failedEvent = waitForEvent("invoice.payment_failed", declined, started.epochSeconds)
        val parsed = parseStripeEvent(failedEvent.toString())
        assertEquals(declined, parsed.customerId)
        assertEquals(declinedSubId, parsed.subscriptionId)
        assertTrue(parsed.hostedInvoiceUrl.orEmpty().startsWith("https://invoice.stripe.com/"))
    }

    private suspend fun waitForEvent(type: String, customerId: String, since: Long): JsonObject {
        repeat(20) {
            val events = raw(
                HttpMethod.Get,
                "/v1/events",
                "type" to type,
                "created[gte]" to since.toString(),
                "limit" to "50",
            )
            events.array("data").map { it.jsonObject }.firstOrNull { event ->
                event.obj("data")?.obj("object")?.string("customer") == customerId
            }?.let { return it }
            // Real time, not the test scheduler's virtual time: Stripe needs a few seconds to emit the event.
            withContext(Dispatchers.Default) { delay(3.seconds) }
        }
        fail("No $type event for $customerId")
    }
}
