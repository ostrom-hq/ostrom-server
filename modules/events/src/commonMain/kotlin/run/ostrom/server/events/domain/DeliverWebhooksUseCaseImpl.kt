// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Clock

/**
 * [DeliverWebhooksUseCase] as TeamCode's `DeliverWebhooksUseCase`: the deliveries of one pass are sent together, each
 * under its own deadline and with its own record, so a receiver that never answers delays neither the others nor
 * its own backoff.
 *
 * @param repository The webhooks.
 * @param cipher Opens the secrets.
 * @param signer Signs the deliveries.
 * @param sender The network edge.
 * @param clock The time.
 * @param render An event as the feed sends it, the `data` of its deliveries.
 */
@Suppress("LongParameterList") // The pass's collaborators, each a port.
class DeliverWebhooksUseCaseImpl(
    private val repository: WebhooksRepository,
    private val cipher: SecretCipher,
    private val signer: WebhookSigner,
    private val sender: WebhookSender,
    private val clock: Clock,
    private val render: (JournalEvent) -> String,
) : DeliverWebhooksUseCase {
    override suspend fun invoke(): Int {
        val orgs = repository.workingOrgs(clock.now(), WebhookRules.ORGS_PER_PASS)
        val due = mutableListOf<DueDelivery>()
        for (org in orgs) {
            repository.fanOut(org, WebhookRules.EVENTS_PER_FANOUT, clock.now()) { id, event ->
                WebhookRules.payload(id, event.type, clock.now(), render(event))
            }
            val room = WebhookRules.BATCH - due.size
            if (room > 0) due += repository.claimDue(org, room, clock.now(), WebhookRules.LEASE)
        }
        coroutineScope { due.map { async { attempt(it) } }.awaitAll() }
        return due.size
    }

    override suspend fun maintain(): Int {
        val now = clock.now()
        val giveUpBefore = now - WebhookRules.GIVE_UP
        return repository.maintain(now, giveUpBefore, now - WebhookRules.LOG_RETENTION, WebhookRules.MAINTAIN_BATCH)
    }

    private suspend fun attempt(due: DueDelivery) {
        val delivery = due.delivery
        val secret = cipher.open(due.sealedSecret, WebhookRules.secretContext(due.orgId, delivery.subscriptionId))
        val result = if (secret == null) SendResult(null, SECRET_UNREADABLE) else send(due, secret.decodeToString())
        val attempts = delivery.attempts + 1
        val now = clock.now()
        val outcome = when {
            result.delivered -> DeliveryOutcome(DeliveryState.DELIVERED, attempts, delivery.nextAttemptAt, result, now)
            // A test is sent once; a refused or exhausted delivery is final.
            delivery.test || !result.retryable || attempts >= WebhookRules.MAX_ATTEMPTS ->
                DeliveryOutcome(DeliveryState.FAILED, attempts, delivery.nextAttemptAt, result, now)
            else -> {
                val next = now + WebhookRules.BACKOFF[attempts - 1]
                DeliveryOutcome(DeliveryState.PENDING, attempts, next, result, now)
            }
        }
        repository.record(due.orgId, delivery.id, outcome)
    }

    private suspend fun send(due: DueDelivery, secret: String): SendResult {
        val delivery = due.delivery
        val seconds = clock.now().epochSeconds
        val headers = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to USER_AGENT,
            "Ostrom-Delivery" to delivery.id.toString(),
            "Ostrom-Event" to delivery.eventType,
            "Ostrom-Timestamp" to seconds.toString(),
            "Ostrom-Signature" to signer.signature(secret, seconds, delivery.payload),
        )
        return withTimeoutOrNull(WebhookRules.SEND_DEADLINE) { sender.send(due.url, headers, delivery.payload) }
            ?: SendResult(null, TIMEOUT)
    }

    private companion object {
        const val USER_AGENT = "Ostrom-Webhooks/1"
        const val SECRET_UNREADABLE = "secret_unreadable"
        const val TIMEOUT = "timeout"
    }
}
