// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [WebhooksService] on the repository. The secret is sealed with the server's key in [WebhookRules.secretContext]
 * and returned once; every change is written to the organization's audit log, never the secret.
 *
 * @param repository The webhooks.
 * @param cipher Seals the secrets.
 * @param signer Makes the secrets.
 * @param targets Where a webhook may point.
 * @param trail The organization's audit log.
 * @param clock The time.
 * @param newId Makes ids; tests fix them.
 */
@Suppress("LongParameterList") // The service's collaborators, each a port.
class WebhooksServiceImpl(
    private val repository: WebhooksRepository,
    private val cipher: SecretCipher,
    private val signer: WebhookSigner,
    private val targets: WebhookTargets,
    private val trail: OrgAuditTrail,
    private val clock: Clock,
    private val newId: () -> Uuid = { Uuid.random() },
) : WebhooksService {
    override suspend fun list(actor: WebhookActor): List<WebhookSubscription> = repository.list(actor.orgId)

    override suspend fun get(actor: WebhookActor, id: Uuid): WebhookSubscription =
        repository.find(actor.orgId, id) ?: throw WebhookNotFoundException()

    override suspend fun deliveries(actor: WebhookActor, id: Uuid, limit: Int): List<WebhookDeliveryRecord> {
        get(actor, id)
        return repository.deliveries(actor.orgId, id, limit.coerceIn(1, WebhookRules.MAX_LOG_PAGE))
    }

    override suspend fun create(
        actor: WebhookActor,
        url: String,
        types: List<String>,
        description: String,
    ): CreatedWebhookSecret {
        val kept = validate(url, types, description)
        if (!targets.allows(url)) throw WebhookHostNotAllowedException()
        val id = newId()
        val secret = signer.newSecret()
        val now = clock.now()
        val draft = WebhookSubscription(
            id = id,
            orgId = actor.orgId,
            url = url,
            types = kept,
            description = description,
            enabled = true,
            disabledReason = null,
            createdBy = actor.memberId,
            createdAt = now,
            updatedAt = now,
            version = 1,
            fanoutSeq = 0,
            failures = 0,
        )
        val created = repository.create(draft, seal(actor, id, secret))
        audit(actor, "webhook.created", id, "types" to kept.sorted())
        return CreatedWebhookSecret(created, secret)
    }

    override suspend fun update(
        actor: WebhookActor,
        id: Uuid,
        version: Int,
        url: String,
        types: List<String>,
        description: String,
        enabled: Boolean?,
    ): WebhookSubscription {
        val kept = validate(url, types, description)
        // A webhook kept on its URL can still be paused, relabelled or deleted after the operator withdrew private
        // targets (its deliveries are refused meanwhile); a new URL must pass, and a paused one on a refused URL
        // cannot be enabled again. Saving an enabled one unchanged stays possible: it delivers nothing either way.
        if (!targets.allows(url)) {
            val current = get(actor, id)
            if (current.url != url || (enabled == true && !current.enabled)) throw WebhookHostNotAllowedException()
        }
        val change = WebhookChange(version, url, kept, description, enabled, clock.now())
        val changed = repository.update(actor.orgId, id, change)
        audit(actor, "webhook.updated", id, "enabled" to changed.enabled)
        return changed
    }

    override suspend fun rotateSecret(actor: WebhookActor, id: Uuid): CreatedWebhookSecret {
        val secret = signer.newSecret()
        val rotated = repository.rotateSecret(actor.orgId, id, seal(actor, id, secret), clock.now())
        audit(actor, "webhook.secret_rotated", id)
        return CreatedWebhookSecret(rotated, secret)
    }

    override suspend fun delete(actor: WebhookActor, id: Uuid) {
        repository.delete(actor.orgId, id)
        audit(actor, "webhook.deleted", id)
    }

    override suspend fun test(actor: WebhookActor, id: Uuid): WebhookDeliveryRecord {
        val deliveryId = newId()
        val now = clock.now()
        val draft = WebhookDeliveryRecord(
            id = deliveryId,
            subscriptionId = id,
            eventSeq = null,
            eventType = WebhookRules.TEST_TYPE,
            payload = WebhookRules.payload(deliveryId, WebhookRules.TEST_TYPE, now, WebhookRules.TEST_DATA),
            state = DeliveryState.PENDING,
            attempts = 0,
            nextAttemptAt = now,
            lastStatus = null,
            lastError = null,
            createdAt = now,
            deliveredAt = null,
            test = true,
        )
        val queued = repository.enqueueTest(actor.orgId, draft)
        audit(actor, "webhook.tested", id)
        return queued
    }

    private suspend fun seal(actor: WebhookActor, id: Uuid, secret: String): String =
        cipher.seal(secret.encodeToByteArray(), WebhookRules.secretContext(actor.orgId, id))

    private suspend fun audit(actor: WebhookActor, event: String, id: Uuid, vararg facts: Pair<String, Any?>) {
        val details = auditDetails(*facts)
        trail.record(OrgAuditRecord(actor.orgId, actor.principal, event, "webhook", id.toString(), details))
    }

    private fun validate(url: String, types: List<String>, description: String): Set<String> {
        targets.requireWellFormed(url)
        val kept = types.toSet()
        if (kept.isEmpty()) throw InvalidWebhookException("Choose at least one event type.")
        if (kept.size > EventRules.MAX_TYPES || kept.any { OrgEventType.parse(it) == null }) {
            throw InvalidWebhookException("Name at most ${EventRules.MAX_TYPES} event types of the feed.")
        }
        if (description.length > WebhookRules.MAX_DESCRIPTION || description.any { it.isISOControl() }) {
            throw InvalidWebhookException(
                "The description is at most ${WebhookRules.MAX_DESCRIPTION} characters, without control characters.",
            )
        }
        return kept
    }
}
