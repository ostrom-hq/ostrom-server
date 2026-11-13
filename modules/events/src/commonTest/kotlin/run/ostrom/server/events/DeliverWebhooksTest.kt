// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import run.ostrom.server.events.domain.DeliverWebhooksUseCaseImpl
import run.ostrom.server.events.domain.DeliveryState
import run.ostrom.server.events.domain.DueDelivery
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.SendResult
import run.ostrom.server.events.domain.WebhookDeliveryRecord
import run.ostrom.server.events.domain.WebhookRules
import run.ostrom.server.events.domain.WebhookSender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** One pass of the worker: what is queued, what is sent, and what each answer is recorded as. */
class DeliverWebhooksTest {
    private val orgA = Uuid.random()
    private val orgB = Uuid.random()
    private val webhook = Uuid.random()
    private val clock = MovingClock()
    private val repository = InMemoryWebhooks()
    private val sent = mutableListOf<Triple<String, Map<String, String>, String>>()

    /** Answers by the delivery's payload: `status:<n>`, `slow` (never answers) or anything else (a connect error). */
    private val sender = WebhookSender { url, headers, body ->
        sent += Triple(url, headers, body)
        when {
            body == "slow" -> awaitCancellation()
            body.startsWith("status:") -> SendResult(body.removePrefix("status:").toInt(), null)
            else -> SendResult(null, "connect")
        }
    }

    private fun useCase() = DeliverWebhooksUseCaseImpl(repository, ContextCipher(), PlainSigner(), sender, clock) {
        """{"seq":${it.seq}}"""
    }

    private fun due(org: Uuid, payload: String, attempts: Int = 0, test: Boolean = false, secret: String? = null) =
        DueDelivery(
            org,
            WebhookDeliveryRecord(
                Uuid.random(), webhook, if (test) null else 7, "cloud.todo", payload, DeliveryState.PENDING, attempts,
                clock.now, null, null, clock.now, null, test,
            ),
            "https://hooks.example.com/$org",
            secret ?: "sealed(webhook:$org:$webhook):whsec_1",
        )

    private fun outcomeOf(due: DueDelivery) = repository.recorded.single { it.first == due.delivery.id }.second

    @Test
    fun eachOrganizationWithWorkIsFannedOutThenItsDueDeliveriesAreSentSigned() = runTest {
        repository.orgs = listOf(orgA, orgB)
        repository.events = listOf(JournalEvent(42, "cloud.wait.created", "wait", "w-1", null, clock.now, "{}"))
        val delivered = due(orgA, "status:204")
        repository.due[orgA] = mutableListOf(delivered)

        assertEquals(1, useCase()())

        assertEquals(listOf(orgA, orgB), repository.fannedOut)
        assertEquals(
            """{"id":"00000000-0000-0000-0000-00000000000d","type":"cloud.wait.created",""" +
                """"createdAt":"${clock.now}","data":{"seq":42}}""",
            repository.payloads.first(),
        )
        assertEquals(listOf(orgA to WebhookRules.BATCH, orgB to WebhookRules.BATCH - 1), repository.claims)
        val (url, headers, body) = sent.single()
        assertEquals("https://hooks.example.com/$orgA", url)
        assertEquals("status:204", body)
        val seconds = clock.now.epochSeconds
        assertEquals(
            mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "Ostrom-Webhooks/1",
                "Ostrom-Delivery" to delivered.delivery.id.toString(),
                "Ostrom-Event" to "cloud.todo",
                "Ostrom-Timestamp" to seconds.toString(),
                "Ostrom-Signature" to "v1=whsec_1|$seconds|10",
            ),
            headers,
        )
        val outcome = outcomeOf(delivered)
        assertEquals(DeliveryState.DELIVERED, outcome.state)
        assertEquals(1, outcome.attempts)
        assertEquals(SendResult(204, null), outcome.result)
    }

    @Test
    fun aFullBatchClaimsNothingMoreFromTheNextOrganizations() = runTest {
        repository.orgs = listOf(orgA, orgB)
        repository.due[orgA] = MutableList(WebhookRules.BATCH) { due(orgA, "status:200") }
        assertEquals(WebhookRules.BATCH, useCase()())
        assertEquals(listOf(orgA to WebhookRules.BATCH), repository.claims)
        assertEquals(listOf(orgA, orgB), repository.fannedOut, "Every organization is still fanned out")
    }

    @Test
    fun retryableAnswersBackOffAndFinalOnesFail() = runTest {
        repository.orgs = listOf(orgA)
        val serverError = due(orgA, "status:503")
        val slowDown = due(orgA, "status:429", attempts = 6)
        val refused = due(orgA, "status:404")
        val redirect = due(orgA, "status:301")
        val exhausted = due(orgA, "status:500", attempts = WebhookRules.MAX_ATTEMPTS - 1)
        val test = due(orgA, "status:500", test = true)
        val unreachable = due(orgA, "nope", attempts = 2)
        val unreadable = due(orgA, "status:200", secret = "sealed(webhook:other):whsec_1")
        repository.due[orgA] =
            mutableListOf(serverError, slowDown, refused, redirect, exhausted, test, unreachable, unreadable)

        assertEquals(8, useCase()())

        val now = clock.now
        assertEquals(DeliveryState.PENDING, outcomeOf(serverError).state)
        assertEquals(now + 10.seconds, outcomeOf(serverError).nextAttemptAt)
        assertEquals(now + WebhookRules.BACKOFF.last(), outcomeOf(slowDown).nextAttemptAt)
        assertEquals(DeliveryState.FAILED, outcomeOf(refused).state)
        assertEquals(DeliveryState.FAILED, outcomeOf(redirect).state)
        assertEquals(DeliveryState.FAILED, outcomeOf(exhausted).state)
        assertEquals(WebhookRules.MAX_ATTEMPTS, outcomeOf(exhausted).attempts)
        assertEquals(DeliveryState.FAILED, outcomeOf(test).state, "A test is sent once")
        assertEquals(SendResult(null, "connect"), outcomeOf(unreachable).result)
        assertEquals(now + 2.seconds * 60, outcomeOf(unreachable).nextAttemptAt)
        assertEquals(SendResult(null, "secret_unreadable"), outcomeOf(unreadable).result)
        val unreadableId = unreadable.delivery.id.toString()
        assertFalse(sent.any { it.second["Ostrom-Delivery"] == unreadableId }, "Never sent unsigned")
    }

    @Test
    fun aReceiverThatNeverAnswersTimesOutAndTheMaintenanceUsesTheRetentions() = runTest {
        repository.orgs = listOf(orgA)
        val slow = due(orgA, "slow")
        repository.due[orgA] = mutableListOf(slow)
        assertEquals(1, useCase()())
        assertEquals(SendResult(null, "timeout"), outcomeOf(slow).result)
        assertTrue(outcomeOf(slow).state == DeliveryState.PENDING)

        assertEquals(WebhookRules.MAINTAIN_BATCH, useCase().maintain())
        val now: Instant = clock.now
        val bounds = listOf(now, now - WebhookRules.GIVE_UP, now - WebhookRules.LOG_RETENTION)
        assertEquals(bounds, repository.maintained.single())
    }
}
