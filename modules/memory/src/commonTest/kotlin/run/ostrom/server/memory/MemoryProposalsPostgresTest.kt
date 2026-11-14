// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.MemoryProposals
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.memory.domain.MemoryCursor
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryProposalStore
import run.ostrom.server.memory.domain.MemoryProposalWaits
import run.ostrom.server.memory.domain.MemoryProposalsLimitException
import run.ostrom.server.memory.domain.MemoryProposalsService
import run.ostrom.server.memory.domain.MemoryRepository
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.NewMemory
import run.ostrom.server.memory.domain.ProposalWait
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.Callers
import run.ostrom.server.memory.fakes.EVE
import run.ostrom.server.memory.fakes.FakeProposalWaits
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.ORG_B
import run.ostrom.server.memory.fakes.Tokens
import run.ostrom.server.memory.fakes.caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * The agents' proposals on a real PostgreSQL (TeamCode #272): stored `proposed` and read by nobody until a decision,
 * the queue, the decisions, the cap of waiting proposals and the settling pass across organizations.
 */
class MemoryProposalsPostgresTest {
    private val waits = FakeProposalWaits()
    private val overrides = module {
        single<TokenAuthenticator> { Tokens }
        single<CallerResolver> { Callers }
        single<MemoryProposalWaits> { waits }
        // The instance module's lease is not under test here: no pass is due.
        single<ScheduledWork> {
            object : ScheduledWork {
                override suspend fun runDue(
                    name: String,
                    interval: Duration,
                    timeout: Duration,
                    block: suspend () -> Unit,
                ) = false
            }
        }
    }

    private fun proposalsTest(block: suspend HttpClient.(TestServer) -> Unit) =
        serverTest(listOf(MemoryModule), overrides) { server -> server.client.block(server) }

    private suspend fun HttpClient.propose(token: String, title: String, session: String? = "s1"): HttpResponse =
        post(MEMORY) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            val named = session?.let { ""","sessionId": "$it"""" }.orEmpty()
            setBody("""{"title": "$title", "summary": "s", "content": "c", "scope": "team"$named}""")
        }

