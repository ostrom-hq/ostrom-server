// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudConversationService
import run.ostrom.server.runners.domain.CloudConversationServiceImpl
import run.ostrom.server.runners.domain.CloudEvent
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.ConversationAnnouncement
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.infrastructure.CloudConversationMaintenance
import run.ostrom.server.runners.presentation.AnswerCloudPermissionBody
import run.ostrom.server.runners.presentation.CloudEventPageDto
import run.ostrom.server.runners.presentation.CloudInputDto
import run.ostrom.server.runners.presentation.CloudInputPageDto
import run.ostrom.server.runners.presentation.CloudQuestionAnswerDto
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CloudSubagentListDto
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.runners.presentation.SendCloudMessageBody
import run.ostrom.server.runners.presentation.StopCloudTaskBody
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import org.koin.dsl.module
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The conversation routes on real PostgreSQL: who reaches them, what they answer, and another organization's 404. */
class CloudConversationRoutesPostgresTest {
    private suspend fun TestServer.create(agent: String = "claude", mode: String? = "conversation"): HttpResponse =
        client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = agent, mode = mode, effort = "high"))
        }

    private suspend fun TestServer.conversation(): String {
        val created = create()
        assertEquals(HttpStatusCode.Created, created.status)
        val session = created.body<CloudSessionResponse>()
        assertEquals("conversation" to "high", session.mode to session.effort)
        assertEquals(false, session.permissionBypass)
        assertNull(session.waiting)
        return "/api/v1/cloud/sessions/${session.id}"
    }

    private suspend fun TestServer.running(path: String) {
        val id = path.substringAfterLast('/')
        database.superuserOutsideTransaction("UPDATE runners_sessions SET state = 'running' WHERE id = '$id'")
    }

    private suspend fun HttpResponse.type(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type")
            .jsonPrimitive.content

    private suspend fun TestServer.send(
        path: String,
        body: SendCloudMessageBody,
        caller: String = "alice",
        key: String? = null,
    ): HttpResponse = client.post("$path/messages") {
        bearerAuth(caller)
        key?.let { header("Idempotency-Key", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun aConversationIsCreatedForClaudeAndCodexOnly() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        // The module announces the conversation keys (RunnersModule.features reads this binding).
        assertTrue(server.koin.get<ConversationAnnouncement>().announced)
        assertEquals("invalid_mode", server.create(agent = "gemini").type())
        assertEquals("invalid_mode", server.create(mode = "chat").type())
        val terminal = server.create(mode = null).body<CloudSessionResponse>()
        assertEquals("terminal", terminal.mode)
        val path = server.conversation()
        // A conversation has no terminal; a terminal session no transcript to stream nor messages.
        val ticket = server.client.post("$path/terminal/ticket") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.Conflict, ticket.status)
        assertEquals("conversation_session", ticket.type())
        val other = "/api/v1/cloud/sessions/${terminal.id}"
        assertEquals("terminal_session", server.client.get("$other/events/stream") { bearerAuth("alice") }.type())
        assertEquals("terminal_session", server.send(other, SendCloudMessageBody("Hi")).type())
        val events = server.client.get("$other/events") { bearerAuth("alice") }.body<CloudEventPageDto>()
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun theOwnerSendsReadsAndRetriesMessages() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val path = server.conversation()
        val sent = server.send(path, SendCloudMessageBody("Fix the tests"), key = "k1")
        assertEquals(HttpStatusCode.Accepted, sent.status)
        val input = sent.body<CloudInputDto>()
        assertEquals("message" to "queued", input.kind to input.status)
        assertEquals("k1", input.clientMessageId)
        assertNull(sent.headers["Idempotency-Replayed"])
        val replay = server.send(path, SendCloudMessageBody("Fix the tests"), key = "k1")
        assertEquals("true", replay.headers["Idempotency-Replayed"])
        assertEquals(input.id, replay.body<CloudInputDto>().id)
        val conflict = server.send(path, SendCloudMessageBody("Something else"), key = "k1")
        assertEquals(HttpStatusCode.UnprocessableEntity, conflict.status)
        assertEquals("idempotency_conflict", conflict.type())
        val alias = server.send(path, SendCloudMessageBody("Again", clientMessageId = "k2"))
        assertEquals(HttpStatusCode.Accepted, alias.status)
        val both = server.send(path, SendCloudMessageBody("x", clientMessageId = "a"), key = "b")
        assertEquals("invalid_idempotency_key", both.type())
        assertEquals("invalid_idempotency_key", server.send(path, SendCloudMessageBody("x"), key = "with space").type())
        assertEquals("files_unsupported", server.send(path, SendCloudMessageBody("x", fileIds = listOf("f1"))).type())
        assertEquals("invalid_message", server.send(path, SendCloudMessageBody(null)).type())

        val page = server.client.get("$path/messages?limit=1") { bearerAuth("alice") }.body<CloudInputPageDto>()
        assertEquals(1, page.inputs.size)
        val next = server.client.get("$path/messages?before=${page.nextBefore}") { bearerAuth("dana") }
            .body<CloudInputPageDto>()
        assertEquals(input.id, next.inputs.single().id)
        assertEquals(
            input.id,
            server.client.get("$path/messages/${input.id}") { bearerAuth("reader") }.body<CloudInputDto>().id,
        )
        assertEquals(HttpStatusCode.NotFound, server.client.get("$path/messages/nope") { bearerAuth("alice") }.status)
        assertEquals("invalid_cursor", server.client.get("$path/messages?before=nope") { bearerAuth("alice") }.type())
        val badLimit = server.client.get("$path/messages?limit=x") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.BadRequest, badLimit.status)
        // The collection's own `limit` (a number) reads the query first.
        assertEquals("invalid_request", server.client.get("$path/events?limit=x") { bearerAuth("alice") }.type())
    }

    @Test
    fun theTranscriptIsReadPagedAndStreamed() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val path = server.conversation()
        val id = path.substringAfterLast('/')
        server.database.superuserOutsideTransaction(
            "INSERT INTO runners_session_events (session_id, seq, org_id, epoch, runner_seq, type, at, data, feed) " +
                "SELECT id, 1, org_id, 1, 1, 'message.agent', now(), '{\"text\":\"Hi\"}', '' FROM runners_sessions " +
                "WHERE id = '$id'",
        )
        server.database.superuserOutsideTransaction(
            "INSERT INTO runners_session_transcripts (session_id, org_id, last_seq, runner_epoch, runner_seq) " +
                "SELECT id, org_id, 1, 1, 1 FROM runners_sessions WHERE id = '$id'",
        )
        val page = server.client.get("$path/events?after=0&limit=10&feed=main") { bearerAuth("alice") }
            .body<CloudEventPageDto>()
        assertEquals("Hi", page.events.single().data.getValue("text").jsonPrimitive.content)
        assertEquals("invalid_cursor", server.client.get("$path/events?after=x") { bearerAuth("alice") }.type())
        assertEquals("invalid_cursor", server.client.get("$path/events?before=x") { bearerAuth("alice") }.type())
        val agents = server.client.get("$path/agents") { bearerAuth("alice") }.body<CloudSubagentListDto>()
        assertTrue(agents.agents.isEmpty())

        // The session ends: the stream sends what is there, then says so.
        server.database.superuserOutsideTransaction("UPDATE runners_sessions SET state = 'terminated' WHERE id = '$id'")
        val stream = server.client.get("$path/events/stream") {
            bearerAuth("alice")
            header("Last-Event-ID", "0")
        }
        assertEquals(HttpStatusCode.OK, stream.status)
        val text = stream.bodyAsText()
        assertTrue(text.startsWith("retry: 2000\n\n"))
        assertTrue("id: 1\nevent: message.agent\ndata: " in text)
        assertTrue(text.endsWith("event: end\ndata: {}\n\n"))
    }

    @Test
    fun interruptsAnswersTaskStopsAndOptions() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val path = server.conversation()
        assertEquals("nothing_to_interrupt", server.client.post("$path/interrupt") { bearerAuth("alice") }.type())
        server.running(path)
        val interrupt = server.client.post("$path/interrupt") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.Accepted, interrupt.status)
        val stop = server.client.post("$path/tasks/stop") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(StopCloudTaskBody("b1"))
        }
        assertEquals("stop_task", stop.body<CloudInputDto>().kind)
        suspend fun answer(body: AnswerCloudPermissionBody) = server.client.post("$path/permissions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals("unknown_permission_request", answer(AnswerCloudPermissionBody("r1", "deny")).type())
        assertEquals("invalid_permission_answer", answer(AnswerCloudPermissionBody("r1", "maybe")).type())
        val id = path.substringAfterLast('/')
        server.database.superuserOutsideTransaction(
            "INSERT INTO runners_session_events (session_id, seq, org_id, epoch, runner_seq, type, at, data, feed) " +
                "SELECT id, 1, org_id, 0, 1, 'permission.request', now(), " +
                "'{\"requestId\":\"q1\",\"question\":true,\"input\":{\"questions\":[{\"options\":[{},{}]}]}}', '' " +
                "FROM runners_sessions WHERE id = '$id'",
        )
        val answered = answer(
            AnswerCloudPermissionBody("q1", "allow", "Thanks", listOf(CloudQuestionAnswerDto(0, text = "Blue"))),
        )
        assertEquals(HttpStatusCode.Accepted, answered.status)
        val text = Json.parseToJsonElement(answered.body<CloudInputDto>().text.orEmpty()).jsonObject
        assertEquals("allow", text.getValue("behavior").jsonPrimitive.content)
        assertEquals("Thanks", text.getValue("message").jsonPrimitive.content)
        val option = listOf(CloudQuestionAnswerDto(0, listOf(1)))
        val chosen = answer(AnswerCloudPermissionBody("q1", "allow", answers = option))
        assertEquals("permission_request_answered", chosen.type())

        suspend fun option(route: String, body: String, caller: String = "alice") = server.client.put("$path/$route") {
            bearerAuth(caller)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val bypass = option("permission-bypass", "{\"enabled\":true}").body<CloudSessionResponse>()
        assertEquals(true, bypass.permissionBypass)
        val administrator = option("permission-bypass", "{\"enabled\":false}", "dana-cli")
        assertEquals(HttpStatusCode.NotFound, administrator.status, "The owner's only")
        assertEquals("opus", option("model", "{\"model\":\"opus\"}").body<CloudSessionResponse>().model)
        assertEquals("low", option("effort", "{\"effort\":\"low\"}").body<CloudSessionResponse>().effort)
        val inputs = server.client.get("$path/messages") { bearerAuth("alice") }.body<CloudInputPageDto>().inputs
        assertEquals(listOf("set_effort", "set_model"), inputs.take(2).map { it.kind })
    }

    @Test
    fun nobodyElseReachesTheRoutes() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val path = server.conversation()
        val reads = listOf("events", "events/stream", "agents", "messages", "messages/${Uuid.random()}")
        for (route in reads) {
            assertEquals(HttpStatusCode.Unauthorized, server.client.get("$path/$route").status, route)
            for (stranger in listOf("bob", "carol")) {
                assertEquals(HttpStatusCode.NotFound, server.client.get("$path/$route") { bearerAuth(stranger) }.status)
            }
        }
        val writes = mapOf(
            "messages" to "{\"text\":\"Hi\"}",
            "interrupt" to "{}",
            "permissions" to "{\"requestId\":\"r1\",\"behavior\":\"deny\"}",
            "tasks/stop" to "{\"taskId\":\"b1\"}",
        )
        for ((route, body) in writes) {
            assertEquals(HttpStatusCode.Unauthorized, server.client.post("$path/$route").status, route)
            for (stranger in listOf("bob", "carol")) {
                val refused = server.client.post("$path/$route") {
                    bearerAuth(stranger)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.NotFound, refused.status, "$route $stranger")
            }
            assertEquals(HttpStatusCode.Forbidden, server.client.post("$path/$route") { bearerAuth("reader") }.status)
        }
        val options = mapOf(
            "permission-bypass" to "{\"enabled\":true}",
            "model" to "{\"model\":\"opus\"}",
            "effort" to "{\"effort\":\"low\"}",
        )
        for ((route, body) in options) {
            assertEquals(HttpStatusCode.Unauthorized, server.client.put("$path/$route").status, route)
            for (stranger in listOf("bob", "carol", "dana")) {
                val refused = server.client.put("$path/$route") {
                    bearerAuth(stranger)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.NotFound, refused.status, "$route $stranger")
            }
        }
        val unknown = "/api/v1/cloud/sessions/not-a-session/events"
        assertEquals(HttpStatusCode.NotFound, server.client.get(unknown) { bearerAuth("alice") }.status)
    }

    @Test
    fun theSweepRunsOnTheServer() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val path = server.conversation()
        val id = path.substringAfterLast('/')
        server.send(path, SendCloudMessageBody("Hi"))
        server.database.superuserOutsideTransaction("UPDATE runners_sessions SET state = 'terminated' WHERE id = '$id'")
        server.koin.get<CloudConversationMaintenance>().run()
        val inputs = server.client.get("$path/messages") { bearerAuth("alice") }.body<CloudInputPageDto>().inputs
        assertEquals("cancelled", inputs.single().status)
    }

    @Test
    fun aStreamEndsWithTheRefusal() {
        val losing = module {
            includes(testIdentities)
            single<CloudConversationService> {
                val real = CloudConversationServiceImpl(get(), get(), get(), get(), get())
                object : CloudConversationService by real {
                    override suspend fun follow(
                        caller: Caller,
                        id: Uuid,
                        after: Long,
                        emit: suspend (List<CloudEvent>) -> Unit,
                        idle: suspend () -> Unit,
                    ) = throw CloudSessionNotFoundException()
                }
            }
        }
        serverTest(listOf(RunnersModule), losing) { server ->
            val path = server.conversation()
            val text = server.client.get("$path/events/stream") { bearerAuth("alice") }.bodyAsText()
            assertTrue(text.endsWith("event: denied\ndata: {\"type\":\"not_found\"}\n\n"), text)
        }
    }
}
