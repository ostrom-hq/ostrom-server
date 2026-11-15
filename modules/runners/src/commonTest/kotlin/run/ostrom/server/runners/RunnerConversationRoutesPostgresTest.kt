// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.ConversationResume
import run.ostrom.server.runners.domain.RunnerAgentConfig
import run.ostrom.server.runners.domain.RunnerOwner
import run.ostrom.server.runners.presentation.RunnerOwnerDto
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudInputKind
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.NewInput
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TurnMarker
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.RunnerAgentResponse
import run.ostrom.server.runners.presentation.RunnerEventDto
import run.ostrom.server.runners.presentation.RunnerEventsResponse
import run.ostrom.server.runners.presentation.RunnerInputsResponse
import run.ostrom.server.runners.presentation.toDomain
import run.ostrom.server.runners.presentation.toResponse
import run.ostrom.server.runners.presentation.turnOf
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The runner's agent channel over real HTTP and PostgreSQL: its credential, its fence, and a whole exchange. */
class RunnerConversationRoutesPostgresTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    private suspend fun TestServer.running(): Pair<CloudSession, String> {
        val tokens = koin.get<RunnerTokens>()
        val token = tokens.mint()
        val created = session(state = SessionState.RUNNING, createdAt = t0)
            .copy(mode = SessionMode.CONVERSATION, epoch = 2, runnerTokenHash = tokens.hash(token))
            .copy(executionStartedAt = t0)
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(created, 100))
        return created to token
    }

    private suspend fun TestServer.call(path: String, auth: String?, body: String): HttpResponse =
        client.post("/api/v1/cloud/runner/$path") {
            auth?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.type(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type").toString()
            .trim('"')

    private fun data(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theRunnerOfTheCurrentExecutionTalksWithItsAgent() = serverTest(listOf(RunnersModule)) { server ->
        val (session, token) = server.running()
        val claim = """"sessionId":"${session.id}","epoch":2"""
        val agent = server.call("agent", token, "{$claim}")
        assertEquals(HttpStatusCode.OK, agent.status)
        val config = agent.body<RunnerAgentResponse>()
        assertEquals("claude" to "none", config.kind to config.conversation)
        assertTrue(config.permissionRelay && config.liveText && config.backgroundTasks)

        val events = """{$claim,"events":[""" +
            """{"sequence":1,"type":"status","data":{"conversation":"resumable"}},""" +
            """{"sequence":2,"type":"message.agent","data":{"text":"key sk-ant-api03-""" + "a".repeat(40) +
            """","thinking":"hidden"}}]}"""
        val stored = server.call("events", token, events)
        assertEquals(HttpStatusCode.OK, stored.status)
        assertEquals(RunnerEventsResponse(2, 2), stored.body<RunnerEventsResponse>())
        val said = server.koin.get<CloudConversationStore>().events(ORG_A, session.id, null, null, 10, null).events
        assertEquals(listOf(CloudEventType.STATUS, CloudEventType.AGENT_MESSAGE), said.map { it.type })
        assertTrue("sk-ant-api03-aaaa" !in said[1].data, "Masked before it is stored")
        assertTrue("hidden" !in said[1].data, "No reasoning stored")
        assertEquals("resumable", server.call("agent", token, "{$claim}").body<RunnerAgentResponse>().conversation)

        val message = CloudInput(
            Uuid.random(), ORG_A, session.id, CloudInputKind.MESSAGE, CloudInputStatus.QUEUED, "alice", null, "Go on",
            false, null, null, t0,
        )
        val idle = server.call("inputs", token, """{$claim,"waitMillis":600}""")
        assertTrue(idle.body<RunnerInputsResponse>().inputs.isEmpty(), "Waited, for nothing")
        server.koin.get<CloudConversationStore>().insertInput(NewInput(message, null), 100)
        val polled = server.call("inputs", token, """{$claim,"desiredStatus":"running","acceptsFiles":true}""")
        assertEquals(HttpStatusCode.OK, polled.status)
        val inputs = polled.body<RunnerInputsResponse>()
        assertEquals("running", inputs.desiredStatus)
        assertEquals(listOf(message.id.toString() to 1), inputs.inputs.map { it.id to it.attempt })
        assertEquals("Go on", inputs.inputs.single().text)

        val ack = """{$claim,"acks":[{"id":"${message.id}","status":"consumed"}]}"""
        assertEquals(HttpStatusCode.NoContent, server.call("inputs/ack", token, ack).status)
        val consumed = server.koin.get<CloudConversationStore>().input(ORG_A, session.id, message.id)
        assertEquals(CloudInputStatus.CONSUMED, consumed?.status)
    }

    @Test
    fun anotherCredentialOrExecutionIsFencedAndABadBodyRefused() = serverTest(listOf(RunnersModule)) { server ->
        val (session, token) = server.running()
        val claim = """"sessionId":"${session.id}","epoch":2"""
        for (path in listOf("agent", "events", "inputs", "inputs/ack")) {
            assertEquals(HttpStatusCode.Unauthorized, server.call(path, null, "{$claim}").status, path)
            val unknown = server.call(path, "ostrom_runner_wrong", "{not json")
            assertEquals(HttpStatusCode.Conflict to "execution_fenced", unknown.status to unknown.type(), path)
        }
        val stale = server.call("agent", token, """{"sessionId":"${session.id}","epoch":1}""")
        assertEquals(HttpStatusCode.Conflict to "execution_fenced", stale.status to stale.type())
        val elsewhere = server.call("agent", token, """{"sessionId":"${Uuid.random()}","epoch":2}""")
        assertEquals(HttpStatusCode.Conflict, elsewhere.status)
        val malformed = server.call("agent", token, """{"sessionId":"nope","epoch":2}""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals(HttpStatusCode.BadRequest, server.call("agent", token, "{not json").status)
        val padded = """{$claim,"pad":"${"x".repeat(5000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, server.call("agent", token, padded).status)

        val input = """{$claim,"events":[{"sequence":1,"type":"input","data":{}}]}"""
        val unknownType = server.call("events", token, input)
        assertEquals(HttpStatusCode.BadRequest to "invalid_events", unknownType.status to unknownType.type())
        val badStatus = server.call("inputs/ack", token, """{$claim,"acks":[{"id":"${Uuid.random()}","status":"x"}]}""")
        assertEquals(HttpStatusCode.BadRequest to "invalid_acks", badStatus.status to badStatus.type())
        val badId = server.call("inputs/ack", token, """{$claim,"acks":[{"id":"nope","status":"rejected"}]}""")
        assertEquals(HttpStatusCode.BadRequest to "invalid_acks", badId.status to badId.type())
        val rejected = """{$claim,"acks":[{"id":"${Uuid.random()}","status":"rejected",""" +
            """"reason":"files_unsupported"}]}"""
        assertEquals(HttpStatusCode.NoContent, server.call("inputs/ack", token, rejected).status)
        val asleep = server.call("inputs", token, """{$claim,"desiredStatus":"asleep"}""")
        assertEquals(HttpStatusCode.BadRequest to "invalid_state", asleep.status to asleep.type())
    }

    @Test
    fun theAgentsOwnerIsShownWhenKnown() {
        val config = RunnerAgentConfig(
            AgentKind.CODEX, null, null, ConversationResume.NONE, false, 0, false, false, RunnerOwner("ada", null),
        )
        assertEquals(RunnerOwnerDto("ada"), config.toResponse().owner)
        assertNull(config.copy(owner = null).toResponse().owner)
    }

    @Test
    fun anEventIsReadForWhatItsTurnSays() {
        assertEquals(TurnMarker.STARTED, turnOf(CloudEventType.TURN, data("""{"phase":"started"}""")))
        assertEquals(TurnMarker.ENDED, turnOf(CloudEventType.TURN, data("""{"phase":"interrupted"}""")))
        assertNull(turnOf(CloudEventType.TURN, data("""{"phase":"thinking"}""")))
        assertNull(turnOf(CloudEventType.TURN, data("""{"phase":{}}""")))
        assertNull(turnOf(CloudEventType.STATUS, data("""{"phase":"started"}""")))
        val event = RunnerEventDto(3, "turn", data("""{"phase":"completed"}""")).toDomain()
        assertEquals(Triple(3L, CloudEventType.TURN, TurnMarker.ENDED), Triple(event.sequence, event.type, event.turn))
        val unknown = assertFailsWith<CloudConversationException> { RunnerEventDto(1, "nope", data("{}")).toDomain() }
        assertEquals("invalid_events", unknown.type)
    }
}
