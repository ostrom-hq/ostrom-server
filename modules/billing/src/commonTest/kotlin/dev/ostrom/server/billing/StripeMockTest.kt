package dev.ostrom.server.billing

import dev.ostrom.server.billing.domain.CheckoutRequest
import dev.ostrom.server.billing.domain.Proration
import dev.ostrom.server.billing.infrastructure.StripeConfig
import dev.ostrom.server.billing.infrastructure.StripeGatewayImpl
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Integration against stripe-mock (Stripe's official mock, which validates every request against Stripe's OpenAPI):
 * each call of the gateway, over real HTTP with the native engine, is accepted. CI runs it with
 * `OSTROM_STRIPE_MOCK_URL` and `OSTROM_STRIPE_MOCK_REQUIRED=true`.
 */
class StripeMockTest {
    @Test
    fun everyGatewayCallIsAValidStripeRequest() = runTest {
        val env = EnvironmentConfigSource()
        val url = env["OSTROM_STRIPE_MOCK_URL"]
        if (url == null) {
            check(env["OSTROM_STRIPE_MOCK_REQUIRED"] != "true") { "OSTROM_STRIPE_MOCK_URL is required in CI." }
            return@runTest
        }
        val gateway = StripeGatewayImpl(HttpClient(), StripeConfig("sk_test_123", url))
        val customer = gateway.createCustomer(Uuid.random(), "Acme", "a@acme.dev")
        val price = gateway.priceId("ostrom_team_monthly")
        val checkout = gateway.createCheckoutSession(CheckoutRequest(Uuid.random(), customer, price, 2, "https://a/s", "https://a/c"))
        assertTrue(checkout.isNotEmpty())
        assertTrue(gateway.createPortalSession(customer, "https://a/b").isNotEmpty())
        val subscription = gateway.getSubscription("sub_123")
        gateway.changePrice(subscription.id, "si_123", price, 2, Proration.CREATE_PRORATIONS)
        gateway.setCancelAtPeriodEnd(subscription.id, true)
        gateway.setQuantity(subscription.id, "si_123", 3)
        gateway.recordActivePerson(customer, "mock-${Uuid.random()}", Clock.System.now())
        gateway.listInvoices(customer, 3)
    }
}
