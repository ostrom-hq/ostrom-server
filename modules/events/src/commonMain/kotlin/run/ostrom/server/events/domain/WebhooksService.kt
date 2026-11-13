// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.uuid.Uuid

/**
 * Who manages an organization's webhooks: an owner or an admin, as the routes checked.
 *
 * @property orgId The organization.
 * @property memberId The member.
 * @property principal The credential, for the audit log.
 */
data class WebhookActor(val orgId: Uuid, val memberId: Uuid, val principal: AuthenticatedPrincipal)

/**
 * A webhook as created or rotated, with its secret shown once.
 *
 * @property webhook The webhook.
 * @property secret Its secret.
 */
class CreatedWebhookSecret(val webhook: WebhookSubscription, val secret: String) {
    override fun toString(): String = "CreatedWebhookSecret(webhook=$webhook, secret=[REDACTED])"
}

/** The management of an organization's webhooks (TeamCode's `WebhookService`). */
interface WebhooksService {
    /**
     * The organization's webhooks.
     *
     * @param actor Who asks.
     *
     * @return Oldest first.
     */
    suspend fun list(actor: WebhookActor): List<WebhookSubscription>

    /**
     * One webhook.
     *
     * @param actor Who asks.
     * @param id The webhook.
     *
     * @return It.
     *
     * @throws WebhookNotFoundException When it does not exist in the organization.
     */
    suspend fun get(actor: WebhookActor, id: Uuid): WebhookSubscription

    /**
     * A webhook's delivery log.
     *
     * @param actor Who asks.
     * @param id The webhook.
     * @param limit The most, within 1 to [WebhookRules.MAX_LOG_PAGE].
     *
     * @return Newest first.
     *
     * @throws WebhookNotFoundException When it does not exist in the organization.
     */
    suspend fun deliveries(actor: WebhookActor, id: Uuid, limit: Int): List<WebhookDeliveryRecord>

    /**
     * Adds a webhook.
     *
     * @param actor Who.
     * @param url The target.
     * @param types The event types.
     * @param description The label.
     *
     * @return It, with its secret.
     *
     * @throws InvalidWebhookException When the URL, the types or the description break a rule.
     * @throws WebhookHostNotAllowedException When the target is off the allowed addresses.
     * @throws WebhookLimitException Past [WebhookRules.MAX_PER_ORG].
     */
    suspend fun create(actor: WebhookActor, url: String, types: List<String>, description: String): CreatedWebhookSecret

    /**
     * Changes a webhook.
     *
     * @param actor Who.
     * @param id The webhook.
     * @param version The version replaced.
     * @param url The target.
     * @param types The event types.
     * @param description The label.
     * @param enabled The new state, null to keep it.
     *
     * @return It.
     *
     * @throws InvalidWebhookException When the URL, the types or the description break a rule.
     * @throws WebhookHostNotAllowedException When a new target, or enabling one, is off the allowed addresses.
     * @throws WebhookNotFoundException When it does not exist in the organization.
     * @throws StaleWebhookVersionException When [version] is not current.
     */
    @Suppress("LongParameterList") // The fields of the update, as the contract names them.
    suspend fun update(
        actor: WebhookActor,
        id: Uuid,
        version: Int,
        url: String,
        types: List<String>,
        description: String,
        enabled: Boolean?,
    ): WebhookSubscription

    /**
     * A new secret, shown once.
     *
     * @param actor Who.
     * @param id The webhook.
     *
     * @return It, with its secret.
     *
     * @throws WebhookNotFoundException When it does not exist in the organization.
     */
    suspend fun rotateSecret(actor: WebhookActor, id: Uuid): CreatedWebhookSecret

    /**
     * Removes a webhook and its log.
     *
     * @param actor Who.
     * @param id The webhook.
     *
     * @throws WebhookNotFoundException When it does not exist in the organization.
     */
    suspend fun delete(actor: WebhookActor, id: Uuid)

    /**
     * Queues one signed `webhook.test` delivery.
     *
     * @param actor Who.
     * @param id The webhook.
     *
     * @return The delivery.
     *
     * @throws WebhookNotFoundException When it does not exist in the organization.
     * @throws WebhookTestRateException Past the tests allowed.
     */
    suspend fun test(actor: WebhookActor, id: Uuid): WebhookDeliveryRecord
}
