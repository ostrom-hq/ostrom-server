// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.domain

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The webhooks and their delivery log. Every call but [workingOrgs] and [maintain] stays inside one organization;
 * every worker step is a conditional update, so replicas run them together.
 */
interface WebhooksRepository {
    /**
     * An organization's webhooks, oldest first.
     *
     * @param orgId The organization.
     *
     * @return At most [WebhookRules.MAX_PER_ORG].
     */
    suspend fun list(orgId: Uuid): List<WebhookSubscription>

    /**
     * One webhook.
     *
     * @param orgId The organization.
     * @param id The webhook.
     *
     * @return It, or null.
     */
    suspend fun find(orgId: Uuid, id: Uuid): WebhookSubscription?

    /**
     * Adds a webhook, at the journal's head: it receives only what happens next.
     *
     * @param subscription The webhook; its [WebhookSubscription.fanoutSeq] is replaced by the head.
     * @param sealedSecret Its sealed secret.
     *
     * @return It, as stored.
     *
     * @throws WebhookLimitException Past [WebhookRules.MAX_PER_ORG].
     */
    suspend fun create(subscription: WebhookSubscription, sealedSecret: String): WebhookSubscription

    /**
     * Changes a webhook. A null [WebhookChange.enabled] keeps its state; enabling it again starts at the journal's
     * head and clears its failures.
     *
     * @param orgId The organization.
     * @param id The webhook.
     * @param change The change.
     *
     * @return It, changed.
     *
     * @throws WebhookNotFoundException When it does not exist.
     * @throws StaleWebhookVersionException When [WebhookChange.version] is not its version.
     */
    suspend fun update(orgId: Uuid, id: Uuid, change: WebhookChange): WebhookSubscription

    /**
     * Replaces a webhook's secret; the deliveries still queued are signed with the new one.
     *
     * @param orgId The organization.
     * @param id The webhook.
     * @param sealedSecret The new secret, sealed.
     * @param at When.
     *
     * @return It.
     *
     * @throws WebhookNotFoundException When it does not exist.
     */
    suspend fun rotateSecret(orgId: Uuid, id: Uuid, sealedSecret: String, at: Instant): WebhookSubscription

    /**
     * Removes a webhook and its delivery log.
     *
     * @param orgId The organization.
     * @param id The webhook.
     *
     * @throws WebhookNotFoundException When it does not exist.
     */
    suspend fun delete(orgId: Uuid, id: Uuid)

    /**
     * A webhook's delivery log, newest first.
     *
     * @param orgId The organization.
     * @param id The webhook.
     * @param limit The most to return.
     *
     * @return The deliveries.
     */
    suspend fun deliveries(orgId: Uuid, id: Uuid, limit: Int): List<WebhookDeliveryRecord>

    /**
     * Queues one test delivery, attempted once.
     *
     * @param orgId The organization.
     * @param delivery The delivery.
     *
     * @return It, as stored.
     *
     * @throws WebhookNotFoundException When its webhook does not exist.
     * @throws WebhookTestRateException Past [WebhookRules.MAX_TESTS] in [WebhookRules.TEST_WINDOW].
     */
    suspend fun enqueueTest(orgId: Uuid, delivery: WebhookDeliveryRecord): WebhookDeliveryRecord

    /**
     * The organizations a worker has something to do in: an enabled webhook behind the journal's head, or a delivery
     * due and unclaimed. The one read across organizations of a pass.
     *
     * @param now The time.
     * @param limit The most to return.
     *
     * @return The organizations, the longest waiting first.
     */
    suspend fun workingOrgs(now: Instant, limit: Int): List<Uuid>

    /**
     * Turns the journal's events after each enabled webhook's cursor into deliveries, at most [perWebhook] each.
     * A delivery is unique per webhook and event, so a replica or a restart never queues an event twice.
     *
     * @param orgId The organization.
     * @param perWebhook The most events per webhook.
     * @param now The time.
     * @param payload The body of a delivery of an event.
     *
     * @return The deliveries queued.
     */
    suspend fun fanOut(orgId: Uuid, perWebhook: Int, now: Instant, payload: (Uuid, JournalEvent) -> String): Int

    /**
     * Claims deliveries due now for [lease]: those of an enabled webhook, and tests.
     *
     * @param orgId The organization.
     * @param limit The most to claim.
     * @param now The time.
     * @param lease How long the claim holds.
     *
     * @return The deliveries claimed.
     */
    suspend fun claimDue(orgId: Uuid, limit: Int, now: Instant, lease: Duration): List<DueDelivery>

    /**
     * Records an attempt and releases the claim. A final failure that is not a test counts against its webhook,
     * which is disabled (`too_many_failures`) at [WebhookRules.DISABLE_AFTER]; a delivery resets the count.
     *
     * @param orgId The organization.
     * @param id The delivery.
     * @param outcome What it is recorded as.
     */
    suspend fun record(orgId: Uuid, id: Uuid, outcome: DeliveryOutcome)

    /**
     * Gives up on the deliveries pending since before [giveUpBefore] (`expired`) and deletes the finished ones
     * queued before [deleteBefore], across organizations.
     *
     * @param now The time.
     * @param giveUpBefore The oldest a pending delivery may be.
     * @param deleteBefore The oldest a finished delivery may be.
     * @param limit The most rows of each kind.
     *
     * @return The rows changed.
     */
    suspend fun maintain(now: Instant, giveUpBefore: Instant, deleteBefore: Instant, limit: Int): Int

    /**
     * Removes every webhook of a deleted organization, and their deliveries.
     *
     * @param orgId The organization.
     */
    suspend fun erase(orgId: Uuid)
}

/**
 * A change of a webhook.
 *
 * @property version The version it replaces.
 * @property url The target.
 * @property types The event types.
 * @property description The label.
 * @property enabled The new state, null to keep it.
 * @property at When.
 */
data class WebhookChange(
    val version: Int,
    val url: String,
    val types: Set<String>,
    val description: String,
    val enabled: Boolean?,
    val at: Instant,
)
