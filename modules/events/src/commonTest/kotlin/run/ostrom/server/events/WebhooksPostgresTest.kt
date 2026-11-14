// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.core.module.Module
import org.koin.dsl.module
import run.ostrom.core.api.models.CreateWebhookRequest
import run.ostrom.core.api.models.CreatedWebhook
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.core.api.models.UpdateWebhookRequest
import run.ostrom.core.api.models.Webhook
import run.ostrom.core.api.models.WebhookDelivery
import run.ostrom.core.api.models.WebhookDeliveryList
import run.ostrom.core.api.models.WebhookList
import run.ostrom.server.events.domain.DeliverWebhooksUseCase
import run.ostrom.server.events.domain.DeliveryOutcome
import run.ostrom.server.events.domain.DeliveryState
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.SendResult
import run.ostrom.server.events.domain.WebhookActor
import run.ostrom.server.events.domain.WebhookNotFoundException
import run.ostrom.server.events.domain.WebhookRules
import run.ostrom.server.events.domain.WebhookSender
import run.ostrom.server.events.domain.WebhookSigner
import run.ostrom.server.events.domain.WebhooksRepository
import run.ostrom.server.events.domain.WebhooksService
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: who manages webhooks, their rules, and the worker that delivers them. */
class WebhooksPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val ownerA = Uuid.parse("0000000a-0000-0000-0000-0000000000a1")
    private val clock = MovingClock()

    /** Each token's organization, member and role; a token absent here belongs to no organization. */
    private val members = mapOf(
        "owner-a" to OrgPrincipal(orgA, ownerA, OrgRole.OWNER),
        "member-a" to OrgPrincipal(orgA, Uuid.random(), OrgRole.MEMBER),
        "reader-a" to OrgPrincipal(orgA, ownerA, OrgRole.ADMIN),
        "cli-a" to OrgPrincipal(orgA, ownerA, OrgRole.ADMIN),
        "key-a" to OrgPrincipal(orgA, ownerA, OrgRole.ADMIN),
        "teamkey-a" to OrgPrincipal(orgA, ownerA, OrgRole.ADMIN),
        "agent-a" to OrgPrincipal(orgA, ownerA, OrgRole.OWNER),
        "owner-b" to OrgPrincipal(orgB, Uuid.random(), OrgRole.OWNER),
    )

    private fun principal(token: String): AuthenticatedPrincipal? = when (token) {
        "reader-a" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("cloud:read", "cloud:write"))
        "cli-a" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"), signedIn = true)
        "key-a" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf("cloud:admin"))
        "teamkey-a" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf("team:read", "cloud:read"))
        "agent-a" -> AuthenticatedPrincipal(token, PrincipalKind.AGENT, setOf("agent:session"))
        "owner-a", "member-a", "owner-b", "stranger" ->
            AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER)
        else -> null
    }

    /** Answers [status] to every delivery, and keeps what it was sent. */
    private class ScriptedSender(var status: Int = 200) : WebhookSender {
        val sent = mutableListOf<Triple<String, Map<String, String>, String>>()

        override suspend fun send(url: String, headers: Map<String, String>, body: String): SendResult {
            sent += Triple(url, headers, body)
            return SendResult(status, null)
        }
    }

    private val sender = ScriptedSender()

    private val overrides: Module = module {
        single<Clock> { clock }
        single<SecretCipher> { ContextCipher() }
        single<WebhookSender> { sender }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = principal(token)
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    members[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
    }

    private val modules = listOf(EventsModule)

    private suspend fun TestServer.create(
        token: String,
        url: String = "https://hooks.example.com/in",
        description: String? = "Support",
    ): HttpResponse = client.post("/api/v1/webhooks") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(CreateWebhookRequest(url, listOf("cloud.wait.created"), description))
    }

    /** Organization B neither sees nor changes A's webhook [id]. */
    private suspend fun TestServer.assertOrganizationBCannotReach(id: String, url: String) {
        val ofB = client.get("/api/v1/webhooks") { bearerAuth("owner-b") }.body<WebhookList>()
        assertEquals(emptyList(), ofB.items)
        for (path in listOf("/api/v1/webhooks/$id", "/api/v1/webhooks/$id/deliveries")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth("owner-b") }.status, path)
        }
        for (path in listOf("/api/v1/webhooks/$id/test", "/api/v1/webhooks/$id/rotate-secret")) {
            assertEquals(HttpStatusCode.NotFound, client.post(path) { bearerAuth("owner-b") }.status, path)
        }
        val changed = change("owner-b", id, UpdateWebhookRequest(1, url, listOf("cloud.todo")))
        assertEquals(HttpStatusCode.NotFound, changed.status)
        val deleted = client.delete("/api/v1/webhooks/$id") { bearerAuth("owner-b") }
        assertEquals(HttpStatusCode.NotFound, deleted.status)
    }

    private suspend fun TestServer.change(token: String, id: String, request: UpdateWebhookRequest): HttpResponse =
        client.put("/api/v1/webhooks/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    @Test
    fun onlyAnOwnerOrAnAdminManagesWebhooks() = serverTest(modules, overrides) { server ->
        assertEquals(setOf(ServerFeatures.EVENTS, ServerFeatures.WEBHOOKS), EventsModule.features(server.koin))
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/webhooks").status)
        assertEquals(HttpStatusCode.NotFound, server.client.get("/api/v1/webhooks") { bearerAuth("stranger") }.status)
        // A personal token made in the console never manages them, whatever its cloud scopes (TeamCode).
        for (token in listOf("member-a", "teamkey-a", "agent-a", "reader-a")) {
            val read = server.client.get("/api/v1/webhooks") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Forbidden, read.status, token)
            assertEquals(HttpStatusCode.Forbidden, server.create(token).status, token)
        }
        assertEquals(HttpStatusCode.Created, server.create("cli-a").status, "An admin's ostrom login does")
        assertEquals(HttpStatusCode.Created, server.create("key-a").status, "An admin's cloud:admin key does")
    }

    @Test
    fun aWebhookIsCreatedChangedTestedRotatedAndDeleted() = serverTest(modules, overrides) { server ->
        val answer = server.create("owner-a")
        assertEquals(HttpStatusCode.Created, answer.status, answer.bodyAsText())
        val created = answer.body<CreatedWebhook>()
        val url = created.webhook.url
        val todo = listOf("cloud.todo")
        val id = created.webhook.id
        assertTrue(created.secret.startsWith("whsec_"))
        assertEquals(listOf("cloud.wait.created"), created.webhook.types)
        assertEquals(ownerA.toString(), created.webhook.createdBy)
        val listed = server.client.get("/api/v1/webhooks") { bearerAuth("owner-a") }.body<WebhookList>()
        assertEquals(listOf(id), listed.items.map { it.id })

        server.assertOrganizationBCannotReach(id, url)
        val odd = server.client.get("/api/v1/webhooks/not-a-uuid") { bearerAuth("owner-a") }
        assertEquals(HttpStatusCode.NotFound, odd.status)

        assertTrue("invalid_webhook" in server.create("owner-a", "http://hooks.example.com/", null).bodyAsText())
        assertTrue("webhook_host_not_allowed" in server.create("owner-a", "https://10.0.0.1/").bodyAsText())

        val stale = server.change("owner-a", id, UpdateWebhookRequest(9, url, todo))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue("stale_version" in stale.bodyAsText())
        val paused = server.change("owner-a", id, UpdateWebhookRequest(1, url, todo, "Ops", false)).body<Webhook>()
        assertEquals(false to "disabled", paused.enabled to paused.disabledReason)
        assertEquals(2, paused.version)
        val kept = server.change("owner-a", id, UpdateWebhookRequest(2, url, todo)).body<Webhook>()
        assertEquals(false, kept.enabled, "An omitted enabled keeps the pause")
        val enabled = server.change("owner-a", id, UpdateWebhookRequest(3, url, todo, null, true)).body<Webhook>()
        assertEquals(true to null, enabled.enabled to enabled.disabledReason)
        assertEquals("", enabled.description)

        repeat(3) {
            val test = server.client.post("/api/v1/webhooks/$id/test") { bearerAuth("owner-a") }
            assertEquals(HttpStatusCode.Accepted, test.status, test.bodyAsText())
            assertTrue(test.body<WebhookDelivery>().test)
        }
        val limited = server.client.post("/api/v1/webhooks/$id/test") { bearerAuth("owner-a") }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("10", limited.headers[HttpHeaders.RetryAfter])
        val log = server.client.get("/api/v1/webhooks/$id/deliveries?limit=2") { bearerAuth("key-a") }
            .body<WebhookDeliveryList>()
        assertEquals(2, log.items.size)
        assertTrue(log.items.all { it.state == "pending" && it.eventType == "webhook.test" })
        assertTrue(log.items.all { it.nextAttemptAt != null })

        val rotated = server.client.post("/api/v1/webhooks/$id/rotate-secret") { bearerAuth("owner-a") }
            .body<CreatedWebhook>()
        assertNotEquals(created.secret, rotated.secret)
        assertEquals(5, rotated.webhook.version)

        val removed = server.client.delete("/api/v1/webhooks/$id") { bearerAuth("owner-a") }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        val gone = server.client.get("/api/v1/webhooks/$id") { bearerAuth("owner-a") }
        assertEquals(HttpStatusCode.NotFound, gone.status)
        val left = server.database.superuser.fetchAll("SELECT COUNT(*) AS n FROM events_webhook_deliveries")
            .getOrThrow()
        assertEquals(0, left.first().get("n").asLong(), "Its log went with it")
    }

    @Test
    fun anOrganizationHasAtMostTwentyWebhooks() = serverTest(modules, overrides) { server ->
        repeat(20) {
            assertEquals(HttpStatusCode.Created, server.create("key-a", "https://hooks.example.com/$it").status)
        }
        val refused = server.create("owner-a")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("webhook_limit" in refused.bodyAsText())
        assertEquals(HttpStatusCode.Created, server.create("owner-b").status, "Organization B counts on its own")
    }

    private suspend fun TestServer.publish(org: Uuid, type: OrgEventType, subject: String) =
        koin.get<OrgEventPublisher>().publish(org, listOf(OrgEventDraft(type, subject, null)))

    private suspend fun TestServer.count(table: String, org: Uuid): Long = driver.withOrganization(org) { tx ->
        tx.fetchAll(Statement.create("SELECT COUNT(*) AS n FROM $table")).getOrThrow().first().get("n").asLong()
    }

    @Test
    fun theWorkerDeliversEachEventOnceSignedAndBacksOffThenDisables() = serverTest(modules, overrides) { server ->
        server.publish(orgA, OrgEventType.WAIT_CREATED, "w-0")
        val service = server.koin.get<WebhooksService>()
        val actor = WebhookActor(orgA, ownerA, principal("owner-a")!!)
        val created = service.create(actor, "https://hooks.example.com/in", listOf("cloud.wait.created"), "")
        val deliver = server.koin.get<DeliverWebhooksUseCase>()
        val repository = server.koin.get<WebhooksRepository>()
        assertEquals(emptyList(), repository.workingOrgs(clock.now, 50), "It starts at the head: w-0 is not sent")

        server.publish(orgA, OrgEventType.WAIT_CREATED, "w-1")
        server.publish(orgA, OrgEventType.TODO_UPDATED, "s-1")
        server.publish(orgB, OrgEventType.WAIT_CREATED, "w-9")
        assertEquals(listOf(orgA), repository.workingOrgs(clock.now, 50))
        assertEquals(1, deliver())
        assertEquals(0, deliver(), "Never queued twice")

        val (url, headers, body) = sender.sent.single()
        assertEquals("https://hooks.example.com/in", url)
        for (fact in listOf(""""type":"cloud.wait.created"""", """"subjectId":"w-1"""", """"seq":2""")) {
            assertTrue(fact in body, body)
        }
        val timestamp = headers.getValue("Ostrom-Timestamp").toLong()
        val signature = server.koin.get<WebhookSigner>().signature(created.secret, timestamp, body)
        assertEquals(signature, headers["Ostrom-Signature"])
        val delivered = repository.deliveries(orgA, created.webhook.id, 10).single()
        assertEquals(DeliveryState.DELIVERED to 1, delivered.state to delivered.attempts)
        assertEquals(clock.now, delivered.deliveredAt)
        assertEquals(headers["Ostrom-Delivery"], delivered.id.toString())

        // A server error is retried after 10 s; the claim is released at once.
        sender.status = 503
        clock.now += 1.seconds // The log is newest first: w-2's delivery comes after w-1's.
        server.publish(orgA, OrgEventType.WAIT_CREATED, "w-2")
        assertEquals(1, deliver())
        val retried = repository.deliveries(orgA, created.webhook.id, 1).single()
        assertEquals(DeliveryState.PENDING, retried.state)
        assertEquals(clock.now + 10.seconds, retried.nextAttemptAt)
        assertEquals(503, retried.lastStatus)
        assertEquals(0, deliver(), "Not before its next attempt")
        clock.now += 10.seconds
        sender.status = 200
        assertEquals(1, deliver())
        assertEquals(DeliveryState.DELIVERED, repository.deliveries(orgA, created.webhook.id, 1).single().state)

        // The twentieth final failure in a row disables it.
        server.database.superuser.execute("UPDATE events_webhooks SET failures = 19").getOrThrow()
        sender.status = 404
        server.publish(orgA, OrgEventType.WAIT_CREATED, "w-3")
        assertEquals(1, deliver())
        val disabled = repository.find(orgA, created.webhook.id)!!
        assertEquals(false to "too_many_failures", disabled.enabled to disabled.disabledReason)
        assertEquals(20, disabled.failures)
        server.publish(orgA, OrgEventType.WAIT_CREATED, "w-4")
        assertEquals(0, deliver(), "A disabled webhook queues nothing")

        // Organization B sees none of it.
        assertEquals(0, server.count("events_webhooks", orgB))
        assertEquals(0, server.count("events_webhook_deliveries", orgB))
        assertEquals(3, server.count("events_webhook_deliveries", orgA))
    }

    @Test
    fun staleDeliveriesExpireTheLogAgesOutAndAnOrganizationIsErased() = serverTest(modules, overrides) { server ->
        val service = server.koin.get<WebhooksService>()
        val actor = WebhookActor(orgA, ownerA, principal("owner-a")!!)
        val webhook = service.create(actor, "https://hooks.example.com/in", listOf("cloud.wait.created"), "").webhook
        service.test(actor, webhook.id)
        clock.now += 11.seconds
        service.test(actor, webhook.id)
        val repository = server.koin.get<WebhooksRepository>()
        val deliver = server.koin.get<DeliverWebhooksUseCase>()

        server.database.superuser
            .execute("UPDATE events_webhook_deliveries SET created_at = created_at - INTERVAL '2 days'")
            .getOrThrow()
        clock.now += 1.days
        // Both tests are older than 24 hours and were never sent: given up.
        assertEquals(2, deliver.maintain())
        val expired = repository.deliveries(orgA, webhook.id, 10)
        assertEquals(listOf("expired", "expired"), expired.map { it.lastError })
        assertTrue(expired.all { it.state == DeliveryState.FAILED })
        clock.now += 15.days
        assertEquals(2, deliver.maintain(), "Past the 14 days of the log")
        assertTrue(repository.deliveries(orgA, webhook.id, 10).isEmpty())

        service.test(actor, webhook.id)
        repository.erase(orgA)
        assertNull(repository.find(orgA, webhook.id))
        assertEquals(0, server.count("events_webhook_deliveries", orgA))
    }

    @Test
    fun aBacklogIsQueuedAPageAtATimeAndAnUnansweredAttemptRecorded() = serverTest(modules, overrides) { server ->
        val service = server.koin.get<WebhooksService>()
        val actor = WebhookActor(orgA, ownerA, principal("owner-a")!!)
        val webhook = service.create(actor, "https://hooks.example.com/in", listOf("cloud.wait.created"), "").webhook
        repeat(3) { server.publish(orgA, OrgEventType.WAIT_CREATED, "w-$it") }
        val repository = server.koin.get<WebhooksRepository>()
        val payload = { id: Uuid, event: JournalEvent -> """{"id":"$id","seq":${event.seq}}""" }
        assertEquals(2, repository.fanOut(orgA, 2, clock.now, payload))
        assertEquals(1, repository.fanOut(orgA, 2, clock.now, payload))
        assertEquals(0, repository.fanOut(orgA, 2, clock.now, payload))

        val claimed = repository.claimDue(orgA, 10, clock.now, WebhookRules.LEASE)
        assertEquals(listOf(1L, 2L, 3L), claimed.mapNotNull { it.delivery.eventSeq }.sorted())
        assertTrue(repository.claimDue(orgA, 10, clock.now, WebhookRules.LEASE).isEmpty(), "Claimed for a minute")
        val first = claimed.first().delivery
        val retry = clock.now + 10.seconds
        val outcome = DeliveryOutcome(DeliveryState.PENDING, 1, retry, SendResult(null, "connect"), clock.now)
        repository.record(orgA, first.id, outcome)
        repository.record(orgA, Uuid.random(), outcome)
        val recorded = repository.deliveries(orgA, webhook.id, 10).single { it.id == first.id }
        assertEquals(null to "connect", recorded.lastStatus to recorded.lastError)
        assertEquals(0, repository.find(orgA, webhook.id)!!.failures, "A retry is not a final failure")

        // A test's failure says nothing of the webhook's health; a paused webhook counts failures, stays paused.
        val failed = DeliveryOutcome(DeliveryState.FAILED, 1, clock.now, SendResult(500, null), clock.now)
        repository.record(orgA, service.test(actor, webhook.id).id, failed)
        assertEquals(0, repository.find(orgA, webhook.id)!!.failures)
        service.update(actor, webhook.id, 1, webhook.url, webhook.types.toList(), "", false)
        repository.record(orgA, claimed.last().delivery.id, failed)
        val paused = repository.find(orgA, webhook.id)!!
        assertEquals(Triple(1, false, "disabled"), Triple(paused.failures, paused.enabled, paused.disabledReason))
        assertFailsWith<WebhookNotFoundException> { repository.rotateSecret(orgA, Uuid.random(), "x", clock.now) }
    }
}
