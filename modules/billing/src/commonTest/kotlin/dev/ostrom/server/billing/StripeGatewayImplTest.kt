// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.BillingNotConfiguredException
import dev.ostrom.server.billing.domain.CheckoutRequest
import dev.ostrom.server.billing.domain.Proration
import dev.ostrom.server.billing.domain.SubscriptionStatus
import dev.ostrom.server.billing.infrastructure.STRIPE_API_VERSION
import dev.ostrom.server.billing.infrastructure.StripeApiException
import dev.ostrom.server.billing.infrastructure.StripeConfig
import dev.ostrom.server.billing.infrastructure.StripeGatewayImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.util.decodeBase64String
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A recorded request: method, path with query, form body, headers. */
private data class Sent(val method: String, val url: String, val body: String, val headers: Map<String, String>)

private class Harness(vararg answers: Pair<Int, String>) {
    val sent = mutableListOf<Sent>()
    private val queue = answers.toMutableList()
    val gateway = StripeGatewayImpl(
        HttpClient(MockEngine { request -> answer(request) }),
        StripeConfig("sk_test_123", "https://stripe.test/"),
        idempotencyKey = { "idem-1" },
    )

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
        sent += Sent(
            request.method.value,
            request.url.encodedPathAndQuery,
            body,
            request.headers.entries().associate { it.key to it.value.joinToString(",") },
        )
        val (status, json) = queue.removeAt(0)
        return respond(json, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

private val ORG = Uuid.parse("0195f7a2-0000-7000-8000-000000000001")

class StripeGatewayImplTest {
    @Test
    fun everyRequestIsAuthenticatedAndPinsTheApiVersionAndWritesAreIdempotent() = runTest {
        val harness = Harness(200 to """{"id":"cus_1"}""")
        assertEquals("cus_1", harness.gateway.createCustomer(ORG, "Acme & Co", "a@acme.dev"))
        val sent = harness.sent.single()
        assertEquals("POST", sent.method)
        assertEquals("/v1/customers", sent.url)
        assertEquals("name=Acme%20%26%20Co&email=a%40acme.dev&metadata%5Borg_id%5D=$ORG", sent.body)
        assertEquals(
            "sk_test_123:",
            sent.headers.getValue(HttpHeaders.Authorization).removePrefix("Basic ").decodeBase64String(),
        )
        assertEquals(STRIPE_API_VERSION, sent.headers.getValue("Stripe-Version"))
        assertEquals("2026-09-30.endive", STRIPE_API_VERSION)
        assertEquals("idem-1", sent.headers.getValue("Idempotency-Key"))
    }

    @Test
    fun aCustomerWithoutNameOrEmailSendsOnlyTheOrganization() = runTest {
        val harness = Harness(200 to """{"id":"cus_2"}""")
        harness.gateway.createCustomer(ORG, null, null)
        assertEquals("metadata%5Borg_id%5D=$ORG", harness.sent.single().body)
    }

    @Test
    fun pricesAreResolvedByLookupKeyOnceThenCached() = runTest {
        val harness = Harness(200 to """{"data":[{"id":"price_1"}]}""")
        assertEquals("price_1", harness.gateway.priceId("ostrom_team_monthly"))
        assertEquals("price_1", harness.gateway.priceId("ostrom_team_monthly"))
        assertEquals("/v1/prices?lookup_keys%5B0%5D=ostrom_team_monthly&active=true", harness.sent.single().url)
        assertFalse("Idempotency-Key" in harness.sent.single().headers, "Reads carry no idempotency key")
    }

    @Test
    fun aMissingPriceMeansTheAccountWasNotProvisioned() = runTest {
        val harness = Harness(200 to """{"data":[]}""")
        assertFailsWith<BillingNotConfiguredException> { harness.gateway.priceId("ostrom_team_monthly") }
    }

    @Test
    fun checkoutHasAutomaticTaxTaxIdsAddressAndTheOrganization() = runTest {
        val harness = Harness(
            200 to """{"url":"https://checkout.stripe.com/x"}""",
            200 to """{"url":"https://checkout.stripe.com/y"}""",
        )
        val request = CheckoutRequest(ORG, "cus_1", "price_1", null, "https://app/s", "https://app/c")
        assertEquals("https://checkout.stripe.com/x", harness.gateway.createCheckoutSession(request))
        val form = harness.sent[0].body.split('&').toSet()
        for (expected in listOf(
            "mode=subscription", "customer=cus_1", "client_reference_id=$ORG", "line_items%5B0%5D%5Bprice%5D=price_1",
            "automatic_tax%5Benabled%5D=true", "tax_id_collection%5Benabled%5D=true",
            "billing_address_collection=required",
            "customer_update%5Baddress%5D=auto", "customer_update%5Bname%5D=auto", "allow_promotion_codes=true",
            "metadata%5Borg_id%5D=$ORG", "subscription_data%5Bmetadata%5D%5Borg_id%5D=$ORG",
            "success_url=https%3A%2F%2Fapp%2Fs", "cancel_url=https%3A%2F%2Fapp%2Fc",
        )) {
            assertTrue(expected in form, expected)
        }
        assertTrue(form.none { it.startsWith("line_items%5B0%5D%5Bquantity%5D") }, "A metered price has no quantity")
        harness.gateway.createCheckoutSession(request.copy(quantity = 4))
        assertTrue("line_items%5B0%5D%5Bquantity%5D=4" in harness.sent[1].body.split('&'))
    }

    @Test
    fun portalSession() = runTest {
        val harness = Harness(200 to """{"url":"https://billing.stripe.com/p"}""")
        assertEquals("https://billing.stripe.com/p", harness.gateway.createPortalSession("cus_1", "https://app/b"))
        assertEquals("customer=cus_1&return_url=https%3A%2F%2Fapp%2Fb", harness.sent.single().body)
    }

    @Test
    fun aSubscriptionIsReadWithItsPeriodOnTheItem() = runTest {
        val harness = Harness(
            200 to """{"id":"sub_1","customer":"cus_1","status":"past_due","cancel_at_period_end":false,
                "cancel_at":null,"metadata":{"org_id":"$ORG"},
                "items":{"data":[{"id":"si_1","quantity":3,
                "current_period_start":1760486400,"current_period_end":1763164800,
                "price":{"id":"price_1","lookup_key":"ostrom_team_yearly"}}]}}""",
        )
        val subscription = harness.gateway.getSubscription("sub_1")
        assertEquals("/v1/subscriptions/sub_1?expand%5B0%5D=items.data.price", harness.sent.single().url)
        assertEquals(SubscriptionStatus.PAST_DUE, subscription.status)
        assertEquals("si_1", subscription.itemId)
        assertEquals("ostrom_team_yearly", subscription.priceLookupKey)
        assertEquals(3, subscription.quantity)
        assertEquals(Instant.fromEpochSeconds(1760486400), subscription.currentPeriodStart)
        assertEquals(Instant.fromEpochSeconds(1763164800), subscription.currentPeriodEnd)
        assertFalse(subscription.cancelAtPeriodEnd)
        assertEquals(ORG.toString(), subscription.orgId)
    }

    @Test
    fun anOlderSubscriptionShapeIsReadToo() = runTest {
        val harness = Harness(
            200 to """{"id":"sub_1","customer":{"id":"cus_1"},
                "current_period_start":10,"current_period_end":20,"cancel_at":20}""",
        )
        val subscription = harness.gateway.getSubscription("sub_1")
        assertEquals("cus_1", subscription.customerId)
        assertEquals(SubscriptionStatus.UNKNOWN, subscription.status)
        assertNull(subscription.itemId)
        assertNull(subscription.priceLookupKey)
        assertNull(subscription.orgId)
        assertEquals(Instant.fromEpochSeconds(20), subscription.currentPeriodEnd)
        assertTrue(subscription.cancelAtPeriodEnd, "cancel_at at the period end means ending at the period end")
    }

    @Test
    fun aCancelAtAnotherDateIsNotAPeriodEndCancellation() = runTest {
        val harness = Harness(
            200 to """{"id":"sub_1","customer":"cus_1","status":"active","current_period_end":20,"cancel_at":30}"""
        )
        assertFalse(harness.gateway.getSubscription("sub_1").cancelAtPeriodEnd)
    }

    @Test
    fun subscriptionChanges() = runTest {
        val ok = 200 to """{"id":"sub_1"}"""
        val harness = Harness(ok, ok, ok, ok, ok)
        harness.gateway.changePrice("sub_1", "si_1", "price_2", null, Proration.ALWAYS_INVOICE)
        harness.gateway.changePrice("sub_1", "si_1", "price_3", 5, Proration.CREATE_PRORATIONS)
        harness.gateway.setCancelAtPeriodEnd("sub_1", true)
        harness.gateway.setQuantity("sub_1", "si_1", 7)
        harness.gateway.recordActivePerson("cus_1", "ostrom-x", Instant.fromEpochSeconds(1_800_000_000))
        assertEquals(
            listOf(
                "items%5B0%5D%5Bid%5D=si_1&items%5B0%5D%5Bprice%5D=price_2&proration_behavior=always_invoice" +
                    "&payment_behavior=pending_if_incomplete",
                "items%5B0%5D%5Bid%5D=si_1&items%5B0%5D%5Bprice%5D=price_3&proration_behavior=create_prorations" +
                    "&payment_behavior=pending_if_incomplete&items%5B0%5D%5Bquantity%5D=5",
                "cancel_at_period_end=true",
                "items%5B0%5D%5Bid%5D=si_1&items%5B0%5D%5Bquantity%5D=7&proration_behavior=always_invoice",
                "event_name=ostrom_active_person&identifier=ostrom-x&timestamp=1800000000" +
                    "&payload%5Bstripe_customer_id%5D=cus_1&payload%5Bvalue%5D=1",
            ),
            harness.sent.map { it.body },
        )
        assertEquals("/v1/billing/meter_events", harness.sent.last().url)
    }

    @Test
    fun invoicesReadTheirTaxFromEitherShape() = runTest {
        val harness = Harness(
            200 to """{"data":[
                {"id":"in_1","number":"A-1","status":"paid","total":4200,"tax":700,"currency":"usd","created":100,
                 "hosted_invoice_url":"https://pay/1","invoice_pdf":"https://pdf/1"},
                {"id":"in_2","total_taxes":[{"amount":300},{"amount":null}]}]}""",
        )
        val invoices = harness.gateway.listInvoices("cus_1", 2)
        assertEquals("/v1/invoices?customer=cus_1&limit=2", harness.sent.single().url)
        assertEquals(700L, invoices[0].tax)
        assertEquals("https://pay/1", invoices[0].hostedInvoiceUrl)
        assertEquals(300L, invoices[1].tax)
        assertEquals("unknown", invoices[1].status)
        assertEquals(0L, invoices[1].total)
        assertEquals("usd", invoices[1].currency)
        assertNull(invoices[1].number)
    }

    @Test
    fun aRefusalCarriesStripesCodeAndMessage() = runTest {
        val harness = Harness(402 to """{"error":{"code":"card_declined","message":"Your card was declined."}}""")
        val error = assertFailsWith<StripeApiException> { harness.gateway.setCancelAtPeriodEnd("sub_1", false) }
        assertEquals(402, error.status)
        assertEquals("card_declined", error.code)
        assertEquals("Your card was declined.", error.message)
    }

    @Test
    fun aRefusalWithoutJsonStillFails() = runTest {
        val harness = Harness(502 to "<html>bad gateway</html>", 500 to """{"error":"flat"}""")
        assertEquals(
            "Stripe answered 502.",
            assertFailsWith<StripeApiException> { harness.gateway.setCancelAtPeriodEnd("s", true) }.message,
        )
        assertNull(assertFailsWith<StripeApiException> { harness.gateway.setCancelAtPeriodEnd("s", true) }.code)
    }

    @Test
    fun aSuccessThatIsNotJsonOrMissesAFieldFails() = runTest {
        val harness = Harness(200 to "ok", 200 to "[1]", 200 to """{"object":"customer"}""")
        assertEquals(
            "Stripe answered a body that is not JSON.",
            assertFailsWith<StripeApiException> { harness.gateway.createCustomer(ORG, null, null) }.message,
        )
        assertFailsWith<StripeApiException> { harness.gateway.createCustomer(ORG, null, null) }
        assertEquals(
            "Stripe's answer has no id.",
            assertFailsWith<StripeApiException> { harness.gateway.createCustomer(ORG, null, null) }.message,
        )
    }

    @Test
    fun aSubscriptionWithoutIdOrCustomerIsADefect() = runTest {
        val harness = Harness(200 to """{"customer":"cus_1"}""", 200 to """{"id":"sub_1","customer":null}""")
        assertFailsWith<IllegalArgumentException> { harness.gateway.getSubscription("x") }
        assertFailsWith<IllegalArgumentException> { harness.gateway.getSubscription("x") }
    }

    @Test
    fun anInvoiceWithoutIdIsADefect() = runTest {
        val harness = Harness(200 to """{"data":[{"total":1}]}""")
        assertFailsWith<IllegalArgumentException> { harness.gateway.listInvoices("cus_1", 1) }
    }

    @Test
    fun theDefaultIdempotencyKeyIsRandom() = runTest {
        val keys = mutableListOf<String>()
        val gateway = StripeGatewayImpl(
            HttpClient(MockEngine { request ->
                keys += request.headers["Idempotency-Key"].orEmpty()
                respond("""{"id":"x"}""", HttpStatusCode.OK)
            }),
            StripeConfig("sk_test_123"),
        )
        gateway.createCustomer(ORG, null, null)
        gateway.createCustomer(ORG, null, null)
        assertEquals(2, keys.toSet().size)
        assertTrue(keys.all { Uuid.parse(it).toString() == it })
    }
}
