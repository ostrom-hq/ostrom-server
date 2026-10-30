package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.billing.domain.InvalidWebhookSignatureException
import dev.ostrom.server.billing.domain.StripeEvent
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The largest webhook body accepted: Stripe's events are a few kilobytes. */
const val MAX_WEBHOOK_BYTES = 256 * 1024

/**
 * Verifies the `Stripe-Signature` header of a webhook: an HMAC-SHA256 of `"<t>.<body>"` with the endpoint's signing
 * secret, in one of the `v1` entries, and a timestamp less than [tolerance] old (replay protection). Several `v1`
 * entries are accepted, as during a secret rotation.
 *
 * @param secrets The endpoint's signing secrets (two during a rotation).
 * @param clock The clock of the tolerance.
 * @param tolerance The largest accepted age, Stripe's default of 5 minutes.
 */
class StripeSignatureVerifier(
    private val secrets: List<String>,
    private val clock: Clock,
    private val tolerance: Duration = 5.minutes,
) {
    /** @throws InvalidWebhookSignatureException When no entry matches or the timestamp is out of tolerance. */
    suspend fun verify(payload: String, header: String?) {
        if (!isValid(payload, header)) throw InvalidWebhookSignatureException()
    }

    private suspend fun isValid(payload: String, header: String?): Boolean {
        val entries = header?.split(',')?.mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
        }.orEmpty()
        val timestamp = entries.firstOrNull { it.first == "t" }?.second?.toLongOrNull()
            ?.takeIf { (clock.now() - Instant.fromEpochSeconds(it)).absoluteValue <= tolerance }
            ?: return false
        val signatures = entries.filter { it.first == "v1" }.map { it.second }
        val signed = "$timestamp.$payload".encodeToByteArray()
        return secrets.any { secret ->
            val expected = hmacSha256Hex(secret, signed)
            signatures.any { constantTimeEquals(it, expected) }
        }
    }
}

/** HMAC-SHA256, lowercase hex: Stripe's webhook signature. */
@OptIn(ExperimentalStdlibApi::class)
suspend fun hmacSha256Hex(secret: String, data: ByteArray): String {
    val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
        .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
    return key.signatureGenerator().generateSignature(data).toHexString()
}

/** Compares without stopping at the first difference, so the time taken does not tell how much of a guess is right. */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
    return difference == 0
}

/**
 * Reads the part of a verified event that billing acts on. Invoices name their subscription under
 * `parent.subscription_details` since API 2025-03-31, and directly before; both are read.
 *
 * @throws InvalidWebhookSignatureException When the body is not a Stripe event (it was signed, so this is unexpected,
 *   and answered 400 so that Stripe shows it as failed).
 */
fun parseStripeEvent(payload: String): StripeEvent {
    val event = try {
        STRIPE_JSON.parseToJsonElement(payload) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    val id = event?.string("id")
    val type = event?.string("type")
    val target = event?.obj("data")?.obj("object")
    if (id == null || type == null || target == null) throw InvalidWebhookSignatureException()
    val objectType = target.string("object")
    val subscriptionId = when (objectType) {
        "subscription" -> target.string("id")
        "invoice" -> target.obj("parent")?.obj("subscription_details")?.idOf("subscription")
            ?: target.idOf("subscription")
        else -> target.idOf("subscription")
    }
    val orgId = target.obj("metadata")?.string("org_id")
        ?: target.string("client_reference_id")
        ?: target.obj("parent")?.obj("subscription_details")?.obj("metadata")?.string("org_id")
    return StripeEvent(
        id = id,
        type = type,
        created = Instant.fromEpochSeconds(event.long("created") ?: 0),
        customerId = if (objectType == "customer") target.string("id") else target.idOf("customer"),
        subscriptionId = subscriptionId,
        orgId = orgId,
        hostedInvoiceUrl = if (objectType == "invoice") target.string("hosted_invoice_url") else null,
    )
}
