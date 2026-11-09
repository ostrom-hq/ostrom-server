// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.TriggerKind
import run.ostrom.server.automations.domain.WebhookSecrets
import run.ostrom.server.automations.presentation.AutomationActionRequest
import run.ostrom.server.automations.presentation.AutomationRequest
import run.ostrom.server.automations.presentation.AutomationResponse
import run.ostrom.server.automations.presentation.RunsResponse
import run.ostrom.server.automations.presentation.TriggerResponse
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.presentation.CloudSessionsResponse
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/** Against a real PostgreSQL, both modules together: a signed webhook starts a cloud session as the owner. */
class AutomationLaunchPostgresTest {
    private val webhook = AutomationRequest(
        "Ticket",
        AutomationTrigger(TriggerKind.WEBHOOK),
        AutomationActionRequest(prompt = "Fix {{ticket}}"),
        variables = listOf(AutomationVariable("ticket", "$.ticket.number")),
        overlap = "allow",
    )

    private suspend fun HttpClient.trigger(secrets: WebhookSecrets, id: String, secret: String, key: String) =
        post("/api/v1/automations/$id/trigger") {
            val payload = """{"ticket":{"number":"TKT-7"}}"""
            val timestamp = Clock.System.now().epochSeconds.toString()
            val signature = secrets.sign(secret, "$timestamp.".encodeToByteArray() + payload.encodeToByteArray())
            header("Ostrom-Timestamp", timestamp)
            header("Ostrom-Signature", "v1=${signature.toHexString()}")
            header("Ostrom-Delivery", key)
            setBody(payload)
        }

    @Test
    fun aSignedWebhookStartsACloudSessionOwnedByTheAutomationsOwner() =
        serverTest(listOf(AutomationsModule, RunnersModule), testIdentities) { server ->
            val client = server.client
            val secrets = server.koin.get<WebhookSecrets>()
            val created = client.post("/api/v1/automations") {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody(webhook)
            }.body<AutomationResponse>()
            client.post("/api/v1/automations/${created.id}/enable") { bearerAuth("alice") }

            val triggered = client.trigger(secrets, created.id, assertNotNull(created.secret), "d1")
            assertEquals(HttpStatusCode.Accepted, triggered.status)
            assertEquals("started", triggered.body<TriggerResponse>().status)
            val runs = client.get("/api/v1/automations/${created.id}/runs") { bearerAuth("alice") }
                .body<RunsResponse>()
            val sessionId = assertNotNull(runs.items.single().sessionId)

            val sessions = client.get("/api/v1/cloud/sessions") { bearerAuth("alice") }.body<CloudSessionsResponse>()
            val session = sessions.items.single()
            assertEquals(sessionId, session.id)
            assertEquals("Ticket", session.name)
            assertEquals("creating", session.status)
            // Bob, in the same organization, does not see alice's session.
            val bobs = client.get("/api/v1/cloud/sessions") { bearerAuth("bob") }.body<CloudSessionsResponse>()
            assertEquals(emptyList(), bobs.items)
        }

    @Test
    fun anOrganizationOutsideTheAllowlistGetsAFailedRunThatSaysWhy() =
        serverTest(listOf(AutomationsModule, RunnersModule), testIdentities) { server ->
            val client = server.client
            val secrets = server.koin.get<WebhookSecrets>()
            val created = client.post("/api/v1/automations") {
                bearerAuth("carol")
                contentType(ContentType.Application.Json)
                setBody(webhook)
            }.body<AutomationResponse>()
            client.post("/api/v1/automations/${created.id}/enable") { bearerAuth("carol") }

            val run = client.trigger(secrets, created.id, assertNotNull(created.secret), "d1").body<TriggerResponse>()
            assertEquals("failed", run.status)
            val runs = client.get("/api/v1/automations/${created.id}/runs") { bearerAuth("carol") }
                .body<RunsResponse>()
            assertEquals("cloud_sessions_unavailable", runs.items.single().reason)
        }
}
