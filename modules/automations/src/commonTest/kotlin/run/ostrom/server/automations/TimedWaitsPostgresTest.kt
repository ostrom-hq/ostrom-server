// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.DueWait
import run.ostrom.server.automations.domain.ResolveDueWaitsUseCase
import run.ostrom.server.automations.domain.TimedWaitsRepository
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitEventTarget
import run.ostrom.server.automations.domain.WaitEvents
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitResolver
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitTimer
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.FakeWaitAgents
import run.ostrom.server.automations.fakes.LAUNCH_SESSION
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.RecordingEvents
import run.ostrom.server.automations.fakes.RecordingOrgEvents
import run.ostrom.server.automations.fakes.testIdentities
import run.ostrom.server.automations.presentation.WaitsResponse
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The waits that resolve by themselves against a real PostgreSQL: the agent's `wait_for`, the event address (no
 * sign-in, the secret is the proof, every failure a 404), the clock across organizations, and the wake.
 */
class TimedWaitsPostgresTest {
    private val withAgents = module {
        includes(testIdentities)
        single<WaitAgentResolver> { FakeWaitAgents }
    }

    private suspend fun HttpClient.waitFor(
        arguments: String,
        path: String = OWN,
        token: String = "launch",
    ): JsonObject {
        val response = post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                    """"params":{"name":"wait_for","arguments":$arguments}}""",
            )
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonObject
    }

    private fun JsonObject.text(): String =
        getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError(): Boolean = getValue("isError").jsonPrimitive.boolean

    private fun JsonObject.field(name: String): String? = this[name]?.jsonPrimitive?.content

    private suspend fun HttpClient.event(
        url: String,
        body: String? = null,
        header: String? = null,
    ): HttpResponse = post(url) {
        header?.let { header("Ostrom-Wait-Token", it) }
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

    private suspend fun HttpClient.eventWait(): JsonObject =
        Json.parseToJsonElement(waitFor("""{"title":"CI of #42","event":{"source":"webhook"},"timeout":3600}""").text())
            .jsonObject

    @Test
    fun anAgentWaitsForATimeAndTheClockWakesItsSession() {
        val events = RecordingEvents()
        val overrides = module {
            includes(withAgents)
            single<WaitEvents> { events }
        }
        serverTest(listOf(AutomationsModule), overrides) { server -> timeComes(server, events) }
    }

    private suspend fun timeComes(server: TestServer, events: RecordingEvents) {
        val client = server.client
        val asked = client.waitFor("""{"title":"Check the deploy","after":30,"resumeMessage":"Look at #42"}""")
        assertFalse(asked.isError(), asked.text())
        val result = Json.parseToJsonElement(asked.text()).jsonObject
        val id = Uuid.parse(result.field("id").orEmpty())
        assertEquals("open", result.field("status"))
        assertTrue(result.field("dueAt") != null && result.field("eventUrl") == null, asked.text())
        assertTrue("End your turn now" in result.field("instruction").orEmpty())

        val repository = server.koin.get<WaitsRepository>()
        val wait = repository.get(ORG_A, id)
        assertEquals(WaitResolver.TIME, wait?.resolver)
        assertEquals("Look at #42", wait?.timer?.resumeMessage)
        assertEquals(LAUNCH_SESSION, wait?.sessionId)
        val pass = server.koin.get<ResolveDueWaitsUseCase>()
        assertEquals(0, pass.pass(), "Not due yet")
        // As if 30 s had passed: the instant is moved back.
        server.database.superuser.execute("UPDATE automations_waits SET due_at = due_at - INTERVAL '1 minute'")
            .getOrThrow()
        assertEquals(1, pass.pass())
        assertEquals(0, pass.pass(), "Once")
        assertTrue("resolved" to id in events.seen, "The bound events hear of it")

        val woken = client.post("/api/v1/waits/wake?session=$LAUNCH_SESSION") {
            bearerAuth("launch")
            contentType(ContentType.Application.Json)
            setBody("""{"waitMillis":0}""")
        }
        val message = Json.parseToJsonElement(woken.bodyAsText()).jsonObject.getValue("messages").jsonArray.single()
        assertEquals(
            "[Ostrom wait answered] Check the deploy\nwait: $id\naction: the time came (time_reached)\n" +
                "resolved by: Ostrom (the clock)\nresume: Look at #42",
            message.jsonPrimitive.content,
        )
    }

    @Test
    fun anEventAddressResolvesItsWaitOnceWithItsSecretOnly() =
        serverTest(listOf(AutomationsModule), withAgents) { server ->
            val client = server.client
            val created = client.eventWait()
            val url = created.field("eventUrl").orEmpty()
            val id = created.field("id").orEmpty()
            assertTrue(url.startsWith("/api/v1/waits/$id/event?token=wev_"), url)
            assertTrue(created.field("timeoutAt") != null)
            val secret = url.substringAfter("token=")
            val stored = server.database.superuser.fetchAll(
                "SELECT event_secret_hash FROM automations_waits WHERE id = '$id'",
            ).getOrThrow().single().get("event_secret_hash").asString()
            assertFalse(secret in stored, "Only its hash is kept")

            val bare = "/api/v1/waits/$id/event"
            for (refused in listOf(
                client.event(bare),
                client.event("$bare?token=wev_wrong"),
                client.event(bare, header = "wev_wrong"),
                client.event("/api/v1/waits/${Uuid.random()}/event?token=$secret"),
                client.event("/api/v1/waits/nope/event?token=$secret"),
            )) {
                assertEquals(HttpStatusCode.NotFound, refused.status)
                assertEquals("not_found", refused.body<ApiError>().error.type)
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, client.event(url, "x".repeat(16 * 1024 + 1)).status)
            assertEquals(HttpStatusCode.BadRequest, client.event(url, """{"url":"http://ci"}""").status)

            val received = client.event(bare, """{"result":"failure","note":"2 failed"}""", header = secret)
            assertEquals(HttpStatusCode.Accepted, received.status, received.bodyAsText())
            assertEquals("""{"id":"$id","status":"resolved"}""", received.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.event(url).status, "A wait no longer open")
            val wait = server.koin.get<WaitsRepository>().get(ORG_A, Uuid.parse(id))
            assertEquals(JsonPrimitive("2 failed"), wait?.answer?.values?.get("note"))

            // The query, too, holds the secret.
            val second = client.eventWait()
            assertEquals(HttpStatusCode.Accepted, client.event(second.field("eventUrl").orEmpty()).status)
        }

    @Test
    fun anEventAddressTakesThirtyCallsAMinute() = serverTest(listOf(AutomationsModule), withAgents) { server ->
        val client = server.client
        val url = client.eventWait().field("eventUrl").orEmpty()
        val wrong = url.substringBefore("?")
        repeat(30) { assertEquals(HttpStatusCode.NotFound, client.event(wrong).status) }
        val limited = client.event(url)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("wait_event_rate_limit", limited.body<ApiError>().error.type)
    }

    @Test
    fun nobodyAnswersItAndItIsListedOnlyWhenAskedFor() = serverTest(listOf(AutomationsModule), withAgents) { server ->
        val client = server.client
        val id = client.eventWait().field("id").orEmpty()
        val answered = client.post("/api/v1/waits/$id/answer") {
            bearerAuth("bob")
            contentType(ContentType.Application.Json)
            setBody("""{"action":"done"}""")
        }
        assertEquals(HttpStatusCode.Conflict, answered.status)
        assertEquals("resolves_by_itself", answered.body<ApiError>().error.type)

        suspend fun listed(query: String) = client.get("/api/v1/waits$query") { bearerAuth("bob") }
        assertEquals(emptyList(), listed("").body<WaitsResponse>().items, "Not in Waiting for you")
        val events = listed("?resolver=event").body<WaitsResponse>().items
        assertEquals(listOf(id), events.map { it.id })
        assertEquals("event", events.single().resolver)
        assertEquals("webhook", events.single().eventSource)
        assertNull(events.single().eventUrl, "The address is shown once")
        assertEquals(1, listed("?resolver=all").body<WaitsResponse>().items.size)
        assertEquals(HttpStatusCode.BadRequest, listed("?resolver=clock").status)
        val read = client.get("/api/v1/waits/$id") { bearerAuth("bob") }.bodyAsText()
        assertTrue("\"timeoutAt\"" in read && "eventUrl" !in read && "wev_" !in read, read)

        val mcpList = client.post(OWN) {
            bearerAuth("launch")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_waits","arguments":{}}}""")
        }.bodyAsText()
        assertTrue("CI of #42  (event)" in mcpList, mcpList)
        val cancelled = client.post(OWN) {
            bearerAuth("launch")
            contentType(ContentType.Application.Json)
            setBody(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"cancel_wait","arguments":""" +
                    """{"wait":"$id"}}}""",
            )
        }.bodyAsText()
        assertTrue("Cancelled wait $id." in cancelled, cancelled)
        assertEquals(0, server.koin.get<ResolveDueWaitsUseCase>().pass(), "A cancelled wait never comes due")
    }

    @Test
    fun onlyAnAgentAsksAWaitThatResolvesByItself() = serverTest(listOf(AutomationsModule), withAgents) { server ->
        val client = server.client
        val rest = client.post("/api/v1/waits") {
            bearerAuth("bob")
            contentType(ContentType.Application.Json)
            setBody("""{"question":{"title":"CI"},"resolver":"time","after":60}""")
        }
        assertEquals(HttpStatusCode.Forbidden, rest.status)
        val human = client.post("/api/v1/waits") {
            bearerAuth("bob")
            contentType(ContentType.Application.Json)
            setBody("""{"question":{"title":"CI"},"after":60}""")
        }
        assertEquals(HttpStatusCode.BadRequest, human.status)
        val member = client.waitFor("""{"title":"CI","after":60}""", path = "/api/v1/waits/mcp", token = "bob")
        assertTrue(member.isError() && "Only an agent" in member.text(), member.text())
        assertTrue(client.waitFor("""{"title":"CI"}""").isError())
        assertTrue(client.waitFor("""{"title":"CI","after":60,"at":"2026-11-14T09:00:00Z"}""").isError())
        val timeout = client.waitFor("""{"title":"CI","after":60,"timeout":120}""")
        assertTrue(timeout.isError() && "timeout belongs to an event wait" in timeout.text(), timeout.text())
        assertTrue(client.waitFor("""{"title":"CI","after":10}""").isError())
        assertTrue(client.waitFor("""{"title":"CI","event":{"source":"github"}}""").isError())
        val at = client.waitFor("""{"title":"CI","at":"${Clock.System.now() + 10.minutes}"}""")
        assertFalse(at.isError(), at.text())
    }

    private val feed = RecordingOrgEvents()

    @Test
    fun theRepositoryFindsDueWaitsAcrossOrganizationsAndResolvesEachOnce() =
        serverTest(listOf(AutomationsModule), module { single<OrgEventPublisher> { feed } }) { server ->
            val repository = server.koin.get<WaitsRepository>()
            val timedWaits = server.koin.get<TimedWaitsRepository>()
            val t0 = Instant.parse("2026-11-13T10:00:00Z")
            fun timed(org: Uuid, resolver: WaitResolver, at: Instant, hash: String? = null) = Wait(
                Uuid.random(), org, "timed", WaitStatus.OPEN, "s1", null, BOB.toString(), BOB.toString(),
                WaitSpec("CI"), null, t0, null, null,
                WaitTimer(resolver, at, "webhook".takeIf { hash != null }, "resume", hash),
            )
            val time = timed(ORG_A, WaitResolver.TIME, t0 + 1.minutes)
            val event = timed(ORG_B, WaitResolver.EVENT, t0 + 2.minutes, "abc")
            val later = timed(ORG_A, WaitResolver.TIME, t0 + 10.minutes)
            for (wait in listOf(time, event, later)) {
                assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(wait, 5))
            }
            assertEquals(time, repository.get(ORG_A, time.id), "Read back as stored")
            assertEquals(event, repository.get(ORG_B, event.id))

            val due = timedWaits.due(t0 + 5.minutes, 50)
            val expected =
                listOf(DueWait(ORG_A, time.id, WaitResolver.TIME), DueWait(ORG_B, event.id, WaitResolver.EVENT))
            assertEquals(expected, due)
            assertEquals(listOf(time.id), timedWaits.due(t0 + 5.minutes, 1).map { it.id })
            assertEquals(WaitEventTarget(ORG_B, "abc"), timedWaits.eventTarget(event.id))
            assertNull(timedWaits.eventTarget(time.id), "Only an event has an address")
            assertNull(timedWaits.eventTarget(Uuid.random()))

            val answer = WaitAnswer("time_reached", emptyMap(), "ostrom", t0 + 5.minutes)
            assertNull(timedWaits.settle(ORG_B, time.id, answer, due = true), "Confined to its organization")
            assertNull(timedWaits.settle(ORG_A, later.id, answer, due = true), "Not due")
            assertNull(timedWaits.settle(ORG_A, time.id, answer, due = false), "A time takes no event")
            val resolved = timedWaits.settle(ORG_A, time.id, answer, due = true)
            assertEquals(WaitStatus.RESOLVED, resolved?.status)
            assertEquals(answer, resolved?.answer)
            assertNull(timedWaits.settle(ORG_A, time.id, answer, due = true), "Once")
            assertEquals(listOf(time.id), repository.takeAnswered(ORG_A, listOf("s1"), 10).map { it.id })

            val received = WaitAnswer("event_received", mapOf("result" to JsonPrimitive("success")), "ostrom", t0)
            assertEquals(WaitStatus.RESOLVED, timedWaits.settle(ORG_B, event.id, received, due = false)?.status)
            assertNull(timedWaits.eventTarget(event.id), "Closed")
            val settled = feed.published.filter { it.second.type == OrgEventType.WAIT_RESOLVED }
            assertEquals(listOf(ORG_A to time.id.toString(), ORG_B to event.id.toString()), settled.map { it.first to it.second.subjectId })
            assertEquals(listOf("time_reached", "event_received"), settled.map { it.second.data["action"]?.jsonPrimitive?.content })
            assertEquals(emptyList(), timedWaits.due(t0 + 5.minutes, 50))
            val timedOnly = WaitFilter(ORG_A, resolver = WaitResolver.TIME)
            assertEquals(setOf(time.id, later.id), repository.list(timedOnly, null, 10).map { it.id }.toSet())
            assertEquals(emptyList(), repository.list(WaitFilter(ORG_A, resolver = WaitResolver.HUMAN), null, 10))
        }

    private companion object {
        const val OWN = "/api/v1/waits/mcp?session=$LAUNCH_SESSION"
    }
}