    private suspend fun HttpClient.proposed(title: String): MemoryEntry {
        val response = propose("agent", title)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.queue(token: String): List<String> =
        get("$MEMORY/proposals") { bearerAuth(token) }.body<MemoryProposals>().proposals.map { it.title }

    private suspend fun HttpClient.decide(token: String, id: String, verb: String): HttpResponse =
        post("$MEMORY/proposals/$id/$verb") { bearerAuth(token) }

    private suspend fun HttpClient.index(token: String): List<String> =
        get("$MEMORY/index") { bearerAuth(token) }.body<MemoryIndexPage>().entries.map { it.title }

    private suspend fun HttpResponse.errorType(): String = body<ApiError>().error.type

    @Test
    fun anAgentsTeamEntryWaitsForItsPersonOrAnAdministrator() = proposalsTest {
        val first = proposed("Deploy order")
        assertEquals("proposed" to "agent", first.status to first.origin)
        assertEquals("s1", first.sessionId)
        assertEquals(first.id, waits.opened.single().second.id.toString(), "its person is asked")
        assertTrue(index("ada").isEmpty(), "nobody reads a proposal")
        assertEquals(listOf("Deploy order"), queue("ada"))
        assertEquals(listOf("Deploy order"), queue("root"))
        assertTrue(queue("ben").isEmpty())
        assertTrue(queue("eve").isEmpty())
        assertEquals(HttpStatusCode.Forbidden, get("$MEMORY/proposals") { bearerAuth("agent") }.status)
        assertEquals(HttpStatusCode.NotFound, propose("agent", "Elsewhere", "s9").status)
        assertEquals("memory_proposal_session", propose("agent", "Nowhere", null).errorType())

        assertEquals(HttpStatusCode.Forbidden, decide("ada", first.id, "accept").status)
        assertEquals(HttpStatusCode.NotFound, decide("eve", first.id, "accept").status)
        val accepted = decide("root", first.id, "accept")
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        assertEquals("active" to 2, accepted.body<MemoryEntry>().let { it.status to it.version })
        assertEquals(listOf("Deploy order"), index("ben"))
        assertEquals(listOf(ADA to waits.states.keys.single()), waits.cancelled, "its wait is closed")
        assertEquals("memory_not_proposed", decide("root", first.id, "reject").errorType())

        val second = proposed("Withdrawn")
        assertEquals(HttpStatusCode.NotFound, decide("ben", second.id, "reject").status, "not Ben's to know of")
        assertEquals("rejected", decide("ada", second.id, "reject").body<MemoryEntry>().status)
        assertEquals(listOf("Deploy order"), index("ada"))
        assertEquals(HttpStatusCode.NotFound, decide("root", Uuid.random().toString(), "accept").status)
        assertEquals(HttpStatusCode.NotFound, decide("root", "not-an-id", "accept").status)
        assertEquals(HttpStatusCode.Unauthorized, post("$MEMORY/proposals/${second.id}/accept").status)
    }

    @Test
    fun aMemberHasTwentyProposalsWaitingAtMost() = proposalsTest { server ->
        val repository = server.koin.get<MemoryRepository>()
        val agent = caller(person = false, agent = true)
        repeat(20) { repository.create(agent, note("Note $it"), MemoryOrigin.AGENT, "s1", MemoryStatus.PROPOSED) }
        val refused = assertFailsWith<MemoryProposalsLimitException> {
            repository.create(agent, note("One more"), MemoryOrigin.AGENT, "s1", MemoryStatus.PROPOSED)
        }
        assertTrue("20" in refused.message.orEmpty())
        assertEquals("memory_proposals_limit", propose("agent", "One more").errorType())
        // Another member's, and a person's own entries, are apart.
        val eve = caller(EVE, ORG_B, agent = true)
        repository.create(eve, note("Eve's"), MemoryOrigin.AGENT, "s1", MemoryStatus.PROPOSED)
        repository.create(caller(), note("Mine"), MemoryOrigin.HUMAN, null)
    }

    @Test
    fun theSettlingPassAppliesTheAnswersInEachOrganization() = proposalsTest { server ->
        val proposals = server.koin.get<MemoryProposalsService>()
        val store = server.koin.get<MemoryProposalStore>()
        val edited = proposals.propose(caller(person = false, agent = true), note("Edited"), "s1")
        val stale = proposals.propose(caller(person = false, agent = true), note("Stale"), "s1")
        val eves = proposals.propose(caller(EVE, ORG_B, person = false, agent = true), note("Eve's"), "s1")
        val waitOf = waits.waitOf
        val editedWait = waitOf.getValue(edited.id)
        waits.states[editedWait] = ProposalWait(editedWait, open = false, "accept", mapOf("title" to "Fixed"), ADA)
        waits.states[waitOf.getValue(eves.id)] = ProposalWait(waitOf.getValue(eves.id), open = false, "reject")
        val superuser = server.database.superuser
        superuser.execute("UPDATE memory_entries SET updated_at = now() - INTERVAL '15 days' WHERE id = '${stale.id}'")
            .getOrThrow()
        assertEquals(setOf(ORG_A, ORG_B), store.organizations(null, 10).toSet())
        val oldest = store.pending(ORG_A, null, 1).single().entry
        assertEquals(stale.id, oldest.id, "oldest first")
        assertEquals(
            listOf(edited.id),
            store.pending(ORG_A, MemoryCursor(oldest.updatedAt, oldest.id), 10).map { it.entry.id },
        )
        val first = store.organizations(null, 1).single()
        assertEquals(setOf(ORG_A, ORG_B) - first, store.organizations(first, 10).toSet(), "by id, after the cursor")

        assertEquals(3, proposals.settle())
        val ada = caller()
        assertEquals(listOf("Fixed"), server.koin.get<MemoryRepository>().index(ada, null, emptyList(), null, 50)
            .entries.map { it.title })
        assertEquals(listOf(ADA to waitOf.getValue(stale.id)), waits.cancelled)
        val statuses = superuser.fetchAll("SELECT title, status FROM memory_entries ORDER BY title").getOrThrow()
            .map { it.get("title").asString() to it.get("status").asString() }
        assertEquals(listOf("Eve's" to "rejected", "Fixed" to "active", "Stale" to "rejected"), statuses)

        // The rejected are kept 30 days for the audit, then deleted; an organization with none left is not visited.
        superuser.execute("UPDATE memory_entries SET updated_at = now() - INTERVAL '31 days' WHERE status = 'rejected'")
            .getOrThrow()
        assertEquals(0, proposals.settle())
        assertEquals(1L, superuser.fetchAll("SELECT id FROM memory_entries").getOrThrow().toList().size.toLong())
        assertTrue(store.organizations(null, 10).isEmpty())
        val job = Job()
        MemoryModule.start(CoroutineScope(job), server.koin)
        job.cancel()
    }

    private companion object {
        const val MEMORY = "/api/v1/memory"

        fun note(title: String) = NewMemory(MemoryScope.TEAM, title, "s", "c")
    }
}
