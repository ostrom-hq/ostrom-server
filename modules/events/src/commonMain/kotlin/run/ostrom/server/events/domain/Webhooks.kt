// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An outgoing webhook of an organization (TeamCode's `WebhookSubscription`): every event of [types] the
 * organization's feed produces, whoever it belongs to, POSTed to [url].
 *
 * @property id Its id.
 * @property orgId The organization.
 * @property url The target, an `https` URL.
 * @property types The wire names of the event types it receives.
 * @property description A label of at most [WebhookRules.MAX_DESCRIPTION] characters.
 * @property enabled False while paused or disabled.
 * @property disabledReason `disabled` (paused by a person) or `too_many_failures`; null while enabled.
 * @property createdBy The member who created it.
 * @property createdAt When.
 * @property updatedAt Its last change.
 * @property version Its version, sent back by an update.
 * @property fanoutSeq The last sequence of the journal turned into deliveries.
 * @property failures Final failures in a row since the last success.
 */
data class WebhookSubscription(
    val id: Uuid,
    val orgId: Uuid,
    val url: String,
    val types: Set<String>,
    val description: String,
    val enabled: Boolean,
    val disabledReason: String?,
    val createdBy: Uuid,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int,
    val fanoutSeq: Long,
    val failures: Int,
)

/** A delivery's state. */
enum class DeliveryState(val wire: String) {
    /** To attempt, now or later. */
    PENDING("pending"),

    /** The receiver answered a 2xx. */
    DELIVERED("delivered"),

    /** Given up: out of attempts, expired, or refused for good by the receiver. */
    FAILED("failed"),
    ;

    /** Reads the states' wire names. */
    companion object {
        /**
         * The state of a wire name.
         *
         * @param wire The name.
         *
         * @return The state.
         */
        fun parse(wire: String): DeliveryState = entries.single { it.wire == wire }
    }
}

/**
 * One event for one webhook, and its attempts.
 *
 * @property id Its id, also the `Ostrom-Delivery` header.
 * @property subscriptionId The webhook.
 * @property eventSeq The event's sequence; null for a test.
 * @property eventType The event's type.
 * @property payload The body, fixed when the event is queued.
 * @property state Its state.
 * @property attempts The attempts made.
 * @property nextAttemptAt The next attempt, while pending.
 * @property lastStatus The last HTTP status the receiver answered.
 * @property lastError The last short error code.
 * @property createdAt When it was queued.
 * @property deliveredAt When the receiver took it.
 * @property test A `webhook.test` delivery, attempted once.
 */
data class WebhookDeliveryRecord(
    val id: Uuid,
    val subscriptionId: Uuid,
    val eventSeq: Long?,
    val eventType: String,
    val payload: String,
    val state: DeliveryState,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastStatus: Int?,
    val lastError: String?,
    val createdAt: Instant,
    val deliveredAt: Instant?,
    val test: Boolean,
)

/**
 * A delivery to attempt now, with its webhook's target and sealed secret.
 *
 * @property orgId The organization.
 * @property delivery The delivery.
 * @property url The webhook's target.
 * @property sealedSecret The webhook's secret, sealed in [WebhookRules.secretContext].
 */
data class DueDelivery(val orgId: Uuid, val delivery: WebhookDeliveryRecord, val url: String, val sealedSecret: String)

/**
 * What one HTTP attempt produced: a status, or a short error code (never a response body nor a message of the
 * network stack, which may echo the URL).
 *
 * @property status The receiver's HTTP status.
 * @property error The short error code when there was no answer.
 */
data class SendResult(val status: Int?, val error: String?) {
    /** The receiver took it. */
    val delivered: Boolean get() = status != null && status in WebhookRules.SUCCESS

    /** Worth another attempt: no answer, a server error, or a receiver asking to slow down. */
    val retryable: Boolean get() = status == null || status >= SERVER_ERROR || status in SLOW_DOWN

    private companion object {
        const val SERVER_ERROR = 500
        val SLOW_DOWN = setOf(408, 429)
    }
}

/**
 * What a delivery's attempt is recorded as.
 *
 * @property state The new state.
 * @property attempts The attempts made, this one included.
 * @property nextAttemptAt The next attempt.
 * @property result What the attempt produced.
 * @property at When it ended.
 */
data class DeliveryOutcome(
    val state: DeliveryState,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val result: SendResult,
    val at: Instant,
)

/** The webhooks' numbers, TeamCode's (`WebhookService`, `DeliverWebhooksUseCase`). */
object WebhookRules {
    /** Webhooks per organization. */
    const val MAX_PER_ORG: Int = 20

    /** The longest URL. */
    const val MAX_URL: Int = 2_048

    /** The longest description. */
    const val MAX_DESCRIPTION: Int = 200

    /** Attempts of one delivery. */
    const val MAX_ATTEMPTS: Int = 8

    /** Final failures in a row that disable a webhook. */
    const val DISABLE_AFTER: Int = 20

    /** A delivery still pending this long after it was queued is given up. */
    val GIVE_UP: Duration = 24.hours

    /** How long finished deliveries stay in the log. */
    val LOG_RETENTION: Duration = 14.days

    /** The waits before attempt 2, 3...: 10 s, 30 s, 2 min, 10 min, 30 min, 2 h, 6 h. */
    val BACKOFF: List<Duration> = listOf(10.seconds, 30.seconds, 2.minutes, 10.minutes, 30.minutes, 2.hours, 6.hours)

    /** Tests per webhook in [TEST_WINDOW]. */
    const val MAX_TESTS: Int = 3

    /** The window of [MAX_TESTS]. */
    val TEST_WINDOW: Duration = 10.seconds

    /** The default and largest page of the delivery log. */
    const val DEFAULT_LOG_PAGE: Int = 50
    const val MAX_LOG_PAGE: Int = 200

    /** A worker's claim on a delivery: a crashed worker's claims come back after it. */
    val LEASE: Duration = 60.seconds

    /** The longest one attempt may take, connection and answer included. */
    val SEND_DEADLINE: Duration = 15.seconds

    /** Deliveries attempted in one pass of a worker. */
    const val BATCH: Int = 25

    /** Organizations worked in one pass. */
    const val ORGS_PER_PASS: Int = 50

    /** Events turned into deliveries per webhook and pass. */
    const val EVENTS_PER_FANOUT: Int = 100

    /** Rows the maintenance expires or deletes in one statement. */
    const val MAINTAIN_BATCH: Int = 1_000

    /** The statuses that are a delivery. */
    val SUCCESS: IntRange = 200..299

    /** The type of a test delivery. */
    const val TEST_TYPE: String = "webhook.test"

    /** The data of a test delivery. */
    const val TEST_DATA: String = """{"message":"This is a test delivery from Ostrom."}"""

    /**
     * The context a webhook's secret is sealed in: another webhook's or organization's envelope does not open.
     *
     * @param orgId The organization.
     * @param id The webhook.
     *
     * @return The context.
     */
    fun secretContext(orgId: Uuid, id: Uuid): String = "webhook:$orgId:$id"

    /**
     * The body of a delivery: `{id, type, createdAt, data}`, `data` a JSON object (the event as the feed sends it).
     *
     * @param deliveryId The delivery.
     * @param type The event type, a plain token.
     * @param createdAt When it was queued.
     * @param dataJson The data, a JSON object.
     *
     * @return The body.
     */
    fun payload(deliveryId: Uuid, type: String, createdAt: Instant, dataJson: String): String =
        """{"id":"$deliveryId","type":"$type","createdAt":"$createdAt","data":$dataJson}"""
}
