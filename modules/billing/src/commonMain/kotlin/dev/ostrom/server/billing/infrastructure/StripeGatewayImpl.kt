// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.billing.domain.BillingNotConfiguredException
import dev.ostrom.server.billing.domain.CheckoutRequest
import dev.ostrom.server.billing.domain.Invoice
import dev.ostrom.server.billing.domain.Proration
import dev.ostrom.server.billing.domain.StripeGateway
import dev.ostrom.server.billing.domain.StripeSubscription
import dev.ostrom.server.billing.domain.SubscriptionStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The Stripe API version every request pins; the same as `ops/stripe/catalog.json` and the webhook endpoint. */
const val STRIPE_API_VERSION = "2026-09-30.endive"

/**
 * Stripe refused a request. The message is Stripe's, which never contains a secret; the body is not kept.
 *
 * @property status The HTTP status.
 * @property code Stripe's error code, when given.
 */
class StripeApiException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * Where Stripe is and how to call it.
 *
 * @property secretKey The secret (or restricted) key; test or live, nothing else changes.
 * @property baseUrl `https://api.stripe.com`, or stripe-mock in tests.
 * @property meterEventName The meter of active people (`ops/stripe/catalog.json`).
 */
data class StripeConfig(
    val secretKey: String,
    val baseUrl: String = "https://api.stripe.com",
    val meterEventName: String = "ostrom_active_person",
)

/**
 * [StripeGateway] over Stripe's form-encoded HTTP API. Writes carry an idempotency key, so a retried request after a
 * timeout never creates a second customer or session.
 *
 * @param client The HTTP client (Curl on Linux, Darwin on macOS; MockEngine in unit tests).
 * @param config The key and base URL.
 * @param idempotencyKey Generates the key of each write (random; fixed in tests).
 */
@Suppress("TooManyFunctions") // implements StripeGateway, one function per Stripe call
class StripeGatewayImpl(
    private val client: HttpClient,
    private val config: StripeConfig,
    private val idempotencyKey: () -> String = { Uuid.random().toString() },
) : StripeGateway {
    private val priceIds = mutableMapOf<String, String>()
    private val priceLock = Mutex()

    override suspend fun createCustomer(orgId: Uuid, name: String?, email: String?): String = call(
        HttpMethod.Post,
        "/v1/customers",
        listOfNotNull(name?.let { "name" to it }, email?.let { "email" to it }, "metadata[org_id]" to orgId.toString()),
    ).requireString("id")

    override suspend fun priceId(lookupKey: String): String = priceLock.withLock {
        priceIds[lookupKey] ?: run {
            val prices = call(HttpMethod.Get, "/v1/prices", listOf("lookup_keys[0]" to lookupKey, "active" to "true"))
            val id = prices.array("data").firstOrNull()?.jsonObject?.string("id")
                ?: throw BillingNotConfiguredException()
            priceIds[lookupKey] = id
            id
        }
    }

    override suspend fun createCheckoutSession(request: CheckoutRequest): String {
        val params = mutableListOf(
            "mode" to "subscription",
            "customer" to request.customerId,
            "client_reference_id" to request.orgId.toString(),
            "line_items[0][price]" to request.priceId,
            "automatic_tax[enabled]" to "true",
            "tax_id_collection[enabled]" to "true",
            "billing_address_collection" to "required",
            "customer_update[address]" to "auto",
            "customer_update[name]" to "auto",
            "allow_promotion_codes" to "true",
            "metadata[org_id]" to request.orgId.toString(),
            "subscription_data[metadata][org_id]" to request.orgId.toString(),
            "success_url" to request.successUrl,
            "cancel_url" to request.cancelUrl,
        )
        request.quantity?.let { params += "line_items[0][quantity]" to it.toString() }
        return call(HttpMethod.Post, "/v1/checkout/sessions", params).requireString("url")
    }

    override suspend fun createPortalSession(customerId: String, returnUrl: String): String = call(
        HttpMethod.Post,
        "/v1/billing_portal/sessions",
        listOf("customer" to customerId, "return_url" to returnUrl),
    ).requireString("url")

    override suspend fun getSubscription(subscriptionId: String): StripeSubscription =
        call(
            HttpMethod.Get,
            "/v1/subscriptions/$subscriptionId",
            listOf("expand[0]" to "items.data.price"),
        ).toSubscription()

    override suspend fun changePrice(
        subscriptionId: String,
        itemId: String,
        priceId: String,
        quantity: Int?,
        proration: Proration,
    ) {
        val params = mutableListOf(
            "items[0][id]" to itemId,
            "items[0][price]" to priceId,
            "proration_behavior" to proration.stripe,
            "payment_behavior" to "pending_if_incomplete",
        )
        quantity?.let { params += "items[0][quantity]" to it.toString() }
        call(HttpMethod.Post, "/v1/subscriptions/$subscriptionId", params)
    }

    override suspend fun setCancelAtPeriodEnd(subscriptionId: String, cancel: Boolean) {
        call(HttpMethod.Post, "/v1/subscriptions/$subscriptionId", listOf("cancel_at_period_end" to cancel.toString()))
    }

    override suspend fun setQuantity(subscriptionId: String, itemId: String, quantity: Int) {
        call(
            HttpMethod.Post,
            "/v1/subscriptions/$subscriptionId",
            listOf(
                "items[0][id]" to itemId,
                "items[0][quantity]" to quantity.toString(),
                "proration_behavior" to Proration.ALWAYS_INVOICE.stripe,
            ),
        )
    }

    override suspend fun recordActivePerson(customerId: String, identifier: String, at: Instant) {
        call(
            HttpMethod.Post,
            "/v1/billing/meter_events",
            listOf(
                "event_name" to config.meterEventName,
                "identifier" to identifier,
                "timestamp" to at.epochSeconds.toString(),
                "payload[stripe_customer_id]" to customerId,
                "payload[value]" to "1",
            ),
        )
    }

    override suspend fun listInvoices(customerId: String, limit: Int): List<Invoice> = call(
        HttpMethod.Get,
        "/v1/invoices",
        listOf("customer" to customerId, "limit" to limit.toString()),
    ).array("data").map { it.jsonObject.toInvoice() }

    private suspend fun call(method: HttpMethod, path: String, params: List<Pair<String, String>>): JsonObject {
        val form = params.joinToString("&") { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        val query = if (method == HttpMethod.Get && form.isNotEmpty()) "?$form" else ""
        val url = config.baseUrl.trimEnd('/') + path + query
        val response = client.request(url) {
            this.method = method
            basicAuth(config.secretKey, "")
            header("Stripe-Version", STRIPE_API_VERSION)
            if (method == HttpMethod.Post) {
                header("Idempotency-Key", idempotencyKey())
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form)
            }
        }
        val body = parse(response.bodyAsText())
        if (!response.status.isSuccess()) {
            val error = body?.get("error") as? JsonObject
            throw StripeApiException(
                response.status.value,
                error?.string("code"),
                error?.string("message") ?: "Stripe answered ${response.status.value}.",
            )
        }
        return body ?: throw StripeApiException(response.status.value, null, "Stripe answered a body that is not JSON.")
    }

    private fun parse(text: String): JsonObject? = try {
        STRIPE_JSON.parseToJsonElement(text) as? JsonObject
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    }
}

