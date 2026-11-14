// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.automations.domain.AnswerWaitUseCase
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCase
import run.ostrom.server.automations.domain.AskedWait
import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAgent
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitClosedException
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitRef
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryProposalSessionException
import run.ostrom.server.memory.domain.MemorySessionNotFoundException
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.ProposalSession
import run.ostrom.server.memory.domain.ProposalWait
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.BEN
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.caller
import run.ostrom.server.memory.infrastructure.AutomationsMemoryProposals
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionQuery
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.sessions.domain.repositories.SessionsTransaction
import run.ostrom.server.sessions.domain.repositories.StaleSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The proposals' waits on hand-written fakes of the automations and sessions modules' ports. */
class AdaptersTest {
    private val t0 = Instant.parse("2026-11-14T12:00:00Z")

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

    private val agents = object : WaitAgentResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? =
            if (sessionId == "s1") WaitAgent(ORG_A, ADA, "s1~r2", listOf("s1", "s1~r2")) else null
    }

    private class Waits : WaitsRepository {
        val stored = mutableMapOf<Uuid, Wait>()

        override suspend fun createWithinLimit(wait: Wait, maxOpen: Int, sessions: List<String>): CreateOutcome =
            unused()
        override suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait? = unused()
        override suspend fun get(orgId: Uuid, id: Uuid): Wait? = stored[id]?.takeIf { it.orgId == orgId }
        override suspend fun list(filter: WaitFilter, before: Uuid?, limit: Int): List<Wait> = unused()
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
            val wait = wait(Uuid.random(), WaitStatus.OPEN, null)
            waits.stored[wait.id] = wait
            return AskedWait(wait, replayed = false)
        }

        override suspend fun answer(caller: WaitCaller, id: Uuid, action: String, values: Map<String, JsonElement>) =
            unused<Wait>()

        override suspend fun cancel(caller: WaitCaller, id: Uuid): Wait {
            cancelled += caller to id
            return waits.stored[id] ?: throw WaitClosedException()
        }
    }

    private fun wait(id: Uuid, status: WaitStatus, answer: WaitAnswer?) = Wait(
        id, ORG_A, "human_request", status, "s1", null, ADA, ADA, WaitSpec("Q"), answer, t0, null, null,
    )

    private fun entry(tags: List<String>, title: String = "Deploy order") = MemoryEntry(
        Uuid.random(), MemoryScope.TEAM, null, title, "Core first", "Merge core, then the server.", tags, emptyList(),
        MemoryStatus.PROPOSED, MemoryOrigin.AGENT, ADA, "s1~r2", null, null, 1, t0, t0, null, 0,
    )

    private fun adapter(segments: List<Session> = emptyList(), waits: Waits = Waits(), asker: Asker = Asker(waits)) =
        AutomationsMemoryProposals(asker, asker, waits, agents, Sessions(segments), logins)

    /** A person's agent the CLI's relay forwards: the person's sign-in, marked as an agent's call. */
    private fun relayed(member: String) =
        caller(member, agent = true).copy(principal = AuthenticatedPrincipal(member, PrincipalKind.USER, emptySet()))

    @Test
    fun aProposalThatNamesNoSessionIsRefusedUnlessTheTokensLaunchIsACloudSessions() = runTest {
        // The fake resolver binds no cloud session: a token that names none must name one, as a person must.
        val agent = caller(person = false, agent = true)
        assertFailsWith<MemoryProposalSessionException> { adapter().session(agent, null) }
        assertFailsWith<MemoryProposalSessionException> { adapter().session(relayed(ADA), null) }
    }

    @Test
    fun anAgentTokensSessionIsItsLaunchsAndAPersonsMustBeTheirs() = runTest {
        val agent = caller(person = false, agent = true)
        assertEquals(ProposalSession("s1~r2", listOf("s1", "s1~r2")), adapter().session(agent, "s1"))
        assertFailsWith<OstromException> { adapter().session(agent, "s9") }

        val ada = relayed(ADA)
        val theirs = adapter(listOf(segment("s1", 1, "ada"), segment("s1~r2", 2, "ada")))
        assertEquals(ProposalSession("s1~r2", listOf("s1", "s1~r2")), theirs.session(ada, "s1"))
        assertFailsWith<MemorySessionNotFoundException> { theirs.session(ada, "ghost") }
        assertFailsWith<MemorySessionNotFoundException> { theirs.session(relayed(BEN), "s1") }
        val nobodys = adapter(listOf(segment("s1", 1, "gone")))
        assertFailsWith<MemorySessionNotFoundException> { nobodys.session(ada, "s1") }
    }

    @Test
    fun aProposalAsksTheSessionsPersonWithTheEntryToEdit() = runTest {
        val waits = Waits()
        val asker = Asker(waits)
        val adapter = adapter(waits = waits, asker = asker)
        val session = ProposalSession("s1~r2", listOf("s1", "s1~r2"))
        val id = adapter.open(caller(agent = true), session, entry(listOf("deploy")))
        val (who, request) = asker.asked.single()
        assertEquals(WaitCaller(ORG_A, ADA, admin = false, interactive = false, segments = session.segments), who)
        assertEquals("s1~r2", request.sessionId)
        assertEquals(id, waits.stored.keys.single())
        val question = request.question
        assertEquals("Add to the team memory: Deploy order", question.title)
        assertEquals(listOf("title", "summary", "content"), question.fields.map { it.id })
        assertEquals(JsonPrimitive("Core first"), question.fields[1].default)
        assertEquals(listOf("accept", "reject"), question.actions.map { it.id })
        assertEquals(listOf(WaitRef("session", id = "s1~r2")), question.refs)
        val body = question.body.orEmpty()
        assertTrue("Merge core, then the server." in body && "Tags: deploy" in body && "14 days" in body)
        assertFalse("external" in body)

        adapter.open(caller(agent = true), session, entry(listOf("external"), "T".repeat(300)))
        val external = asker.asked.last().second.question
        assertEquals(200, external.title.length)
        assertTrue("Tagged `external`" in external.body.orEmpty())
        adapter.open(caller(agent = true), session, entry(emptyList()))
        assertFalse("Tags:" in asker.asked.last().second.question.body.orEmpty())
        assertEquals(32_000, run {
            adapter.open(caller(agent = true), session, entry(emptyList()).copy(content = "c".repeat(40_000)))
            asker.asked.last().second.question.body.orEmpty().length
        })
    }

    @Test
    fun aWaitsStateIsReadByIdAndAClosedOneIsNotCancelledTwice() = runTest {
        val waits = Waits()
        val asker = Asker(waits)
        val adapter = adapter(waits = waits, asker = asker)
        val open = Uuid.random()
        val answered = Uuid.random()
        waits.stored[open] = wait(open, WaitStatus.OPEN, null)
        val values = mapOf("title" to JsonPrimitive("Fixed"))
        waits.stored[answered] = wait(answered, WaitStatus.RESOLVED, WaitAnswer("accept", values, BEN, t0))
        assertEquals(
            mapOf(
                open to ProposalWait(open, open = true),
                answered to ProposalWait(answered, open = false, "accept", mapOf("title" to "Fixed"), BEN),
            ),
            adapter.states(ORG_A, listOf(open, answered, Uuid.random())),
        )

        adapter.cancel(ORG_A, ADA, open)
        adapter.cancel(ORG_A, ADA, Uuid.random())
        assertEquals(WaitCaller(ORG_A, ADA, admin = false, interactive = false), asker.cancelled.first().first)
        assertEquals(2, asker.cancelled.size, "a wait closed meanwhile is left as it is")
    }
}

/** A port method the adapters never call. */
private fun <T> unused(): T = error("Not used by the memory adapters.")
