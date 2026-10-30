// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.InvalidWebhookSignatureException
import dev.ostrom.server.billing.fakes.FixedClock
import dev.ostrom.server.billing.fakes.NOW
import dev.ostrom.server.billing.infrastructure.StripeSignatureVerifier
import dev.ostrom.server.billing.infrastructure.constantTimeEquals
import dev.ostrom.server.billing.infrastructure.hmacSha256Hex
import dev.ostrom.server.billing.infrastructure.parseStripeEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StripeWebhooksTest {
    private val payload = """{"id":"evt_1","type":"invoice.paid"}"""
    private val t = NOW.epochSeconds

    private suspend fun header(secret: String = "whsec_test", at: Long = t, body: String = payload) =
        "t=$at,v1=${hmacSha256Hex(secret, "$at.$body".encodeToByteArray())},v0=ignored"

    @Test
    fun hmacMatchesTheRfc4231Vector() = runTest {
        // RFC 4231, test case 2: key "Jefe", data "what do ya want for nothing?".
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256Hex("Jefe", "what do ya want for nothing?".encodeToByteArray()),
        )
    }

    @Test
    fun aValidSignatureIsAccepted() = runTest {
        StripeSignatureVerifier(listOf("whsec_test"), FixedClock()).verify(payload, header())
    }

    @Test
    fun theSecondSecretOfARotationIsAccepted() = runTest {
        StripeSignatureVerifier(listOf("whsec_old", "whsec_new"), FixedClock()).verify(payload, header("whsec_new"))
    }

    @Test
    fun anyOfSeveralV1EntriesMayMatch() = runTest {
        StripeSignatureVerifier(
            listOf("whsec_test"),
            FixedClock(),
        ).verify(payload, "t=$t,v1=deadbeef," + header().substringAfter(','))
    }

    @Test
    fun tamperedOrForeignSignaturesAreRefused() = runTest {
        val verifier = StripeSignatureVerifier(listOf("whsec_test"), FixedClock())
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify("""{"id":"evt_2"}""", header()) }
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify(payload, header("whsec_other")) }
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify(payload, null) }
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify(payload, "v1=abc") }
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify(payload, "t=notanumber,v1=abc") }
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify(payload, "garbage,=x,t=$t") }
        assertFailsWith<InvalidWebhookSignatureException> {
            StripeSignatureVerifier(emptyList(), FixedClock()).verify(payload, header())
        }
    }

    @Test
    fun aReplayOlderOrNewerThanFiveMinutesIsRefused() = runTest {
        val clock = FixedClock()
        val verifier = StripeSignatureVerifier(listOf("whsec_test"), clock)
        val signed = header()
        clock.advance(5.minutes)
        verifier.verify(payload, signed)
        clock.advance(1.seconds)
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify(payload, signed) }
        clock.now = NOW - 6.minutes
        assertFailsWith<InvalidWebhookSignatureException> { verifier.verify(payload, signed) }
    }

    @Test
    fun constantTimeComparison() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "ab"))
    }

    @Test
    fun aCheckoutEventNamesItsCustomerSubscriptionAndOrganization() {
        val event = parseStripeEvent(
            """{"id":"evt_1","type":"checkout.session.completed","created":1800000000,"data":{"object":{
                "object":"checkout.session","customer":"cus_1","subscription":"sub_1",
                "client_reference_id":"org-1"}}}""",
        )
        assertEquals("evt_1", event.id)
        assertEquals("checkout.session.completed", event.type)
        assertEquals(1_800_000_000L, event.created.epochSeconds)
        assertEquals("cus_1", event.customerId)
        assertEquals("sub_1", event.subscriptionId)
        assertEquals("org-1", event.orgId)
        assertNull(event.hostedInvoiceUrl)
    }

    @Test
    fun aSubscriptionEventIsItsOwnSubscription() {
        val event = parseStripeEvent(
            """{"id":"e","type":"customer.subscription.updated","data":{"object":{"object":"subscription","id":"sub_9",
                "customer":"cus_9","metadata":{"org_id":"org-9"}}}}""",
        )
        assertEquals("sub_9", event.subscriptionId)
        assertEquals("org-9", event.orgId)
        assertEquals(0L, event.created.epochSeconds)
    }

    @Test
    fun anInvoiceNamesItsSubscriptionInEitherShape() {
        val current = parseStripeEvent(
            """{"id":"e","type":"invoice.payment_failed","data":{"object":{"object":"invoice","customer":"cus_1",
                "hosted_invoice_url":"https://invoice.stripe.com/i/1",
                "parent":{"subscription_details":{"subscription":"sub_1","metadata":{"org_id":"org-1"}}}}}}""",
        )
        assertEquals("sub_1", current.subscriptionId)
        assertEquals("org-1", current.orgId)
        assertEquals("https://invoice.stripe.com/i/1", current.hostedInvoiceUrl)
        val older = parseStripeEvent(
            """{"id":"e","type":"invoice.paid","data":{"object":{"object":"invoice",
                "customer":{"id":"cus_2"},"subscription":"sub_2"}}}""",
        )
        assertEquals("sub_2", older.subscriptionId)
        assertEquals("cus_2", older.customerId)
        assertNull(older.orgId)
        assertNull(older.hostedInvoiceUrl)
    }

    @Test
    fun aCustomerEventIsItsOwnCustomer() {
        val event = parseStripeEvent(
            """{"id":"e","type":"customer.updated","data":{"object":{"object":"customer","id":"cus_3",
                "subscription":null}}}""",
        )
        assertEquals("cus_3", event.customerId)
        assertNull(event.subscriptionId)
    }

    @Test
    fun aBodyThatIsNotAStripeEventIsRefused() {
        for (
            body in listOf(
                "not json",
                "[]",
                """{"type":"x","data":{"object":{}}}""",
                """{"id":"e","data":{"object":{}}}""",
                """{"id":"e","type":"x"}""",
                """{"id":"e","type":"x","data":{"object":1}}""",
            )
        ) {
            assertFailsWith<InvalidWebhookSignatureException>(body) { parseStripeEvent(body) }
        }
    }
}
