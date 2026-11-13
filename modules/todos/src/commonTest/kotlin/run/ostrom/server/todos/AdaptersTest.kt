// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.LegacyBlob
import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCase
import run.ostrom.server.automations.domain.AskedWait
import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitRef
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionQuery
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.sessions.domain.repositories.SessionsTransaction
import run.ostrom.server.sessions.domain.repositories.StaleSession
import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoItemDefinition
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoOrigin
import run.ostrom.server.todos.domain.TodoOwner
import run.ostrom.server.todos.domain.TodoSession
import run.ostrom.server.todos.domain.TodoWaitState
import run.ostrom.server.todos.fakes.ADA
import run.ostrom.server.todos.fakes.BEN
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.item
import run.ostrom.server.todos.infrastructure.ArtifactsTodoArtifacts
import run.ostrom.server.todos.infrastructure.AutomationsTodoWaits
import run.ostrom.server.todos.infrastructure.SessionsTodoSessions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The adapters over the sessions, automations (waits) and artifacts modules, on hand-written fakes of their ports. */
class AdaptersTest {
    private val t0 = Instant.parse("2026-11-13T14:02:00Z")

    private fun segment(id: String, n: Int, dev: String) =
        Session(ORG_A, id, "s1", n, agent = "claude-code", dev = dev, startedAt = t0)

