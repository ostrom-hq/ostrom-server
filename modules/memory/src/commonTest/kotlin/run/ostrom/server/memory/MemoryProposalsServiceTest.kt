// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryNotProposedException
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryProposalForbiddenException
import run.ostrom.server.memory.domain.MemoryProposalSessionException
import run.ostrom.server.memory.domain.MemoryProposalsServiceImpl
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemorySessionNotFoundException
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.MemoryTeamForbiddenException
import run.ostrom.server.memory.domain.NewMemory
import run.ostrom.server.memory.domain.ProposalWait
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.BEN
import run.ostrom.server.memory.fakes.FakeProposalStore
import run.ostrom.server.memory.fakes.FakeProposalWaits
import run.ostrom.server.memory.fakes.FakeRepository
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.ORG_B
import run.ostrom.server.memory.fakes.ROOT
import run.ostrom.server.memory.fakes.RecordingAudit
import run.ostrom.server.memory.fakes.caller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** An agent's entries for the organization's memory: proposals its session's person, or an administrator, decides. */
class MemoryProposalsServiceTest {
    private val repository = FakeRepository()
    private val store = FakeProposalStore(repository)
    private val waits = FakeProposalWaits()
    private val audit = RecordingAudit()
    private val clock = object : Clock {
        override fun now(): Instant = FakeRepository.AT
    }
    private val proposals = MemoryProposalsServiceImpl(repository, store, waits, audit, clock)
    private val agent = caller(person = false, agent = true)
    private val admin = caller(ROOT, admin = true)

    private fun note(title: String = "Deploy order") =
        NewMemory(MemoryScope.TEAM, title, "Core first", "Merge core, then the server.", listOf("deploy"))

    private suspend fun proposed(title: String = "Deploy order"): MemoryEntry =
        proposals.propose(agent, note(title), "s1")

    private fun age(entry: MemoryEntry, by: kotlin.time.Duration) {
        repository.entries[entry.id] = repository.entries.getValue(entry.id).copy(updatedAt = FakeRepository.AT - by)
    }

    private fun answer(entry: MemoryEntry, action: String, values: Map<String, String> = emptyMap(), by: String = ADA) {
        val id = store.waits.getValue(entry.id)
        waits.states[id] = ProposalWait(id, open = false, action = action, values = values, author = by)
    }

    private fun status(entry: MemoryEntry) = repository.entries.getValue(entry.id).status

    @Test
    fun anAgentsTeamEntryIsAProposalOfItsSessionThatItsPersonIsAsked() = runTest {
        val entry = proposed()
        assertEquals(MemoryStatus.PROPOSED, entry.status)
        assertEquals(MemoryOrigin.AGENT, entry.origin)
        assertEquals("s1", entry.sessionId)
        val (session, asked) = waits.opened.single()
        assertEquals("s1", session.segment)
        assertEquals(entry.id, asked.id)
        assertEquals(waits.states.keys.single(), store.waits[entry.id])
        assertEquals(listOf("memory.proposed scope=team,origin=agent"), audit.events)
        // A person's relayed agent proposes from the session it names, which must be theirs.
        assertEquals(MemoryStatus.PROPOSED, proposals.propose(caller(agent = true), note(), "s1~r2").status)
        assertFailsWith<MemorySessionNotFoundException> { proposals.propose(caller(agent = true), note(), "s9") }
    }

