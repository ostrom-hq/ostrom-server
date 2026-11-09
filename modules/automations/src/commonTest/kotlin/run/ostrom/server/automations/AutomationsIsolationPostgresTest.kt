// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.TriggerKind
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.fakes.RecordingLauncher
import run.ostrom.server.automations.fakes.testIdentities
import run.ostrom.server.automations.presentation.AnswerWaitRequest
import run.ostrom.server.automations.presentation.AutomationActionRequest
import run.ostrom.server.automations.presentation.AutomationRequest
import run.ostrom.server.automations.presentation.AutomationResponse
import run.ostrom.server.automations.presentation.AutomationsResponse
import run.ostrom.server.automations.presentation.CreateWaitRequest
import run.ostrom.server.automations.presentation.RunsResponse
import run.ostrom.server.automations.presentation.TestRunRequest
import run.ostrom.server.automations.presentation.WaitResponse
import run.ostrom.server.automations.presentation.WaitsResponse
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertTrue

/**
 * Another organization's administrator (carol, owner of org B) neither sees, changes nor runs an automation of org A,
 * nor reads, answers or cancels one of its waits: every list is empty for her, every route on one of them answers
 * 404, and the owner reads them unchanged.
 */
class AutomationsIsolationPostgresTest {
    private val webhook = AutomationRequest(
        "Ticket",
        AutomationTrigger(TriggerKind.WEBHOOK),
        AutomationActionRequest(prompt = "Fix {{ticket}}"),
        variables = listOf(AutomationVariable("ticket", "$.ticket.number")),
        overlap = "allow",
    )
    private val question = WaitSpec(
        "Review",
        body = "**Plan**",
        fields = listOf(WaitField("feedback", WaitFieldType.TEXT, "Feedback")),
        actions = listOf(WaitAction("approve", "Approve")),
    )

    private suspend inline fun <reified T : Any> HttpClient.postJson(
        token: String,
        path: String,
        body: T,
    ): HttpResponse =
        post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun anotherOrganizationNeitherSeesNorChangesNorRunsAnAutomation() {
        val launcher = RecordingLauncher()
        val overrides = module {
            includes(testIdentities)
            single<AutomationLauncher> { launcher }
        }
        serverTest(listOf(AutomationsModule), overrides) { server ->
            val client = server.client
            val created = client.postJson("alice", "/api/v1/automations", webhook).body<AutomationResponse>()
            val path = "/api/v1/automations/${created.id}"
            suspend fun read() = client.get(path) { bearerAuth("alice") }.body<AutomationResponse>()

            assertEquals(HttpStatusCode.NotFound, client.post("$path/enable") { bearerAuth("carol") }.status)
            assertFalse(read().enabled, "Not armed by B")
            assertEquals(HttpStatusCode.OK, client.post("$path/enable") { bearerAuth("alice") }.status)
            val before = read()
            assertTrue(before.enabled)

            for (listing in listOf("/api/v1/automations", "/api/v1/automations?owner=all")) {
                val items = client.get(listing) { bearerAuth("carol") }.body<AutomationsResponse>().items
                assertEquals(emptyList(), items, listing)
            }
            val change = client.put(path) {
                bearerAuth("carol")
                contentType(ContentType.Application.Json)
                setBody(webhook.copy(name = "Owned", version = before.version))
            }
            assertEquals(HttpStatusCode.NotFound, change.status, "PUT")
            for (move in listOf("disable", "webhook/rotate-secret")) {
                assertEquals(HttpStatusCode.NotFound, client.post("$path/$move") { bearerAuth("carol") }.status, move)
            }
            val test = TestRunRequest(buildJsonObject { putJsonObject("ticket") { put("number", "TKT-8") } })
            val run = client.post("$path/run") {
                bearerAuth("carol")
                contentType(ContentType.Application.Json)
                setBody(test)
                header("Idempotency-Key", "t1")
            }
            assertEquals(HttpStatusCode.NotFound, run.status, "run")
            assertEquals(HttpStatusCode.NotFound, client.get("$path/runs") { bearerAuth("carol") }.status, "runs")

            assertEquals(before, read(), "Unchanged by B: still armed, same name, version and secret")
            assertEquals(emptyList(), launcher.launched, "B started nothing")
            val runs = client.get("$path/runs") { bearerAuth("alice") }.body<RunsResponse>()
            assertEquals(emptyList(), runs.items, "No run recorded for A's automation")
        }
    }

    @Test
    fun anotherOrganizationNeitherSeesNorAnswersNorCancelsAWait() =
        serverTest(listOf(AutomationsModule), testIdentities) { server ->
            val client = server.client
            val wait = client.postJson("bob", "/api/v1/waits", CreateWaitRequest(question)).body<WaitResponse>()
            val path = "/api/v1/waits/${wait.id}"

            for (listing in listOf("/api/v1/waits", "/api/v1/waits?assignee=all", "/api/v1/waits?status=all")) {
                val items = client.get(listing) { bearerAuth("carol") }.body<WaitsResponse>().items
                assertEquals(emptyList(), items, listing)
            }
            val answer = client.postJson("carol", "$path/answer", AnswerWaitRequest("approve"))
            assertEquals(HttpStatusCode.NotFound, answer.status, "answer")
            assertEquals(HttpStatusCode.NotFound, client.post("$path/cancel") { bearerAuth("carol") }.status, "cancel")

            val after = client.get(path) { bearerAuth("bob") }.body<WaitResponse>()
            assertEquals(wait, after, "Still open, unanswered")
            assertEquals("open", after.status)
        }
}
