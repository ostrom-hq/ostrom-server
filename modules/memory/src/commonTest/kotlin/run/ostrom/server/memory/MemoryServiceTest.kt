// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.memory.domain.InvalidMemoryException
import run.ostrom.server.memory.domain.MemoryConflictException
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryProposalsServiceImpl
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemorySecretException
import run.ostrom.server.memory.domain.MemoryServiceImpl
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.MemoryTeamForbiddenException
import run.ostrom.server.memory.domain.MemoryUpdate
import run.ostrom.server.memory.domain.NewMemory
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.BEN
import run.ostrom.server.memory.fakes.FakeProposalStore
import run.ostrom.server.memory.fakes.FakeProposalWaits
import run.ostrom.server.memory.fakes.FakeRepository
import run.ostrom.server.memory.fakes.ROOT
import run.ostrom.server.memory.fakes.RecordingAudit
import run.ostrom.server.memory.fakes.caller
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class MemoryServiceTest {
    private val repository = FakeRepository()
    private val audit = RecordingAudit()
    private val clock = object : Clock {
        override fun now(): Instant = FakeRepository.AT
    }
    private val waits = FakeProposalWaits()
    private val proposals = MemoryProposalsServiceImpl(repository, FakeProposalStore(repository), waits, audit, clock)
    private val memory = MemoryServiceImpl(repository, proposals, audit, clock)

    private fun entry(scope: MemoryScope = MemoryScope.PERSONAL, tags: List<String> = listOf("b", "a")) =
        NewMemory(scope, "Deploy order", "Core first", "Merge core, then the server.", tags)

    @Test
    fun aPersonWritesBothScopesDirectlyAsAHuman() = runTest {
        val personal = memory.create(caller(), entry())
        val team = memory.create(caller(), entry(MemoryScope.TEAM))
        assertEquals(MemoryOrigin.HUMAN, personal.origin)
        assertEquals(MemoryScope.TEAM, team.scope)
        assertEquals(listOf("a", "b"), personal.tags, "tags are stored sorted")
        assertEquals(
            listOf("memory.created scope=personal,origin=human", "memory.created scope=team,origin=human"),
            audit.events,
        )
    }

    @Test
    fun anAgentOrAKeyWritesItsOwnEntriesButNotTheTeams() = runTest {
        val agent = caller(person = false, agent = true)
        assertEquals(MemoryOrigin.AGENT, memory.create(agent, entry(), "s1").origin)
        assertEquals("s1", repository.lastSession)
        // A relay's call carries a person's sign-in but is still an agent's.
        assertEquals(MemoryOrigin.AGENT, memory.create(caller(agent = true), entry()).origin)
        val key = caller(person = false)
        assertEquals(MemoryOrigin.AGENT, memory.create(key, entry()).origin)
        // An agent's organization entry is a proposal of its session; a key is no agent's session.
        for (who in listOf(agent, caller(agent = true))) {
            assertEquals(MemoryStatus.PROPOSED, memory.create(who, entry(MemoryScope.TEAM), "s1").status)
        }
        assertEquals(listOf("a", "b"), waits.opened.last().second.tags)
        assertFailsWith<MemoryTeamForbiddenException> { memory.create(key, entry(MemoryScope.TEAM), "s1") }
    }

    @Test
    fun anInvalidOrSecretEntryIsRefusedBeforeAnythingIsStored() = runTest {
        assertFailsWith<InvalidMemoryException> { memory.create(caller(), entry().copy(title = "")) }
        assertFailsWith<MemorySecretException> {
            memory.create(caller(), entry().copy(content = "AKIAABCDEFGHIJKLMNOP"))
        }
        assertTrue(repository.entries.isEmpty())
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun aTeamEntryIsChangedByItsAuthorOrAnAdministratorAsAPerson() = runTest {
        val team = memory.create(caller(), entry(MemoryScope.TEAM))
        val update = MemoryUpdate(1, "New title", "Core first", "Merge core.", listOf("z", "y"))
        val changed = memory.update(caller(), team.id, update)
        assertEquals(listOf("y", "z"), changed.tags)
        assertEquals(2, changed.version)
        memory.update(caller(ROOT, admin = true), team.id, update.copy(expectedVersion = 2))
        val refused = listOf(
            caller(BEN),
            caller(person = false),
            caller(agent = true),
            caller(ROOT, admin = true, person = false),
        )
        for (who in refused) {
            assertFailsWith<MemoryTeamForbiddenException> {
                memory.update(who, team.id, update.copy(expectedVersion = 3))
            }
            assertFailsWith<MemoryTeamForbiddenException> { memory.delete(who, team.id) }
        }
        memory.delete(caller(ROOT, admin = true), team.id)
        assertEquals(
            listOf("memory.created", "memory.updated", "memory.updated", "memory.forgotten"),
            audit.events.map { it.substringBefore(' ') },
        )
    }

    @Test
    fun aPersonalEntryIsChangedByItsOwnerWhateverTheCredential() = runTest {
        val mine = memory.create(caller(person = false, agent = true), entry())
        val update = MemoryUpdate(1, "New title", "s", "c")
        memory.update(caller(ADA, person = false, agent = true), mine.id, update)
        assertFailsWith<MemoryConflictException> { memory.update(caller(), mine.id, update) }
        assertFailsWith<InvalidMemoryException> { memory.update(caller(), mine.id, update.copy(title = "")) }
        memory.delete(caller(person = false), mine.id)
        assertTrue(repository.entries.isEmpty())
    }

    @Test
    fun aListIsBoundedAndItsTagsChecked() = runTest {
        memory.index(caller(), limit = 500, tags = listOf("a", "a"))
        assertEquals(50, repository.lastLimit)
        assertEquals(listOf("a"), repository.lastTags)
        memory.index(caller(), limit = 0)
        assertEquals(1, repository.lastLimit)
        memory.search(caller(), "Deploy core", limit = 99)
        assertEquals(20, repository.lastLimit)
        assertEquals(listOf("deploy", "core"), repository.lastTerms)
        assertFailsWith<InvalidMemoryException> { memory.index(caller(), tags = listOf("Bad!")) }
        assertFailsWith<InvalidMemoryException> { memory.index(caller(), tags = List(9) { "t$it" }) }
        assertFailsWith<InvalidMemoryException> { memory.search(caller(), "?") }
    }

    @Test
    fun anEntryIsReadThroughTheRepository() = runTest {
        val mine = memory.create(caller(), entry())
        assertEquals(mine, memory.read(caller(), mine.id))
    }
}