    private class Sessions(private val segments: List<Session>) : SessionsRepository {
        override suspend fun <T> transaction(orgId: Uuid, block: suspend SessionsTransaction.() -> T): T = unused()
        override suspend fun get(orgId: Uuid, id: String): Session? = unused()
        override suspend fun segmentsOf(orgId: Uuid, id: String): List<Session> =
            if (segments.any { it.id == id }) segments else emptyList()
        override suspend fun list(orgId: Uuid, query: SessionQuery): List<Session> = unused()
        override suspend fun stale(seenBefore: Instant, limit: Int): List<StaleSession> = unused()
    }

    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            mapOf("ada" to Uuid.parse(ADA)).filterKeys { it in logins }
        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = unused()
    }

    @Test
    fun aSessionIsFoundByAnyOfItsSegmentsWithItsPerson() = runTest {
        val segments = listOf(segment("s1", 1, "ada"), segment("s1~r2", 2, "ada"))
        val sessions = SessionsTodoSessions(Sessions(segments), logins)
        val expected = TodoSession("s1", listOf("s1", "s1~r2"), owned = true)
        assertEquals(expected, sessions.find(ORG_A, "s1~r2", ADA))
        assertEquals(expected.copy(owned = false), sessions.find(ORG_A, "s1", BEN))
        assertNull(sessions.find(ORG_A, "ghost", ADA))
        val unknown = SessionsTodoSessions(Sessions(listOf(segment("s1", 1, "nobody"))), logins)
        assertFalse(unknown.find(ORG_A, "s1", ADA)!!.owned, "a login no member has owns nothing")
    }

    private class Waits : WaitsRepository {
        val stored = mutableMapOf<Uuid, Wait>()
        val filters = mutableListOf<WaitFilter>()

        override suspend fun createWithinLimit(wait: Wait, maxOpen: Int, sessions: List<String>): CreateOutcome =
            unused()
        override suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait? = unused()
        override suspend fun get(orgId: Uuid, id: Uuid): Wait? = stored[id]?.takeIf { it.orgId == orgId }
        override suspend fun list(filter: WaitFilter, before: Uuid?, limit: Int): List<Wait> {
            filters += filter
            return stored.values.filter { it.status == filter.status && it.sessionId in filter.sessionIds.orEmpty() }
                .take(limit)
        }
        override suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer): Wait? = unused()
        override suspend fun cancel(orgId: Uuid, id: Uuid, now: Instant): Wait? = unused()
        override suspend fun cancelOpenOfSessions(orgId: Uuid, sessions: List<String>, now: Instant): List<Wait> =
            unused()
        override suspend fun takeAnswered(orgId: Uuid, sessions: List<String>, limit: Int): List<Wait> = unused()
        override suspend fun hasOpen(orgId: Uuid, sessions: List<String>): Boolean = unused()
    }

    private inner class Asker(private val waits: Waits) : AskWaitUseCase, AnswerWaitUseCase {
        val asked = mutableListOf<Pair<WaitCaller, AskWait>>()
        val cancelled = mutableListOf<Pair<WaitCaller, Uuid>>()

        override suspend fun ask(caller: WaitCaller, request: AskWait, idempotencyKey: String?): AskedWait {
            asked += caller to request
            val wait = wait(Uuid.random(), WaitStatus.OPEN, request.sessionId, request.question)
            waits.stored[wait.id] = wait
            return AskedWait(wait, replayed = false)
        }

        override suspend fun answer(caller: WaitCaller, id: Uuid, action: String, values: Map<String, JsonElement>) =
            unused<Wait>()

        override suspend fun cancel(caller: WaitCaller, id: Uuid): Wait {
            cancelled += caller to id
            return waits.stored.getValue(id)
        }
    }

    private fun wait(id: Uuid, status: WaitStatus, session: String?, question: WaitSpec = WaitSpec("Q")) = Wait(
        id, ORG_A, "human_request", status, session, null, ADA, ADA, question,
        if (status == WaitStatus.RESOLVED) WaitAnswer("yes", emptyMap(), ADA, t0) else null, t0, null, null,
    )

    @Test
    fun aDropOrAStallAsksTheSessionsPersonAWaitInTheOpenSegment() = runTest {
        val repository = Waits()
        val asker = Asker(repository)
        val waits = AutomationsTodoWaits(asker, asker, repository)
        val owner = TodoOwner(ORG_A, ADA, "s1~r2", listOf("s1", "s1~r2"))
        val dropId = waits.askDrop(owner, stored(item("api")), "No longer needed")
        val (caller, request) = asker.asked.single()
        val person = WaitCaller(ORG_A, ADA, admin = false, interactive = false, segments = listOf("s1", "s1~r2"))
        assertEquals(person, caller)
        assertEquals("s1~r2", request.sessionId)
        assertEquals("Drop this item of the todo?", request.question.title)
        assertTrue(request.question.body!!.startsWith("**Item api** (`api`)\n\nNo longer needed\n\n"))
        assertEquals(listOf("yes", "no"), request.question.actions.map { it.id })
        assertEquals(listOf(WaitRef("session", id = "s1~r2")), request.question.refs)

        waits.askBlocked(owner, "T".repeat(300), "B".repeat(40_000), dropItem = true)
        val blocked = asker.asked.last().second.question
        assertEquals(200, blocked.title.length)
        assertEquals(32_000, blocked.body!!.length)
        assertEquals(listOf("no", "yes", "stop"), blocked.actions.map { it.id })
        waits.askBlocked(owner, "Blocked", "Body", dropItem = false)
        assertEquals(listOf("no", "stop"), asker.asked.last().second.question.actions.map { it.id })

        waits.cancel(owner, dropId)
        assertEquals(Uuid.parse(dropId), asker.cancelled.single().second)
    }

    @Test
    fun theStatesOfWaitsAreReadByIdAndAnOpenOneOfTheSessionIsSeen() = runTest {
        val repository = Waits()
        val waits = AutomationsTodoWaits(Asker(repository), Asker(repository), repository)
        val answered = Uuid.random()
        val open = Uuid.random()
        repository.stored[answered] = wait(answered, WaitStatus.RESOLVED, "s1")
        repository.stored[open] = wait(open, WaitStatus.OPEN, "s9")
        val ids = listOf(answered.toString(), open.toString(), "not-a-uuid", Uuid.random().toString())
        val states = waits.states(ORG_A, ids)
        assertEquals(
            mapOf(
                answered.toString() to TodoWaitState("resolved", "yes", "s1"),
                open.toString() to TodoWaitState("open", null, "s9"),
            ),
            states,
        )
        assertFalse(waits.anyOpen(ORG_A, listOf("s1", "s1~r2")))
        assertTrue(waits.anyOpen(ORG_A, listOf("s9")))
        assertEquals(WaitFilter(ORG_A, status = WaitStatus.OPEN, sessionIds = listOf("s9")), repository.filters.last())
    }

    @Test
    fun theLatestVersionOfEachArtifactIsReadInItsOrganization() = runTest {
        val id = Uuid.random()
        val artifact = Artifact(id, ORG_A, ADA, "Plan", ArtifactAccess.PERSONAL, 3, null, t0, t0)
        val artifacts = ArtifactsTodoArtifacts(Artifacts(mapOf(id to artifact)))
        assertEquals(
            mapOf(id.toString() to 3),
            artifacts.versions(ORG_A, listOf(id.toString(), "not-a-uuid", Uuid.random().toString())),
        )
    }

    private class Artifacts(private val byId: Map<Uuid, Artifact>) : ArtifactsRepository {
        override suspend fun create(artifact: Artifact, version: ArtifactVersion) = unused<Unit>()
        override suspend fun addVersion(orgId: Uuid, version: ArtifactVersion, now: Instant): Artifact? = unused()
        override suspend fun updateSettings(artifact: Artifact) = unused<Unit>()
        override suspend fun get(orgId: Uuid, id: Uuid): Artifact? = byId[id]?.takeIf { it.orgId == orgId }
        override suspend fun getById(id: Uuid): Artifact? = unused()
        override suspend fun getByLinkSlug(slug: String): Artifact? = unused()
        override suspend fun listReadable(caller: ArtifactCaller, before: Uuid?, limit: Int): List<Artifact> = unused()
        override suspend fun versions(orgId: Uuid, artifactId: Uuid): List<ArtifactVersion> = unused()
        override suspend fun version(orgId: Uuid, artifactId: Uuid, number: Int): ArtifactVersion? = unused()
        override suspend fun legacyBlobs(orgId: Uuid, limit: Int): List<LegacyBlob> = unused()
        override suspend fun releaseLegacyBlob(orgId: Uuid, artifactId: Uuid, sha256: String) = unused<Unit>()
        override suspend fun blobDigests(orgId: Uuid, artifactId: Uuid): Set<String> = unused()
        override suspend fun blobSizes(orgId: Uuid, artifactId: Uuid): Map<String, Long> = unused()
        override suspend fun members(orgId: Uuid, artifactId: Uuid): List<String> = unused()
        override suspend fun setMember(orgId: Uuid, artifactId: Uuid, memberId: String, share: Boolean) =
            unused<Unit>()
        override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = unused()
    }
}

/** A port method the adapters never call. */
private fun <T> unused(): T = error("Not used by the todos adapters.")

/** The item an agent wrote, as stored. */
private fun stored(definition: TodoItemDefinition) = TodoItem(
    definition.id,
    definition.name,
    definition.description,
    definition.doneWhen,
    definition.dependsOn,
    TodoOrigin.PLAN,
    null,
    TodoItemStatus.TODO,
)
