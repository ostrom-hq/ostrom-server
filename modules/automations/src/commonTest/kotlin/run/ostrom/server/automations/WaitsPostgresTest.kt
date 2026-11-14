// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCase
import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.InvalidAssigneeException
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitEvents
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitSessionNotFoundException
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.RecordingEvents
import run.ostrom.server.automations.fakes.RecordingOrgEvents
import run.ostrom.server.automations.fakes.alice
import run.ostrom.server.automations.fakes.bob
import run.ostrom.server.automations.fakes.testIdentities
import run.ostrom.server.automations.infrastructure.NoMemberLogins
import run.ostrom.server.automations.infrastructure.inTenant
import run.ostrom.server.automations.presentation.AnswerWaitRequest
import run.ostrom.server.automations.presentation.CreateWaitRequest
import run.ostrom.server.automations.presentation.WaitResponse
import run.ostrom.server.automations.presentation.WaitsResponse
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: the repository's SQL and the routes. */
class WaitsPostgresTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")
    private val question = WaitSpec(
        "Review",
        body = "**Plan**",
        fields = listOf(WaitField("feedback", WaitFieldType.TEXT, "Feedback")),
        actions = listOf(WaitAction("approve", "Approve")),
    )

    private fun wait(
        session: String? = "s1",
        createdBy: String = BOB.toString(),
        key: String? = null,
        at: Instant = t0,
        org: Uuid = ORG_A,
    ) = Wait(Uuid.random(), org, "human_request", WaitStatus.OPEN, session, "r1", ALICE.toString(), createdBy, question,
        null, at, null, key)

    @Test
    fun theRepositoryStoresLimitsAndResolvesWaits() = serverTest(listOf(AutomationsModule)) { server ->
        val repository = server.koin.get<WaitsRepository>()
        val first = wait(key = "k1")
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(first, 2))
        assertEquals(first, repository.get(ORG_A, first.id))
        assertNull(repository.get(ORG_B, first.id))
        assertEquals(first, repository.findByIdempotencyKey(ORG_A, BOB.toString(), "k1"))
        assertNull(repository.findByIdempotencyKey(ORG_A, ALICE.toString(), "k1"))
        assertEquals(CreateOutcome.DUPLICATE, repository.createWithinLimit(wait(key = "k1"), 2))
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(wait(at = t0 + 1.minutes), 2))
        assertEquals(CreateOutcome.AT_LIMIT, repository.createWithinLimit(wait(), 2))
        val alone = wait(session = null, at = t0 + 2.minutes)
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(alone, 1))
        assertEquals(CreateOutcome.AT_LIMIT, repository.createWithinLimit(wait(session = null), 1))
        val other = wait(session = null, createdBy = "x", at = t0 - 1.minutes)
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(other, 1))
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(wait(org = ORG_B), 1))

        val values = mapOf("feedback" to JsonPrimitive("ok"), "n" to JsonPrimitive(2))
        val answer = WaitAnswer("approve", values, ALICE.toString(), t0 + 3.minutes)
        val resolved = repository.resolve(ORG_A, first.id, answer)
        val expected = first.copy(status = WaitStatus.RESOLVED, answer = answer, resolvedAt = answer.answeredAt)
        assertEquals(expected, resolved)
        assertNull(repository.resolve(ORG_A, first.id, answer))
        assertNull(repository.cancel(ORG_A, first.id, t0))
        val cancelled = repository.cancel(ORG_A, alone.id, t0 + 4.minutes)
        assertEquals(WaitStatus.CANCELLED, cancelled?.status)
        assertEquals(t0 + 4.minutes, cancelled?.resolvedAt)
        assertNull(cancelled?.answer)

        val all = repository.list(WaitFilter(ORG_A), null, 10)
        assertEquals(listOf(alone.id, first.id, other.id), all.map { it.id }.filter { it != all[1].id })
        assertEquals(all.drop(1), repository.list(WaitFilter(ORG_A), alone.id, 10))
        assertEquals(listOf(all[1].id), repository.list(open("s1"), null, 10).map { it.id })
        assertEquals(listOf(other.id), repository.list(WaitFilter(ORG_A, involving = "x"), null, 10).map { it.id })
        assertEquals(listOf(other.id), repository.list(WaitFilter(ORG_A, createdBy = "x"), null, 10).map { it.id })
        assertEquals(emptyList(), repository.list(WaitFilter(ORG_A, createdBy = ALICE.toString()), null, 10))
        assertEquals(4, repository.list(WaitFilter(ORG_A, assignee = ALICE.toString(), runId = "r1"), null, 10).size)
        assertEquals(emptyList(), repository.list(WaitFilter(ORG_A, assignee = "x"), null, 10))
    }

    @Test
    fun eachWaitAskedAnsweredOrWithdrawnIsHeardOnTheFeed() {
        val feed = RecordingOrgEvents()
        serverTest(listOf(AutomationsModule), module { single<OrgEventPublisher> { feed } }) { server ->
            val repository = server.koin.get<WaitsRepository>()
            val asked = wait(key = "e1")
            assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(asked, 5))
            val again = repository.createWithinLimit(wait(key = "e1"), 5)
            assertEquals(CreateOutcome.DUPLICATE, again, "A duplicate is not heard")
            val alone = wait(session = null).copy(assignee = "agent:x")
            assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(alone, 5))
            val values = mapOf("feedback" to JsonPrimitive("secret words"))
            repository.resolve(ORG_A, asked.id, WaitAnswer("approve", values, ALICE.toString(), t0))
            val twice = repository.resolve(ORG_A, asked.id, WaitAnswer("approve", emptyMap(), ALICE.toString(), t0))
            assertNull(twice, "Once")
            repository.cancel(ORG_A, alone.id, t0)
            val ended = wait(session = "s9")
            assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(ended, 5))
            repository.cancelOpenOfSessions(ORG_A, listOf("s9"), t0)

            assertEquals(
                listOf(
                    OrgEventType.WAIT_CREATED to asked.id,
                    OrgEventType.WAIT_CREATED to alone.id,
                    OrgEventType.WAIT_RESOLVED to asked.id,
                    OrgEventType.WAIT_CANCELLED to alone.id,
                    OrgEventType.WAIT_CREATED to ended.id,
                    OrgEventType.WAIT_CANCELLED to ended.id,
                ),
                feed.published.map { (_, event) -> event.type to Uuid.parse(event.subjectId) },
            )
            assertEquals(setOf(ORG_A), feed.published.map { it.first }.toSet())
            val (created, otherAssignee, resolved) = feed.published.map { it.second }
            assertEquals(ALICE, created.memberId, "The assignee's event")
            assertEquals(
                mapOf(
                    "waitId" to asked.id.toString(),
                    "sessionId" to "s1",
                    "waitKind" to "human_request",
                    "assigneeId" to ALICE.toString(),
                ),
                created.data.mapValues { it.value.jsonPrimitive.content },
            )
            assertNull(otherAssignee.memberId, "An assignee that is not a member id")
            assertNull(otherAssignee.data["sessionId"])
            assertEquals("approve", resolved.data["action"]?.jsonPrimitive?.content)
            assertFalse("secret words" in resolved.data.toString(), "Never the answer's values")
        }
    }

    @Test
    fun aListingNamesTheSessionsItReads() = serverTest(listOf(AutomationsModule)) { server ->
        val repository = server.koin.get<WaitsRepository>()
        val first = wait(session = "s-1", at = t0)
        val reopened = wait(session = "s-1~r2", at = t0 + 1.minutes)
        val resumed = wait(session = "s-1~r3", at = t0 + 2.minutes)
        for (wait in listOf(first, reopened, resumed)) {
            assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(wait, 5))
        }

        val listed = repository.list(WaitFilter(ORG_A, sessionIds = listOf("s-1", "s-1~r2")), null, 10).map { it.id }
        assertEquals(listOf(reopened.id, first.id), listed, "Only the sessions named")
        val one = repository.list(WaitFilter(ORG_A, sessionIds = listOf("s-1~r3")), null, 10).map { it.id }
        assertEquals(listOf(resumed.id), one)
        assertEquals(emptyList(), repository.list(WaitFilter(ORG_A, sessionIds = listOf("s-1~r")), null, 10))

        // The limit counts the sessions named with the wait's own: a launch's segments, not another launch's.
        val launch = listOf("s-1", "s-1~r2")
        assertEquals(CreateOutcome.AT_LIMIT, repository.createWithinLimit(wait(session = "s-1~r2"), 2, launch))
        assertEquals(CreateOutcome.AT_LIMIT, repository.createWithinLimit(wait(session = "s-1~r4"), 2, launch))
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(wait(session = "s-1~r4"), 2, listOf("s-1~r4")))
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(wait(session = "s-1~r3"), 2))
    }

    @Test
    fun aSessionThatEndsCancelsItsOpenWaitsInOneUpdate() = serverTest(listOf(AutomationsModule)) { server ->
        val repository = server.koin.get<WaitsRepository>()
        val first = wait(session = "s-1")
        val reopened = wait(session = "s-1~r2", at = t0 + 1.minutes)
        val answered = wait(session = "s-1~r2", at = t0 + 2.minutes)
        val other = wait(session = "s-2")
        val elsewhere = wait(session = "s-1", org = ORG_B)
        for (wait in listOf(first, reopened, answered, other, elsewhere)) repository.createWithinLimit(wait, 5)
        repository.resolve(ORG_A, answered.id, WaitAnswer("approve", emptyMap(), ALICE.toString(), t0))

        server.koin.get<SessionEndListener>().ended(ORG_A, listOf("s-1", "s-1~r2"))
        val cancelled = repository.list(WaitFilter(ORG_A, status = WaitStatus.CANCELLED), null, 10)
        assertEquals(setOf(first.id, reopened.id), cancelled.map { it.id }.toSet())
        assertTrue(cancelled.all { it.resolvedAt != null && it.answer == null })
        assertEquals(WaitStatus.RESOLVED, repository.get(ORG_A, answered.id)?.status)
        assertEquals(WaitStatus.OPEN, repository.get(ORG_A, other.id)?.status)
        assertEquals(WaitStatus.OPEN, repository.get(ORG_B, elsewhere.id)?.status)
        assertEquals(emptyList(), repository.cancelOpenOfSessions(ORG_A, listOf("s-1", "s-1~r2"), t0), "Once")
    }

    @Test
    fun anAnswerInASessionIsTakenOnceOldestFirst() = serverTest(listOf(AutomationsModule)) { server ->
        val repository = server.koin.get<WaitsRepository>()
        val first = wait(session = "s-1")
        val second = wait(session = "s-1~r2", at = t0 + 1.minutes)
        val alone = wait(session = null)
        val elsewhere = wait(session = "s-1", org = ORG_B)
        for (wait in listOf(first, second, alone, elsewhere)) repository.createWithinLimit(wait, 5)
        val segments = listOf("s-1", "s-1~r2")
        assertTrue(repository.hasOpen(ORG_A, segments))
        assertEquals(emptyList(), repository.takeAnswered(ORG_A, segments, 10))

        fun answer(at: Instant) = WaitAnswer("approve", emptyMap(), ALICE.toString(), at)
        repository.resolve(ORG_A, second.id, answer(t0 + 2.minutes))
        repository.resolve(ORG_A, first.id, answer(t0 + 3.minutes))
        repository.resolve(ORG_A, alone.id, answer(t0 + 1.minutes))
        repository.resolve(ORG_B, elsewhere.id, answer(t0 + 1.minutes))
        assertFalse(repository.hasOpen(ORG_A, segments))
        assertEquals(listOf(second.id), repository.takeAnswered(ORG_A, segments, 1).map { it.id }, "Oldest answer")
        assertEquals(listOf(first.id), repository.takeAnswered(ORG_A, segments, 10).map { it.id })
        assertEquals(emptyList(), repository.takeAnswered(ORG_A, segments, 10), "Once")
        assertEquals(listOf(elsewhere.id), repository.takeAnswered(ORG_B, segments, 10).map { it.id })
    }

    private fun open(session: String) = WaitFilter(ORG_A, status = WaitStatus.OPEN, sessionId = session)

    @Test
    fun withoutAMemberDirectoryAWaitIsOnlyAskedOfItsAsker() = serverTest(listOf(AutomationsModule)) { server ->
        val ask = server.koin.get<AskWaitUseCase>()
        val toAlice = AskWait(question, assignee = ALICE.toString())
        assertFailsWith<InvalidAssigneeException> { ask.ask(bob, toAlice, null) }
        val own = ask.ask(bob, AskWait(question), null).wait
        val answers = server.koin.get<AnswerWaitUseCase>()
        assertEquals(WaitStatus.RESOLVED, answers.answer(bob, own.id, "approve", emptyMap()).status)
        val other = ask.ask(bob, AskWait(question), null).wait
        assertEquals(WaitStatus.CANCELLED, answers.cancel(bob, other.id).status)
        // Nor by username, nor in a session: without the orgs and sessions modules no one is named nor known.
        assertFailsWith<InvalidAssigneeException> { ask.ask(bob, AskWait(question, assignee = "alice"), null) }
        assertFailsWith<WaitSessionNotFoundException> { ask.ask(bob, AskWait(question, sessionId = "s1"), null) }
        assertEquals(emptyMap(), NoMemberLogins.loginsOf(ORG_A, listOf(BOB)))
    }

    private suspend fun HttpClient.ask(token: String, request: CreateWaitRequest, key: String? = null): HttpResponse =
        post("/api/v1/waits") {
            bearerAuth(token)
            if (key != null) header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.answer(token: String, id: String, request: AnswerWaitRequest): HttpResponse =
        post("/api/v1/waits/$id/answer") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    @Test
    fun theApiAsksListsAnswersAndCancels() {
        val events = RecordingEvents()
        val overrides = module {
            includes(testIdentities)
            single<WaitEvents> { events }
        }
        serverTest(listOf(AutomationsModule), overrides) { server ->
            // A session's end tells the bound events of what it cancels (none here).
            server.koin.get<SessionEndListener>().ended(ORG_A, listOf("s-none"))
            assertTrue(events.seen.isEmpty())
            val client = server.client
            val request = CreateWaitRequest(question, sessionId = "s1", assignee = ALICE.toString())
            val created = client.ask("agent", request, key = "k1")
            assertEquals(HttpStatusCode.Created, created.status)
            val wait = created.body<WaitResponse>()
            assertEquals("open", wait.status)
            assertEquals(BOB.toString(), wait.createdBy)
            val replay = client.ask("agent", request, key = "k1")
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["Idempotency-Replayed"])
            assertEquals(wait, replay.body<WaitResponse>())

            val mine = client.get("/api/v1/waits?mine=true") { bearerAuth("alice") }.body<WaitsResponse>()
            assertEquals(listOf(wait.id), mine.items.map { it.id })
            assertNull(mine.next)
            val asked = client.get("/api/v1/waits?sessionId=s1&limit=1") { bearerAuth("reader") }.body<WaitsResponse>()
            assertEquals(listOf(wait.id), asked.items.map { it.id })
            assertEquals(wait, client.get("/api/v1/waits/${wait.id}") { bearerAuth("alice") }.body<WaitResponse>())

            assertEquals(HttpStatusCode.Forbidden, client.answer("agent", wait.id, AnswerWaitRequest("approve")).status)
            val approve = AnswerWaitRequest("approve")
            assertEquals(HttpStatusCode.Forbidden, client.answer("writer", wait.id, approve).status)
            assertEquals(HttpStatusCode.Forbidden, client.answer("davekey", wait.id, approve).status)
            // S2-52: a personal token holding waits:write is still not a person answering.
            val byToken = client.answer("alicekey", wait.id, approve)
            assertEquals(HttpStatusCode.Forbidden, byToken.status)
            assertEquals("forbidden", byToken.body<ApiError>().error.type)
            assertEquals(HttpStatusCode.BadRequest, client.answer("alice", wait.id, AnswerWaitRequest("reject")).status)
            val values = mapOf("feedback" to JsonPrimitive("Go"))
            val answered = client.answer("alice", wait.id, AnswerWaitRequest("approve", values))
            assertEquals(HttpStatusCode.OK, answered.status)
            val resolved = answered.body<WaitResponse>()
            assertEquals("resolved", resolved.status)
            assertEquals("Go", resolved.answer?.values?.get("feedback")?.let { (it as JsonPrimitive).content })
            assertEquals(ALICE.toString(), resolved.answer?.author)
            assertEquals(resolved, client.answer("aliceapp", wait.id, AnswerWaitRequest("approve", values)).body())
            assertEquals(HttpStatusCode.Conflict, client.answer("dave", wait.id, AnswerWaitRequest("approve")).status)

            val other = client.ask("bob", CreateWaitRequest(question)).body<WaitResponse>()
            assertEquals(BOB.toString(), other.assignee)
            val cancel = client.post("/api/v1/waits/${other.id}/cancel") { bearerAuth("agent") }
            assertEquals("cancelled", cancel.body<WaitResponse>().status)
            val again = client.post("/api/v1/waits/${other.id}/cancel") { bearerAuth("bob") }
            assertEquals(HttpStatusCode.Conflict, again.status)
            val history = client.get("/api/v1/waits?status=all&limit=1") { bearerAuth("bob") }.body<WaitsResponse>()
            assertEquals(listOf(other.id), history.items.map { it.id })
            assertEquals(other.id, history.next)
            val rest = client.get("/api/v1/waits?status=all&before=${history.next}") { bearerAuth("bob") }
            assertEquals(listOf(wait.id), rest.body<WaitsResponse>().items.map { it.id })
            assertEquals(listOf("created", "resolved", "created", "cancelled"), events.seen.map { it.first })
        }
    }

    @Test
    fun anotherOrganizationsWaitsAreNeitherSeenNorWritten() = serverTest(listOf(AutomationsModule)) { server ->
        val repository = server.koin.get<WaitsRepository>()
        assertEquals(CreateOutcome.CREATED, repository.createWithinLimit(wait(), 10))
        suspend fun count(org: Uuid) = server.driver.inTenant(org) {
            fetchAll(Statement.create("SELECT COUNT(*) AS n FROM automations_waits")).getOrThrow().first().get("n")
                .asLong()
        }
        assertEquals(1L, count(ORG_A))
        // No WHERE org_id: the policy alone hides organization A's waits.
        assertEquals(0L, count(ORG_B))
        // A write for another organization is refused.
        assertFailsWith<Exception> {
            server.driver.inTenant(ORG_B) {
                execute(
                    Statement.create(
                        "INSERT INTO automations_waits (id, org_id, kind, status, assignee, created_by, question, " +
                            "created_at) VALUES (:id, :org, 'k', 'open', 'a', 'b', '{}', now())",
                    ).bind("id", Uuid.random()).bind("org", ORG_A),
                ).getOrThrow()
            }
        }
        // B's update reaches none of A's waits.
        val touched = server.driver.inTenant(ORG_B) {
            execute(Statement.create("UPDATE automations_waits SET status = 'cancelled'")).getOrThrow()
        }
        assertEquals(0L, touched)
    }

    @Test
    fun refusalsMapToStatuses() = serverTest(listOf(AutomationsModule), testIdentities) { server ->
        val client = server.client
        val wait = client.ask("bob", CreateWaitRequest(question)).body<WaitResponse>()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/waits").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/waits") { bearerAuth("cloud") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.ask("reader", CreateWaitRequest(question)).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/waits") { bearerAuth("nobody") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/waits/${wait.id}") { bearerAuth("alice") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/waits/${wait.id}") { bearerAuth("carol") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/waits/nope") { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/waits?before=nope") { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/waits?status=done") { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/waits?assignee=all") { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/waits?assignee=all") { bearerAuth("dave") }.status)
        val stranger = CreateWaitRequest(question, assignee = "00000000-0000-0000-0000-000000000000")
        assertEquals(HttpStatusCode.BadRequest, client.ask("bob", stranger).status)
        assertEquals(HttpStatusCode.BadRequest, client.ask("bob", CreateWaitRequest(question.copy(title = ""))).status)
        assertEquals(HttpStatusCode.BadRequest, client.ask("bob", CreateWaitRequest(question), key = "a b").status)
        val malformed = client.post("/api/v1/waits") {
            bearerAuth("bob")
            contentType(ContentType.Application.Json)
            setBody("""{"question":{"title":"x","fields":[{"id":"a","type":"secret","label":"S"}]}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }
}
