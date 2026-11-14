// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import kotlinx.coroutines.test.runTest
import run.ostrom.server.events.domain.CreatedWebhookSecret
import run.ostrom.server.events.domain.DeliveryState
import run.ostrom.server.events.domain.InvalidWebhookException
import run.ostrom.server.events.domain.WebhookActor
import run.ostrom.server.events.domain.WebhookHostNotAllowedException
import run.ostrom.server.events.domain.WebhookNotFoundException
import run.ostrom.server.events.domain.WebhookRules
import run.ostrom.server.events.domain.WebhookTargets
import run.ostrom.server.events.domain.WebhooksServiceImpl
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The management of the webhooks: their rules, their secrets, their audit. */
class WebhooksServiceTest {
    private val org = Uuid.random()
    private val principal = AuthenticatedPrincipal("u1", PrincipalKind.USER, setOf("user:*"))
    private val actor = WebhookActor(org, Uuid.random(), principal)
    private val repository = InMemoryWebhooks()
    private val trail = RecordingTrail()
    private val clock = MovingClock()
    private val ids = generateSequence(1) { it + 1 }.map { Uuid.fromLongs(0, it.toLong()) }.iterator()

    private fun service(allowPrivate: Boolean = false) = WebhooksServiceImpl(
        repository,
        ContextCipher(),
        PlainSigner(),
        WebhookTargets(allowPrivate),
        trail,
        clock,
    ) { ids.next() }

    private suspend fun created(url: String = "https://hooks.example.com/a", allowPrivate: Boolean = false) =
        service(allowPrivate).create(actor, url, listOf("cloud.wait.created", "cloud.wait.created"), "Support")

    @Test
    fun aWebhookIsCreatedWithASealedSecretShownOnce() = runTest {
        val created = created()
        val webhook = created.webhook
        assertEquals("whsec_1", created.secret)
        assertEquals("sealed(webhook:$org:${webhook.id}):whsec_1", repository.secrets[webhook.id])
        assertEquals(setOf("cloud.wait.created"), webhook.types)
        assertTrue(webhook.enabled)
        assertEquals(actor.memberId, webhook.createdBy)
        assertFalse("whsec_1" in created.toString(), "The secret never reaches a log line")
        assertEquals(listOf("webhook.created"), trail.records.map { it.event })
        assertEquals(mapOf("types" to "cloud.wait.created"), trail.records.single().details)
        assertEquals(listOf(webhook), service().list(actor))
        assertEquals(webhook, service().get(actor, webhook.id))
        assertFailsWith<WebhookNotFoundException> { service().get(actor, Uuid.random()) }
    }

    @Test
    fun theTargetTypesAndDescriptionAreChecked() = runTest {
        val service = service()
        val hook = "https://hooks.example.com/"
        val todo = listOf("cloud.todo")
        assertFailsWith<InvalidWebhookException> { service.create(actor, "http://hooks.example.com/", todo, "") }
        assertFailsWith<WebhookHostNotAllowedException> { service.create(actor, "https://10.0.0.1/", todo, "") }
        assertFailsWith<InvalidWebhookException> { service.create(actor, hook, emptyList(), "") }
        assertFailsWith<InvalidWebhookException> { service.create(actor, hook, listOf("cloud.nope"), "") }
        val many = (1..65).map { "cloud.todo$it" }
        assertFailsWith<InvalidWebhookException> { service.create(actor, hook, many, "") }
        val long = "x".repeat(WebhookRules.MAX_DESCRIPTION + 1)
        assertFailsWith<InvalidWebhookException> { service.create(actor, hook, todo, long) }
        assertFailsWith<InvalidWebhookException> { service.create(actor, hook, todo, "a\u0007") }
        assertTrue(repository.webhooks.isEmpty())
        assertEquals("https://10.0.0.1/", created("https://10.0.0.1/", allowPrivate = true).webhook.url)
    }

    @Test
    fun aWebhookOnAWithdrawnPrivateTargetCanBeKeptPausedButNotMovedNorEnabled() = runTest {
        val webhook = created("https://10.0.0.1/", allowPrivate = true).webhook
        val service = service()
        val saved = service.update(actor, webhook.id, 1, webhook.url, listOf("cloud.todo"), "Kept", enabled = true)
        assertTrue(saved.enabled, "Saving an enabled one unchanged stays possible")
        val paused = service.update(actor, webhook.id, 2, webhook.url, listOf("cloud.todo"), "Paused", enabled = false)
        assertFalse(paused.enabled)
        assertEquals(listOf(true, false), repository.updates.map { it.enabled })
        assertFailsWith<WebhookHostNotAllowedException> {
            service.update(actor, webhook.id, 3, webhook.url, listOf("cloud.todo"), "", enabled = true)
        }
        assertFailsWith<WebhookHostNotAllowedException> {
            service.update(actor, webhook.id, 3, "https://10.0.0.2/", listOf("cloud.todo"), "", enabled = null)
        }
        assertEquals(4, service.update(actor, webhook.id, 3, webhook.url, listOf("cloud.todo"), "", null).version)
        assertFailsWith<WebhookNotFoundException> {
            service.update(actor, Uuid.random(), 1, webhook.url, listOf("cloud.todo"), "", null)
        }
        val moved = service.update(actor, webhook.id, 4, "https://hooks.example.com/", listOf("cloud.todo"), "", true)
        assertTrue(moved.enabled)
        assertEquals(listOf(true, false, false, true), trail.records.drop(1).map { it.details["enabled"] == "true" })
    }

    @Test
    fun theSecretRotatesTheLogIsBoundedAndADeleteIsAudited() = runTest {
        val webhook = created().webhook
        val service = service()
        val rotated: CreatedWebhookSecret = service.rotateSecret(actor, webhook.id)
        assertEquals("whsec_1", rotated.secret, "A new signer: its first secret")
        assertEquals("sealed(webhook:$org:${webhook.id}):whsec_1", repository.secrets[webhook.id])

        val test = service.test(actor, webhook.id)
        assertTrue(test.test)
        assertNull(test.eventSeq)
        assertEquals(DeliveryState.PENDING, test.state)
        assertEquals(clock.now, test.nextAttemptAt)
        assertEquals(
            """{"id":"${test.id}","type":"webhook.test","createdAt":"${clock.now}","data":${WebhookRules.TEST_DATA}}""",
            test.payload,
        )
        assertEquals(listOf(test), service.deliveries(actor, webhook.id, 0))
        assertFailsWith<WebhookNotFoundException> { service.deliveries(actor, Uuid.random(), 10) }

        service.delete(actor, webhook.id)
        assertEquals(
            listOf("webhook.created", "webhook.secret_rotated", "webhook.tested", "webhook.deleted"),
            trail.records.map { it.event },
        )
        assertTrue(trail.records.all { it.subjectType == "webhook" && it.subjectId == webhook.id.toString() })
    }
}
