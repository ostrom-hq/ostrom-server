// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.TriggerKind
import run.ostrom.server.automations.domain.WebhookSecrets
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.RecordingLauncher
import run.ostrom.server.automations.fakes.testIdentities
import run.ostrom.server.automations.infrastructure.UnavailableLauncher
import run.ostrom.server.automations.presentation.AutomationActionRequest
import run.ostrom.server.automations.presentation.AutomationRequest
import run.ostrom.server.automations.presentation.AutomationResponse
import run.ostrom.server.automations.presentation.AutomationsResponse
import run.ostrom.server.automations.presentation.DryRunResponse
import run.ostrom.server.automations.presentation.RunResponse
import run.ostrom.server.automations.presentation.RunsResponse
import run.ostrom.server.automations.presentation.SecretResponse
import run.ostrom.server.automations.presentation.TestRunRequest
import run.ostrom.server.automations.presentation.TriggerResponse
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The automations routes, against a real PostgreSQL. */
class AutomationsApiTest {
    private val cron = AutomationRequest(
        "Nightly",
        AutomationTrigger(TriggerKind.CRON, "0 21 * * *", "Europe/Paris"),
        AutomationActionRequest(prompt = "Summarize {{run.date}}"),
    )
    private val webhook = AutomationRequest(
        "Ticket",
        AutomationTrigger(TriggerKind.WEBHOOK),
        AutomationActionRequest(prompt = "Fix {{ticket}}"),
        variables = listOf(AutomationVariable("ticket", "$.ticket.number")),
        overlap = "allow",
    )
    private val body = """{"ticket":{"number":"TKT-7"}}"""

