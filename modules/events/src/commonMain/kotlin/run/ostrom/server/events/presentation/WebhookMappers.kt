// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.presentation

import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.CreatedWebhook
import run.ostrom.core.api.models.OrgEvent
import run.ostrom.core.api.models.Webhook
import run.ostrom.core.api.models.WebhookDelivery
import run.ostrom.server.events.domain.CreatedWebhookSecret
import run.ostrom.server.events.domain.DeliveryState
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.WebhookDeliveryRecord
import run.ostrom.server.events.domain.WebhookSubscription

/**
 * The contract's view of a webhook, never its secret.
 *
 * @return The webhook.
 */
fun WebhookSubscription.toResponse(): Webhook = Webhook(
    id = id.toString(),
    url = url,
    types = types.sorted(),
    description = description,
    enabled = enabled,
    disabledReason = disabledReason,
    createdBy = createdBy.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    version = version,
    consecutiveFailures = failures,
)

/**
 * The answer to a creation or a rotation: the webhook and its secret, shown this once.
 *
 * @return The answer.
 */
fun CreatedWebhookSecret.toResponse(): CreatedWebhook = CreatedWebhook(webhook.toResponse(), secret)

/**
 * The contract's view of a delivery, never its payload.
 *
 * @return The delivery.
 */
fun WebhookDeliveryRecord.toResponse(): WebhookDelivery = WebhookDelivery(
    id = id.toString(),
    eventSeq = eventSeq,
    eventType = eventType,
    state = state.wire,
    attempts = attempts,
    nextAttemptAt = nextAttemptAt.toString().takeIf { state == DeliveryState.PENDING },
    lastStatus = lastStatus,
    lastError = lastError,
    createdAt = createdAt.toString(),
    deliveredAt = deliveredAt?.toString(),
    test = test,
)

/**
 * An event as the feed sends it, the `data` of its webhook deliveries.
 *
 * @param event The event.
 *
 * @return Its JSON.
 */
fun feedJson(event: JournalEvent): String = OstromJson.encodeToString(OrgEvent.serializer(), event.toOrgEvent())
