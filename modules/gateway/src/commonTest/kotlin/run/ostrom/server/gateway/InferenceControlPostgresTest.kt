// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway

import run.ostrom.server.gateway.presentation.InferenceQueueDto
import run.ostrom.server.gateway.presentation.InferenceSettingsDto
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real PostgreSQL and HTTP: the admission queue's counts and a member's concurrency limit, the workload and the
 * client's call id, and a member cancelling their own call before it starts or as it streams.
 */
class InferenceControlPostgresTest : GatewayHttpFixture() {
    private suspend fun TestServer.queue(token: String): InferenceQueueDto =
        client.get("/api/v1/inference/queue") { bearerAuth(token) }.body()

    @Test
    fun whoeverCallsModelsReadsTheQueueAndAWorkloadIsInteractiveOrBackground() =
        serverTest(modules, overrides) { server ->
            with(server) {
                for (token in listOf("agent-a", "use-a", "policy-a", "member-a")) {
                    assertEquals(InferenceQueueDto(0, 0), queue(token), token)
                }
                val reader = client.get("/api/v1/inference/queue") { bearerAuth("reader-a") }
                assertEquals(HttpStatusCode.Forbidden, reader.status)
                for (token in listOf(null, "nobody")) {
                    val refused = client.get("/api/v1/inference/queue") { token?.let { bearerAuth(it) } }
                    assertEquals(HttpStatusCode.Unauthorized, refused.status, token)
                }
                for (workload in listOf("urgent", "BACKGROUND")) {
                    val invalid = infer("messages", """{"model":"claude-opus-5"}""") {
                        header("x-api-key", "agent-a")
                        header("x-ostrom-workload", workload)
                    }
                    assertEquals(HttpStatusCode.BadRequest, invalid.status, workload)
                    assertEquals("invalid_request_error", errorField(invalid, "type"))
                    assertEquals("invalid_workload", invalid.headers["x-ostrom-error"])
                }
                assertTrue(received.isEmpty() && records.isEmpty(), "Refused before it is a model call")
            }
        }

    /** TeamCode's member concurrency (TC-SCH): a member's call past their limit waits for one of theirs to end. */
    @Test
    fun aMemberAtTheirLimitWaitsForTheirCallToEndAndTheQueueSaysSo() = serverTest(modules, overrides) { server ->
        with(server) {
            shareClaude()
            val ada = Gw.memberA.memberId.toString()
            val limited = InferenceSettingsDto(memberConcurrency = mapOf(ada.uppercase() to 1))
            val saved = putJson("/api/v1/inference/settings", "admin-a", limited)
            assertEquals(mapOf(ada to 1), saved.body<InferenceSettingsDto>().memberConcurrency, "In one spelling")
            val tooMany = InferenceSettingsDto(memberConcurrency = mapOf(ada to 33))
            assertEquals(HttpStatusCode.BadRequest, putJson("/api/v1/inference/settings", "admin-a", tooMany).status)

            val upstream = ByteChannel()
            broken = upstream
            val stream = """{"model":"claude-opus-5","stream":true}"""
            val (first, second) = coroutineScope {
                val first = async { infer("messages", stream).bodyAsText() }
                val second = async {
                    withContext(Dispatchers.Default) {
                        withTimeout(10.seconds) { while (received.isEmpty()) delay(5.milliseconds) }
                    }
                    infer("messages", stream) {
                        header("x-api-key", "agent-a")
                        header("x-ostrom-workload", "background")
                    }
                }
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) { while (queue("member-a").waitingMine == 0) delay(20.milliseconds) }
                }
                assertEquals(InferenceQueueDto(1, 0), queue("admin-a"), "The org's, and none of the admin's")
                assertEquals(1, received.size, "A waiting call never reaches the provider")
                broken = null
                upstream.writeStringUtf8(events)
                upstream.flushAndClose()
                first.await() to second.await()
            }
            assertEquals(events, first)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(events, second.bodyAsText())
            assertEquals(InferenceQueueDto(0, 0), queue("member-a"))
        }
    }

    /** TeamCode's `requests.cancel.v1`: a member stops their own call by its id, before it starts or as it streams. */
    @Test
    fun aMemberCancelsTheirOwnCallByItsIdBeforeItStartsOrWhileItStreams() = serverTest(modules, overrides) { server ->
        with(server) {
            shareClaude()
            val id = "6f1c2b0e-9a4d-4c3e-8b7a-2d5e1f0a9c41"
            val path = "/api/v1/inference/requests/$id/cancel"
            for (token in listOf("agent-a", "use-a", "member-a")) {
                assertEquals(HttpStatusCode.NoContent, client.post(path) { bearerAuth(token) }.status, token)
            }
            assertEquals(HttpStatusCode.Forbidden, client.post(path) { bearerAuth("reader-a") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post(path).status)
            val upper = client.post("/api/v1/inference/requests/${id.uppercase()}/cancel") { bearerAuth("agent-a") }
            assertEquals(HttpStatusCode.BadRequest, upper.status)
            assertTrue("invalid_request_id" in upper.bodyAsText())
            val invalid = infer("messages", """{"model":"claude-opus-5"}""") {
                header("x-api-key", "agent-a")
                header("x-ostrom-client-request-id", id.uppercase())
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_request_id", invalid.headers["x-ostrom-error"])

            val stream = """{"model":"claude-opus-5","stream":true}"""
            try {
                infer("messages", stream) {
                    header("x-api-key", "agent-a")
                    header("x-ostrom-client-request-id", id)
                }.bodyAsText()
            } catch (_: Exception) {
                // The call was stopped: the connection ends without an answer.
            }
            assertTrue(received.isEmpty() && records.isEmpty(), "Cancelled before it starts, it never starts")

            val running = "0b7e4f2a-1c3d-4e5f-8a9b-0c1d2e3f4a5b"
            val upstream = ByteChannel()
            broken = upstream
            val answer = coroutineScope {
                launch {
                    upstream.writeStringUtf8(events.substringBefore("event: content_block_delta"))
                    upstream.flush()
                    withContext(Dispatchers.Default) {
                        withTimeout(10.seconds) { while (received.isEmpty()) delay(5.milliseconds) }
                    }
                    client.post("/api/v1/inference/requests/$running/cancel") { bearerAuth("agent-a") }
                }
                try {
                    infer("messages", stream) {
                        header("x-api-key", "agent-a")
                        header("x-ostrom-client-request-id", running)
                    }.bodyAsText()
                } catch (_: Exception) {
                    ""
                }
            }
            assertFalse("message_delta" in answer, "Never ended as if complete")
            assertEquals("cancelled", recorded.receive().outcome)
            upstream.cancel(IllegalStateException("closed"))
        }
    }

}