    private suspend fun HttpClient.create(token: String, request: AutomationRequest): HttpResponse =
        post("/api/v1/automations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.send(token: String, method: String, path: String): HttpResponse =
        if (method == "DELETE") delete(path) { bearerAuth(token) } else post(path) { bearerAuth(token) }

    private suspend fun HttpClient.change(token: String, path: String, request: AutomationRequest): HttpResponse =
        put(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.run(token: String, path: String, request: TestRunRequest, key: String? = null) =
        post("$path/run") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
            if (key != null) header("Idempotency-Key", key)
        }

    private suspend fun HttpClient.trigger(
        secrets: WebhookSecrets,
        id: String,
        secret: String,
        payload: String = body,
        key: String? = null,
    ): HttpResponse = triggerAt(secrets, "/api/v1/automations/$id/trigger", secret, payload, key)

    private suspend fun HttpClient.triggerAt(
        secrets: WebhookSecrets,
        url: String,
        secret: String,
        payload: String,
        key: String?,
    ): HttpResponse {
        val timestamp = Clock.System.now().epochSeconds.toString()
        val signature = secrets.sign(secret, "$timestamp.".encodeToByteArray() + payload.encodeToByteArray())
        return post(url) {
            header("Ostrom-Timestamp", timestamp)
            header("Ostrom-Signature", "v1=${signature.toHexString()}")
            // A key starting with "idem:" goes in Idempotency-Key, which wins over Ostrom-Delivery.
            if (key != null) {
                val name = if (key.startsWith("idem:")) "Idempotency-Key" else "Ostrom-Delivery"
                header(name, key.removePrefix("idem:"))
            }
            setBody(payload)
        }
    }

    @Test
    fun anAutomationIsCreatedArmedChangedAndDeleted() = serverTest(listOf(AutomationsModule), testIdentities) {
        val client = it.client
        val created = client.create("alice", cron)
        assertEquals(HttpStatusCode.Created, created.status)
        val automation = created.body<AutomationResponse>()
        assertEquals(ALICE.toString(), automation.owner)
        assertFalse(automation.enabled)
        assertNull(automation.webhookUrl)
        assertNull(automation.secret)
        val path = "/api/v1/automations/${automation.id}"
        assertEquals(automation, client.get(path) { bearerAuth("autoreader") }.body<AutomationResponse>())
        val listed = client.get("/api/v1/automations?limit=1") { bearerAuth("alice") }.body<AutomationsResponse>()
        assertEquals(listOf(automation.id), listed.items.map { item -> item.id })
        assertNull(listed.next)
        val second = client.create("alice", cron.copy(name = "Weekly")).body<AutomationResponse>()
        val first = client.get("/api/v1/automations?limit=1") { bearerAuth("alice") }.body<AutomationsResponse>()
        assertEquals(listOf(second.id), first.items.map { item -> item.id })
        assertNotNull(first.next)
        // An action needs a prompt: workflows are not available yet.
        val promptless = cron.copy(action = AutomationActionRequest())
        assertEquals(HttpStatusCode.BadRequest, client.create("alice", promptless).status)
        val armed = client.send("autowriter", "POST", "$path/enable").body<AutomationResponse>()
        assertTrue(armed.enabled)
        assertNotNull(armed.nextRunAt)
        val renamed = client.change("alice", path, cron.copy(name = "Renamed", version = 1))
        assertEquals("Renamed", renamed.body<AutomationResponse>().name)
        assertEquals(HttpStatusCode.BadRequest, client.change("alice", path, cron).status)
        assertEquals(HttpStatusCode.Conflict, client.change("alice", path, cron.copy(version = 1)).status)
        assertFalse(client.send("dave", "POST", "$path/disable").body<AutomationResponse>().enabled)
        val dry = client.run("alice", path, TestRunRequest(dryRun = true)).body<DryRunResponse>()
        assertEquals(3, dry.nextRuns.size)
        assertEquals(HttpStatusCode.NoContent, client.send("alice", "DELETE", path).status)
        assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth("alice") }.status)
    }

    @Test
    fun aSignedWebhookStartsARunOnceAndASecretRotates() {
        val launcher = RecordingLauncher()
        val overrides = module {
            includes(testIdentities)
            single<AutomationLauncher> { launcher }
        }
        serverTest(listOf(AutomationsModule), overrides) {
            val client = it.client
            val secrets = it.koin.get<WebhookSecrets>()
            val created = client.create("alice", webhook).body<AutomationResponse>()
            val secret = assertNotNull(created.secret)
            assertEquals("/api/v1/automations/${created.id}/trigger", created.webhookUrl)
            val path = "/api/v1/automations/${created.id}"
            assertEquals(HttpStatusCode.Conflict, client.trigger(secrets, created.id, secret).status)
            client.send("alice", "POST", "$path/enable")
            val first = client.trigger(secrets, created.id, secret, key = "d1")
            assertEquals(HttpStatusCode.Accepted, first.status)
            val run = first.body<TriggerResponse>()
            assertEquals("started", run.status)
            assertEquals("Fix TKT-7", launcher.launched.single().prompt)
            // A delivery is accepted once: a retry or a replay answers 409 (S2-50).
            assertEquals(HttpStatusCode.Conflict, client.trigger(secrets, created.id, secret, key = "d1").status)
            assertEquals(HttpStatusCode.Unauthorized, client.trigger(secrets, created.id, "whsec_wrong").status)
            val large = "{\"x\":\"" + "y".repeat(64 * 1024) + "\"}"
            assertEquals(HttpStatusCode.BadRequest, client.trigger(secrets, created.id, secret, large).status)
            assertEquals(HttpStatusCode.Unauthorized, client.trigger(secrets, "nope", secret).status)

            val rotated = client.send("alice", "POST", "$path/webhook/rotate-secret").body<SecretResponse>()
            assertNotNull(rotated.previousSecretValidUntil)
            val read = client.get(path) { bearerAuth("alice") }.body<AutomationResponse>()
            assertEquals(rotated.previousSecretValidUntil, read.previousSecretValidUntil)
            val current = client.trigger(secrets, created.id, rotated.secret, key = "d2")
            assertEquals(HttpStatusCode.Accepted, current.status)
            assertEquals(HttpStatusCode.Accepted, client.trigger(secrets, created.id, secret, key = "d3").status)
            val keyed = client.trigger(secrets, created.id, rotated.secret, key = "idem:d3")
            assertEquals(HttpStatusCode.Conflict, keyed.status)

            val test = TestRunRequest(buildJsonObject { putJsonObject("ticket") { put("number", "TKT-8") } })
            val tested = client.run("alice", path, test, key = "t1")
            assertEquals(HttpStatusCode.Accepted, tested.status)
            assertEquals("test", tested.body<RunResponse>().source)
            val replayed = client.run("alice", path, test, key = "t1")
            assertEquals("true", replayed.headers["Idempotency-Replayed"])
            val history = client.get("$path/runs?limit=2") { bearerAuth("autoreader") }.body<RunsResponse>()
            assertEquals(2, history.items.size)
            val rest = client.get("$path/runs?before=${history.next}") { bearerAuth("alice") }.body<RunsResponse>()
            assertEquals(2, rest.items.size)
            assertNull(rest.next)
            val end = client.get("$path/runs?before=${rest.items.last().id}") { bearerAuth("alice") }
            assertEquals(emptyList(), end.body<RunsResponse>().items, "After the oldest run")
        }
    }

    @Test
    fun aVersionedUrlRunsOnlyWhileTheAutomationIsAtThatVersion() {
        val launcher = RecordingLauncher()
        val overrides = module {
            includes(testIdentities)
            single<AutomationLauncher> { launcher }
        }
        serverTest(listOf(AutomationsModule), overrides) {
            val client = it.client
            val secrets = it.koin.get<WebhookSecrets>()
            val created = client.create("alice", webhook).body<AutomationResponse>()
            val secret = assertNotNull(created.secret)
            val path = "/api/v1/automations/${created.id}"
            client.send("alice", "POST", "$path/enable")
            suspend fun run(version: String, key: String, with: String = secret, id: String = created.id) =
                client.triggerAt(secrets, "/api/v1/automations/$id/v/$version/trigger", with, body, key)
            assertEquals(HttpStatusCode.Accepted, run("1", "a").status)
            assertEquals(HttpStatusCode.Accepted, run("latest", "b").status)
            assertEquals(HttpStatusCode.Accepted, client.trigger(secrets, created.id, secret, key = "c").status)
            // The proof first: a wrong secret, or an id that cannot exist, is the same 401 whatever the version.
            assertEquals(HttpStatusCode.Unauthorized, run("9", "d", with = "whsec_wrong").status)
            assertEquals(HttpStatusCode.Unauthorized, run("1", "d", id = "nope").status)
            assertEquals(HttpStatusCode.Unauthorized, run("1", "d", id = Uuid.random().toString()).status)
            assertEquals(HttpStatusCode.BadRequest, run("x", "d").status)
            val stale = run("9", "d")
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue("automation_version_changed" in stale.bodyAsText())
            // Arming, pausing and rotating the secret keep the version.
            client.send("alice", "POST", "$path/disable")
            client.send("alice", "POST", "$path/enable")
            val rotated = client.send("alice", "POST", "$path/webhook/rotate-secret").body<SecretResponse>()
            assertEquals(1, client.get(path) { bearerAuth("alice") }.body<AutomationResponse>().version)
            assertEquals(HttpStatusCode.Accepted, run("1", "e", with = rotated.secret).status)
            val runsBefore = client.get("$path/runs") { bearerAuth("alice") }.body<RunsResponse>().items.size
            val renamed = client.change("alice", path, webhook.copy(name = "Ticket 2", version = 1))
            assertEquals(2, renamed.body<AutomationResponse>().version)
            val old = run("1", "f", with = rotated.secret)
            assertEquals(HttpStatusCode.Conflict, old.status)
            assertTrue("automation_version_changed" in old.bodyAsText())
            assertEquals(HttpStatusCode.Accepted, run("2", "g", with = rotated.secret).status)
            assertEquals(HttpStatusCode.Accepted, run("latest", "h", with = rotated.secret).status)
            val runs = client.get("$path/runs?limit=100") { bearerAuth("alice") }.body<RunsResponse>()
            // The refused call recorded nothing: only the two that ran are new.
            assertEquals(runsBefore + 2, runs.items.size)
            assertEquals(listOf(2, 2, 1), runs.items.take(3).map { run -> run.version })
        }
    }

    @Test
    fun aRunIsCancelledByItsOwnerAKeyOrAnAdministratorAndOnlyOnce() {
        val launcher = RecordingLauncher()
        val overrides = module {
            includes(testIdentities)
            single<AutomationLauncher> { launcher }
        }
        serverTest(listOf(AutomationsModule), overrides) {
            val client = it.client
            val secrets = it.koin.get<WebhookSecrets>()
            val created = client.create("alice", webhook).body<AutomationResponse>()
            val secret = assertNotNull(created.secret)
            val path = "/api/v1/automations/${created.id}"
            client.send("alice", "POST", "$path/enable")
            val first = client.trigger(secrets, created.id, secret, key = "a").body<TriggerResponse>()
            val second = client.trigger(secrets, created.id, secret, key = "b").body<TriggerResponse>()
            launcher.working += listOf("session-1", "session-2")
            val cancel = "$path/runs/${first.runId}/cancel"
            assertEquals(HttpStatusCode.Unauthorized, client.post(cancel).status)
            assertEquals(HttpStatusCode.Forbidden, client.send("autoreader", "POST", cancel).status)
            assertEquals(HttpStatusCode.Forbidden, client.send("agent", "POST", cancel).status)
            assertEquals(HttpStatusCode.NotFound, client.send("bob", "POST", cancel).status)
            assertEquals(HttpStatusCode.NotFound, client.send("carol", "POST", cancel).status)
            assertEquals(HttpStatusCode.NotFound, client.send("nobody", "POST", cancel).status)
            val absent = listOf("$path/runs/${Uuid.random()}", "$path/runs/nope", "/api/v1/automations/nope/runs/x")
            for (missing in absent) {
                assertEquals(HttpStatusCode.NotFound, client.send("alice", "POST", "$missing/cancel").status, missing)
            }
            assertEquals(emptyList(), launcher.paused)
            val cancelled = client.send("dave", "POST", cancel)
            assertEquals(HttpStatusCode.OK, cancelled.status)
            assertEquals("cancelled", cancelled.body<RunResponse>().status)
            assertEquals(listOf("$ALICE:session-1"), launcher.paused)
            assertEquals(HttpStatusCode.Conflict, client.send("alice", "POST", cancel).status)
            val byKey = client.send("autowriter", "POST", "$path/runs/${second.runId}/cancel")
            assertEquals("cancelled", byKey.body<RunResponse>().status)
            val history = client.get("$path/runs") { bearerAuth("alice") }.body<RunsResponse>()
            assertEquals(listOf("cancelled", "cancelled"), history.items.map { run -> run.status })
        }
    }

    @Test
    fun withoutCloudSessionsARunFailsAndSaysWhy() = serverTest(listOf(AutomationsModule), testIdentities) {
        val client = it.client
        val created = client.create("alice", webhook).body<AutomationResponse>()
        client.send("alice", "POST", "/api/v1/automations/${created.id}/enable")
        val secrets = it.koin.get<WebhookSecrets>()
        val run = client.trigger(secrets, created.id, assertNotNull(created.secret)).body<TriggerResponse>()
        assertEquals("failed", run.status)
        val history = client.get("/api/v1/automations/${created.id}/runs") { bearerAuth("alice") }.body<RunsResponse>()
        assertEquals("cloud_sessions_unavailable", history.items.single().reason)
        assertFalse(UnavailableLauncher.isWorking(ORG_A, "s1"))
        assertFalse(UnavailableLauncher.pause(ORG_A, "alice", "s1"))
    }

    @Test
    fun refusalsMapToStatuses() = serverTest(listOf(AutomationsModule), testIdentities) {
        val client = it.client
        val created = client.create("alice", cron).body<AutomationResponse>()
        val path = "/api/v1/automations/${created.id}"
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/automations").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$path/enable").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/automations") { bearerAuth("cloud") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.create("autoreader", cron).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("agent", "POST", "$path/enable").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/automations") { bearerAuth("nobody") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth("carol") }.status)
        assertEquals(HttpStatusCode.NotFound, client.send("carol", "DELETE", path).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/automations/nope") { bearerAuth("alice") }.status)
        val badCursor = client.get("/api/v1/automations?before=nope") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.BadRequest, badCursor.status)
        val badRunsCursor = client.get("$path/runs?before=nope") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.BadRequest, badRunsCursor.status)
        // An id that is no run of this automation, of this organization: refused, not an empty page.
        val forgedRunsCursor = client.get("$path/runs?before=${Uuid.random()}") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.BadRequest, forgedRunsCursor.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/automations?owner=all") { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/automations?owner=all") { bearerAuth("dave") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.send("dave", "POST", "$path/enable").status)
        assertEquals(HttpStatusCode.BadRequest, client.send("alice", "POST", "$path/webhook/rotate-secret").status)
        assertEquals(HttpStatusCode.BadRequest, client.run("alice", path, TestRunRequest()).status)
        val workflow = cron.copy(action = AutomationActionRequest(workflowId = "wf"))
        assertEquals(HttpStatusCode.BadRequest, client.create("alice", workflow).status)
        assertEquals(HttpStatusCode.BadRequest, client.create("alice", cron.copy(overlap = "queue")).status)
        assertEquals(HttpStatusCode.BadRequest, client.create("alice", cron.copy(name = "")).status)
    }
}