    @Test
    fun aProposalNeedsAnAgentsSessionAndAWaitThatOpens() = runTest {
        assertFailsWith<MemoryTeamForbiddenException> { proposals.propose(caller(person = false), note(), "s1") }
        assertFailsWith<MemoryProposalSessionException> { proposals.propose(agent, note(), null) }
        assertFailsWith<MemoryProposalSessionException> { proposals.propose(agent, note(), "  ") }
        assertTrue(repository.entries.isEmpty())
        waits.failOpen = true
        assertFailsWith<IllegalStateException> { proposals.propose(agent, note(), "s1") }
        assertEquals(MemoryStatus.REJECTED, repository.entries.values.single().status, "nobody could ever accept it")
        assertTrue(waits.cancelled.isEmpty(), "no wait was opened")
        // A wait opened but never bound to its proposal is closed with it.
        waits.failOpen = false
        store.failAttach = true
        assertFailsWith<IllegalStateException> { proposals.propose(agent, note(), "s1") }
        assertEquals(List(2) { MemoryStatus.REJECTED }, repository.entries.values.map { it.status })
        assertEquals(listOf(ADA to waits.states.keys.single()), waits.cancelled)
        // A cancelled call withdraws it all the same.
        store.failAttach = false
        waits.cancelOpen = true
        assertFailsWith<CancellationException> { proposals.propose(agent, note(), "s1") }
        assertEquals(List(3) { MemoryStatus.REJECTED }, repository.entries.values.map { it.status })
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun theQueueIsAPersonsTheirOwnOrEveryonesForAnAdministrator() = runTest {
        val mine = proposed()
        val bens = proposals.propose(caller(BEN, person = false, agent = true), note("Ben's"), "s1")
        assertEquals(listOf(mine.id), proposals.proposals(caller()).map { it.id })
        assertEquals(setOf(mine.id, bens.id), proposals.proposals(admin).map { it.id }.toSet())
        for (who in listOf(agent, caller(agent = true), caller(person = false))) {
            assertFailsWith<MemoryTeamForbiddenException> { proposals.proposals(who) }
            assertFailsWith<MemoryTeamForbiddenException> { proposals.decide(who, mine.id, accept = false) }
        }
    }

    @Test
    fun anAdministratorAcceptsAndTheAuthorOrAnAdministratorRejects() = runTest {
        val first = proposed()
        assertFailsWith<MemoryProposalForbiddenException> { proposals.decide(caller(), first.id, accept = true) }
        // Another member's proposal is not theirs to know of: the 404 of an unknown entry.
        assertFailsWith<MemoryNotFoundException> { proposals.decide(caller(BEN), first.id, accept = false) }
        assertFailsWith<MemoryNotFoundException> { proposals.decide(caller(BEN), first.id, accept = true) }
        assertEquals(MemoryStatus.ACTIVE, proposals.decide(admin, first.id, accept = true).status)
        assertEquals(listOf(ADA to store.waits.getValue(first.id)), waits.cancelled, "its wait is closed")
        assertFailsWith<MemoryNotProposedException> { proposals.decide(admin, first.id, accept = true) }

        val second = proposed()
        assertEquals(MemoryStatus.REJECTED, proposals.decide(caller(), second.id, accept = false).status)
        val third = proposed()
        assertEquals(MemoryStatus.REJECTED, proposals.decide(admin, third.id, accept = false).status)
        // One still being opened has no wait to close.
        val opening = repository.create(agent, note(), MemoryOrigin.AGENT, "s1", MemoryStatus.PROPOSED)
        proposals.decide(admin, opening.id, accept = false)
        assertEquals(3, waits.cancelled.size)
        assertEquals(
            listOf("memory.accepted edited=false", "memory.rejected", "memory.rejected", "memory.rejected"),
            audit.events.filter { !it.startsWith("memory.proposed") },
        )
    }

    @Test
    fun theSettlingAppliesTheAnswersAndTheOwnersValidEdits() = runTest {
        val edited = proposed("Edited")
        answer(edited, "accept", mapOf("title" to "Deploy order, fixed", "summary" to "Core, then server"), by = BEN)
        val asSent = proposed("As sent")
        answer(asSent, "accept", mapOf("title" to "As sent", "summary" to "Core first"))
        val broken = proposed("Broken")
        answer(broken, "accept", mapOf("title" to " "))
        val secret = proposed("Secret")
        answer(secret, "accept", mapOf("content" to "AKIAABCDEFGHIJKLMNOP"))
        val refused = proposed("Refused")
        answer(refused, "reject")
        val waiting = proposed("Waiting")

        assertEquals(5, proposals.settle())
        val fixed = repository.entries.getValue(edited.id)
        assertEquals(MemoryStatus.ACTIVE, fixed.status)
        assertEquals("Deploy order, fixed" to "Core, then server", fixed.title to fixed.summary)
        assertEquals("Merge core, then the server.", fixed.content)
        for (entry in listOf(asSent, broken, secret)) {
            assertEquals(MemoryStatus.ACTIVE, status(entry))
            val kept = repository.entries.getValue(entry.id)
            assertEquals(entry.title to entry.content, kept.title to kept.content)
        }
        assertEquals(MemoryStatus.REJECTED, status(refused))
        assertEquals(MemoryStatus.PROPOSED, status(waiting), "an open wait under 14 days is left")
        assertEquals(
            listOf(
                "memory.accepted by=$BEN,edited=true",
                "memory.accepted by=$ADA,edited=false",
                "memory.accepted by=$ADA,edited=false",
                "memory.accepted by=$ADA,edited=false",
                "memory.rejected by=$ADA",
            ),
            audit.events.filter { !it.startsWith("memory.proposed") },
        )
        assertEquals(setOf("memory"), audit.actors.drop(6).toSet(), "the settling pass writes as the memory")
        assertTrue(waits.cancelled.isEmpty(), "an answered wait is closed already")
        assertEquals(listOf(FakeRepository.AT - 30.days), store.purged)
    }

    @Test
    fun theSettlingRejectsWhatNobodyAnswersOrCanAnswer() = runTest {
        val stale = proposed()
        age(stale, 15.days)
        val lost = proposed()
        waits.states.remove(store.waits.getValue(lost.id))
        val opening = repository.create(agent, note(), MemoryOrigin.AGENT, "s1", MemoryStatus.PROPOSED)
        val abandoned = repository.create(agent, note(), MemoryOrigin.AGENT, "s1", MemoryStatus.PROPOSED)
        age(abandoned, 61.seconds)

        assertEquals(3, proposals.settle())
        assertEquals(listOf(MemoryStatus.REJECTED, MemoryStatus.REJECTED), listOf(status(stale), status(lost)))
        assertEquals(MemoryStatus.PROPOSED, status(opening), "a wait being opened gets a minute")
        assertEquals(MemoryStatus.REJECTED, status(abandoned))
        assertEquals(listOf(ADA to store.waits.getValue(stale.id)), waits.cancelled, "the stale one's wait is closed")
        assertEquals(List(3) { "memory.rejected by=ostrom" }, audit.events.filter { it.startsWith("memory.rejected") })
    }

    @Test
    fun aProposalAnotherReplicaSettledIsNeitherCountedNorAudited() = runTest {
        val stale = proposed()
        age(stale, 15.days)
        store.lostRace = true
        store.organizations = listOf(Uuid.random(), ORG_B)
        assertEquals(0, proposals.settle())
        assertEquals(listOf("memory.proposed scope=team,origin=agent"), audit.events)
        assertEquals(2, waits.cancelled.size, "its wait is closed all the same, in each pass")
        assertNull(repository.entries.values.firstOrNull { it.status != MemoryStatus.PROPOSED })
    }

    @Test
    fun theSettlingReadsEveryPageOfEveryOrganizationAndOneFailureStopsNoOther() = runTest {
        val paged = MemoryProposalsServiceImpl(repository, store, waits, audit, clock, page = 2)
        // Two waiting proposals fill the first page: the answers after them are applied all the same.
        proposed("Waiting 1")
        proposed("Waiting 2")
        val answered = (1..3).map { proposed("Answered $it").also { entry -> answer(entry, "reject") } }
        val other = Uuid.random()
        store.organizations = listOf(other, ORG_A, ORG_B)
        store.broken = other
        assertFailsWith<IllegalStateException> { paged.settle() }
        assertEquals(List(3) { MemoryStatus.REJECTED }, answered.map(::status), "the broken organization stops none")
        assertEquals(List(2) { FakeRepository.AT - 30.days }, store.purged, "ORG_A's and ORG_B's, on the second page")
    }
}
