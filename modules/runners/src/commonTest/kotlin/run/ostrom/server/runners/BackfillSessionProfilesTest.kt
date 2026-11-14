// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.BackfillSessionProfilesUseCaseImpl
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.NoSessionProfiles
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionProfiles
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The former sessions given their profile at a server's start: every one, by pages, by compare-and-set, once. */
class BackfillSessionProfilesTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val clock = SettableClock(Instant.parse("2026-11-14T09:00:00Z"))
    private val profile = SessionProfile(clusterId = "c", namespace = "ostrom", image = "r:1", workspaceGibibytes = 20)
    private var legacy: SessionProfile? = profile
    private var asked = 0
    private val profiles = object : SessionProfiles by NoSessionProfiles {
        override suspend fun legacy(): SessionProfile? = legacy.also { asked++ }
    }

    private fun backfill(sessions: CloudSessionsRepository = repository) =
        BackfillSessionProfilesUseCaseImpl(sessions, profiles, clock, pageSize = 2)

    private fun stored(session: CloudSession) = repository.sessions.getValue(session.id)

    @Test
    fun everySessionWithoutAProfileIsGivenTheFormerOnesByPagesThenNothingIsDone() = runTest {
        // Four by pages of two: the last page read is empty.
        val former = List(4) { i -> session(state = SessionState.PAUSED, orgId = if (i % 2 == 0) ORG_B else ORG_A) }
        val ended = session(state = SessionState.TERMINATED)
        val newer = session().copy(profile = profile.copy(image = "web:1"))
        (former + ended + newer).forEach(repository::put)

        assertEquals(4, backfill()())

        for (session in former) {
            assertEquals(profile, stored(session).profile)
            assertEquals(SessionState.PAUSED, stored(session).state)
            assertEquals(clock.now, stored(session).updatedAt)
            assertEquals(1L, stored(session).generation, "Written once, by compare-and-set")
            assertNull(repository.next[session.id], "Its schedule is the reconciler's, untouched")
        }
        assertNull(stored(ended).profile, "A terminated session has nothing left to run")
        assertEquals("web:1", stored(newer).profile?.image)
        assertEquals(1, asked, "The former profile is read once")

        assertEquals(0, backfill()(), "A second start is a no-op")
        assertEquals(1, asked, "Nothing lacks one: not even the former profile is read")
        assertEquals(List(4) { 1L }, former.map { stored(it).generation })
    }

    @Test
    fun aSessionGoneBeforeItIsReadNeverEndsThePagesEarly() = runTest {
        val former = List(3) { session(state = SessionState.PAUSED) }.sortedBy { it.id.toString() }
        former.forEach(repository::put)
        // The first of a full page is deleted between the page and its read.
        val gone = former.first()
        val deleting = object : CloudSessionsRepository by repository {
            override suspend fun get(orgId: Uuid, id: Uuid): CloudSession? =
                if (id == gone.id) null else repository.get(orgId, id)
        }
        assertEquals(2, backfill(deleting)())
        assertEquals(listOf(null, profile, profile), former.map { stored(it).profile })
    }

    @Test
    fun withoutTheFormerVariablesClusterNothingIsWritten() = runTest {
        legacy = null
        val former = session(state = SessionState.PAUSED)
        repository.put(former)
        assertEquals(0, backfill()())
        assertNull(stored(former).profile, "Its next step asks again")
    }

    @Test
    fun aSessionAnotherWriterChangedFirstIsReadAgainAndNeverWrittenOver() = runTest {
        val resumed = session(state = SessionState.PAUSED)
        repository.put(resumed)
        // An API call wins the first write: the session is read again, then given its profile.
        repository.loseNextRaces = 1
        assertEquals(1, backfill()())
        assertEquals(profile, stored(resumed).profile)
        assertEquals(2L, stored(resumed).generation)

        // A step gives it its profile meanwhile: it is kept as it is.
        val stepped = session(state = SessionState.PAUSED)
        repository.put(stepped)
        val stepping = object : CloudSessionsRepository by repository {
            override suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession? {
                val current = repository.sessions.getValue(session.id)
                repository.put(current.copy(profile = profile.copy(image = "step:1"), generation = 9))
                return null
            }
        }
        assertEquals(0, backfill(stepping)())
        assertEquals("step:1", stored(stepped).profile?.image)
    }

    @Test
    fun aSessionStillContendedOrDeletedIsLeftToItsNextStep() = runTest {
        val contended = session(state = SessionState.PAUSED)
        repository.put(contended)
        repository.loseNextRaces = 3
        assertEquals(0, backfill()())
        assertNull(stored(contended).profile)

        val deleting = object : CloudSessionsRepository by repository {
            override suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession? {
                repository.sessions.remove(session.id)
                return null
            }
        }
        assertEquals(0, backfill(deleting)())
        assertEquals(emptyList(), repository.withoutProfile(null, 10), "Deleted meanwhile: nothing to give")
        assertEquals(2, asked, "Asked at each pass with a session to give")
    }
}