/** The JSON reader of Stripe bodies. */
val STRIPE_JSON = Json { ignoreUnknownKeys = true }

/** A string field, null when absent or JSON null. */
fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** A number field. */
fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

/** A boolean field. */
fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

/** A nested object, null when absent or not an object. */
fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

/** An array field, empty when absent. */
fun JsonObject.array(key: String): JsonArray = (get(key) as? JsonArray) ?: JsonArray(emptyList())

/** An id field that Stripe sends either as a string or, when expanded, as an object with an `id`. */
fun JsonObject.idOf(key: String): String? = when (val value: JsonElement? = get(key)) {
    is JsonObject -> value.string("id")
    is JsonPrimitive -> value.takeIf { it !is JsonNull }?.contentOrNull
    else -> null
}

private fun JsonObject.toSubscription(): StripeSubscription {
    val item = obj("items")?.array("data")?.firstOrNull()?.jsonObject
    val price = item?.obj("price")
    // Since API 2025-03-31, the period lives on the item; older versions had it on the subscription.
    val periodStart = item?.long("current_period_start") ?: long("current_period_start")
    val periodEnd = item?.long("current_period_end") ?: long("current_period_end")
    val cancelsAt = long("cancel_at")
    return StripeSubscription(
        id = requireNotNull(string("id")) { "A subscription has an id." },
        customerId = requireNotNull(idOf("customer")) { "A subscription has a customer." },
        status = SubscriptionStatus.fromStripe(string("status") ?: ""),
        itemId = item?.string("id"),
        priceLookupKey = price?.string("lookup_key"),
        quantity = item?.long("quantity")?.toInt(),
        currentPeriodStart = periodStart?.let(Instant::fromEpochSeconds),
        currentPeriodEnd = periodEnd?.let(Instant::fromEpochSeconds),
        cancelAtPeriodEnd = bool("cancel_at_period_end") == true || (cancelsAt != null && cancelsAt == periodEnd),
        orgId = obj("metadata")?.string("org_id"),
    )
}

private fun JsonObject.toInvoice(): Invoice {
    val taxes = array("total_taxes").sumOf { it.jsonObject.long("amount") ?: 0 }
    return Invoice(
        id = requireNotNull(string("id")) { "An invoice has an id." },
        number = string("number"),
        status = string("status") ?: "unknown",
        total = long("total") ?: 0,
        tax = long("tax") ?: taxes,
        currency = string("currency") ?: "usd",
        created = Instant.fromEpochSeconds(long("created") ?: 0),
        hostedInvoiceUrl = string("hosted_invoice_url"),
        pdfUrl = string("invoice_pdf"),
    )
}

private fun JsonObject.requireString(key: String): String =
    string(key) ?: throw StripeApiException(HttpStatusCode.OK.value, null, "Stripe's answer has no $key.")
