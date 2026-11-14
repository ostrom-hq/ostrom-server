// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.memory.domain.CuratedScope
import run.ostrom.server.memory.domain.CurationReport
import run.ostrom.server.memory.domain.CurationScope
import run.ostrom.server.memory.domain.MemoryCurationForbiddenException
import run.ostrom.server.memory.domain.MemoryCurationServiceImpl
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryTeamForbiddenException
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.BEN
import run.ostrom.server.memory.fakes.FakeCurationRuns
import run.ostrom.server.memory.fakes.FakeCurationStore
import run.ostrom.server.memory.fakes.FakeRepository
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.ORG_B
import run.ostrom.server.memory.fakes.ROOT
import run.ostrom.server.memory.fakes.RecordingAudit
import run.ostrom.server.memory.fakes.caller
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The curation's rules (TeamCode #273): who reads and answers what, what is audited, how a run spreads its room. */
class MemoryCurationServiceTest {
    private val store = FakeCurationStore()
    private val runs = FakeCurationRuns()
    private val audit = RecordingAudit()
    private val clock = object : Clock {
        override fun now(): Instant = FakeRepository.AT
    }
    private val first = Uuid.parse("00000000-0000-0000-0000-000000000000")
    private val curation = MemoryCurationServiceImpl(store, runs, audit, clock, page = 2) { first }
    private val root = caller(ROOT, admin = true)

    @Test
    fun onlyAnAdministratorSignedInReadsOrChangesTheSetting() = runTest {
        assertFalse(curation.settings(root).enabled)
        assertFailsWith<MemoryCurationForbiddenException> { curation.settings(caller()) }
        assertFailsWith<MemoryCurationForbiddenException> { curation.setEnabled(caller(), true) }
        val key = caller(ROOT, admin = true, person = false)
        assertFailsWith<MemoryTeamForbiddenException> { curation.setEnabled(key, true) }
        assertTrue(curation.setEnabled(root, true).enabled)
        assertFalse(curation.setEnabled(root, false).enabled)
        assertEquals(listOf("memory.curation_enabled", "memory.curation_disabled"), audit.events)
    }

    @Test
    fun aPersonAnswersTheirReviewsAndNoKeyOrAgentDoes() = runTest {
        assertEquals(listOf(store.review), curation.reviews(caller()))
        assertTrue(curation.superseded(caller()).isEmpty())
        assertEquals(listOf("reviews 100", "superseded 100"), store.asked)
        for (other in listOf(caller(person = false), caller(agent = true))) {
            assertFailsWith<MemoryTeamForbiddenException> { curation.reviews(other) }
            assertFailsWith<MemoryTeamForbiddenException> { curation.resolve(other, store.review.id, true) }
            assertFailsWith<MemoryTeamForbiddenException> { curation.restore(other, Uuid.random()) }
        }
        assertEquals(store.review, curation.resolve(caller(), store.review.id, apply = true))
        curation.resolve(caller(), store.review.id, apply = false)
        val by = store.review.newerId
        assertEquals(
            listOf(
                "memory.superseded by=$by,run=review:$ADA,reason=reviewed",
                "memory.review_applied",
                "memory.review_dismissed",
            ),
            audit.events,
        )
    }

    @Test
    fun aTeamEntryIsRestoredByItsAuthorOrAnAdministratorAndItsReplacementAudited() = runTest {
        val id = Uuid.random()
        assertFailsWith<MemoryCurationForbiddenException> { curation.restore(caller(), id) }
        assertEquals(id, curation.restore(caller(BEN), id).id, "Ben wrote it")
        val replaced = Uuid.random()
        store.replaced = replaced
        curation.restore(root, id)
        store.entryScope = MemoryScope.PERSONAL
        curation.restore(caller(), id)
        assertEquals(
            listOf(
                "memory.restored",
                "memory.superseded by=$id,run=restore:$ROOT,reason=restored",
                "memory.restored replaced=$replaced",
                "memory.superseded by=$id,run=restore:$ADA,reason=restored",
                "memory.restored replaced=$replaced",
            ),
            audit.events,
        )
    }

    @Test
    fun aRunKeepsEveryOrganizationAndCuratesTwentyScopesAtMost() = runTest {
        val orgs = listOf(ORG_A, ORG_B, Uuid.random())
        orgs.forEach(runs::add)
        val scopes = List(15) { CurationScope(MemoryScope.PERSONAL, "m$it") }
        val older = Uuid.random()
        val newer = Uuid.random()
        runs.superseding[scopes[0]] = CuratedScope(listOf(older to newer), 2)
        runs.scopes[ORG_A] = scopes
        runs.scopes[ORG_B] = scopes
        val gone = Uuid.random()
        runs.expired[orgs[2]] = listOf(gone)
        val report = curation.run("run-1")
        assertEquals(CurationReport(20, 2, 4, 1), report)
        assertEquals(listOf(ORG_A to 20, ORG_B to 5), runs.rooms, "the third organization has no room left")
        assertEquals(listOf("$ORG_A run-1 1 2", "$ORG_B run-1 1 2"), runs.recorded)
        assertTrue("memory.expired run=run-1" in audit.events)
        assertEquals(2, audit.events.count { it == "memory.superseded by=$newer,run=run-1,reason=duplicate" })
        assertEquals(setOf("memory"), audit.actors.toSet(), "the run writes as the memory")
    }

    @Test
    fun aBrokenOrganizationLeavesTheOthersDoneAndItsFailureIsThrownOnce() = runTest {
        val orgs = listOf(ORG_A, ORG_B, Uuid.random())
        orgs.forEach(runs::add)
        runs.broken += ORG_A
        runs.broken += ORG_B
        val failure = assertFailsWith<IllegalStateException> { curation.run("run-2") }
        assertEquals("broken", failure.message)
        assertEquals(orgs - ORG_A - ORG_B, runs.rooms.map { it.first }, "the third one ran")
    }

    @Test
    fun aRunStartsAtARandomOrganizationAndComesBackToItSoNoneIsAlwaysLast() = runTest {
        val orgs = (1..5).map { Uuid.parse("00000000-0000-0000-0000-00000000000$it") }
        orgs.forEach(runs::add)
        orgs.forEach { runs.scopes[it] = List(15) { n -> CurationScope(MemoryScope.PERSONAL, "m$n") } }
        val middle = Uuid.parse("00000000-0000-0000-0000-000000000003")
        val wrapping = MemoryCurationServiceImpl(store, runs, audit, clock, page = 2) { middle }
        assertEquals(20, wrapping.run("run-4").scopes)
        assertEquals(listOf(orgs[3] to 20, orgs[4] to 5), runs.rooms, "the fourth one first, then no room left")
        assertEquals(orgs.drop(3) + orgs.take(3), runs.retained, "then from the first, each kept once")
    }
}
