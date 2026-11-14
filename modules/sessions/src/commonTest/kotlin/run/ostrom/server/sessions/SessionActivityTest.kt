// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.sessions.domain.models.EndSession
import run.ostrom.server.sessions.domain.models.InvalidSessionActivityException
import run.ostrom.server.sessions.domain.models.NotYourSessionException
import run.ostrom.server.sessions.domain.models.SessionActivityState
import run.ostrom.server.sessions.domain.models.SessionActivityState.WAITING_FOR_HUMAN
import run.ostrom.server.sessions.domain.models.SessionActivityState.WORKING
import run.ostrom.server.sessions.domain.models.SessionEndedException
import run.ostrom.server.sessions.domain.models.SessionNotFoundException
import run.ostrom.server.sessions.domain.models.StartSession
import run.ostrom.server.sessions.fakes.ORG
import run.ostrom.server.sessions.fakes.SessionBench
import run.ostrom.server.sessions.fakes.agent
import run.ostrom.server.sessions.fakes.user
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionActivityTest {
    private val start = StartSession(id = "s-1", agent = "claude-code", repo = "acme/api")

    private fun SessionBench.stored(id: String = "s-1") = repository.rows.getValue(ORG to id)

    @Test
    fun aReportIsStoredWithTheServersTimeAndARepeatedStateKeepsIt() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        val began = bench.clock.now
        val first = bench.activity(agent(), "s-1", WORKING, began)
        assertEquals(Triple(WORKING, began, false), Triple(first.state, first.since, first.superseded))
        bench.clock.now += 5.seconds
        val again = bench.activity(agent(), "s-1", WORKING, bench.clock.now)
        assertEquals(began, again.since, "the same state keeps its time")
        assertEquals(bench.clock.now, bench.stored().activityObservedAt, "the newest observation is kept")
        bench.clock.now += 5.seconds
        val waiting = bench.activity(user(), "s-1", WAITING_FOR_HUMAN, null)
        assertEquals(WAITING_FOR_HUMAN to bench.clock.now, waiting.state to waiting.since)
        assertEquals(began + 5.seconds, bench.stored().activityObservedAt, "a report without a time keeps it")
        val unchanged = bench.activity(user(), "s-1", WAITING_FOR_HUMAN, null)
        assertEquals(bench.clock.now, unchanged.since)
    }

    @Test
    fun aReportObservedBeforeTheStoredOneChangesNothing() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        val now = bench.clock.now
        bench.activity(agent(), "s-1", WAITING_FOR_HUMAN, now)
        val late = bench.activity(agent(), "s-1", WORKING, now - 1.seconds)
        assertTrue(late.superseded)
        assertEquals(WAITING_FOR_HUMAN to now, late.state to late.since)
        assertEquals(WAITING_FOR_HUMAN, bench.stored().activity)
        assertFalse(bench.activity(agent(), "s-1", WORKING, now).superseded, "the same time is not older")
    }

    @Test
    fun aClockFarAheadCountsAsTheServersTimePlusFiveMinutes() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        val now = bench.clock.now
        bench.activity(agent(), "s-1", WORKING, now + 3_600.seconds)
        assertEquals(now + 5.minutes, bench.stored().activityObservedAt)
        bench.clock.now += 6.minutes
        assertFalse(bench.activity(agent(), "s-1", WAITING_FOR_HUMAN, bench.clock.now).superseded)
    }

    @Test
    fun aSignOfLifeThatEndsASilenceClearsAWorkingReportedBeforeIt() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        bench.alive(agent(), "s-1")
        bench.activity(agent(), "s-1", WORKING, null)
        bench.clock.now += 20.seconds
        bench.alive(agent(), "s-1")
        assertEquals(WORKING, bench.stored().activity, "no silence")
        bench.clock.now += 90.seconds
        bench.alive(agent(), "s-1")
        assertNull(bench.stored().activity, "a guess after the silence")
        assertNull(bench.stored().activityAt)
    }

    @Test
    fun aStateReportedDuringTheSilenceAndAWaitAreKept() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        bench.alive(agent(), "s-1")
        bench.clock.now += 90.seconds
        val during = bench.activity(agent(), "s-1", WORKING, null)
        assertEquals(bench.clock.now, during.since)
        bench.clock.now += 1.seconds
        bench.alive(agent(), "s-1")
        assertEquals(WORKING, bench.stored().activity, "reported during the silence: current")

        bench.activity(agent(), "s-1", WAITING_FOR_HUMAN, null)
        bench.clock.now += 90.seconds
        bench.alive(agent(), "s-1")
        assertEquals(WAITING_FOR_HUMAN, bench.stored().activity, "a person still has to answer")
    }

    @Test
    fun aStateRepeatedDuringTheSilenceItWasReportedInKeepsItsTime() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        bench.alive(agent(), "s-1")
        bench.clock.now += 90.seconds
        bench.alive(agent(), "s-1")
        assertNull(bench.stored().activity, "nothing to clear")
        bench.clock.now += 90.seconds
        val first = bench.activity(agent(), "s-1", WORKING, null)
        bench.clock.now += 1.seconds
        assertEquals(first.since, bench.activity(agent(), "s-1", WORKING, null).since)
        val waiting = bench.activity(agent(), "s-1", WAITING_FOR_HUMAN, null)
        bench.clock.now += 1.seconds
        assertEquals(waiting.since, bench.activity(agent(), "s-1", WAITING_FOR_HUMAN, null).since)
    }

    @Test
    fun aWorkingRepeatedDuringASilenceStartsAgain() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        bench.alive(agent(), "s-1")
        val first = bench.activity(agent(), "s-1", WORKING, null)
        bench.clock.now += 90.seconds
        val again = bench.activity(agent(), "s-1", WORKING, null)
        assertEquals(bench.clock.now, again.since, "not ${first.since}: older than the silence")
    }

    @Test
    fun aSegmentReopenedAfterALostOneWaitsAsItDidAndForgetsAWorking() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        bench.alive(agent(), "s-1")
        bench.activity(agent(), "s-1", WAITING_FOR_HUMAN, bench.clock.now)
        val waitingSince = bench.clock.now
        bench.clock.now += 3.minutes
        bench.sweep()
        val reopened = bench.reopen(agent(), "s-1").session
        assertEquals(WAITING_FOR_HUMAN to waitingSince, reopened.activity to reopened.activityAt)
        assertEquals(WAITING_FOR_HUMAN, bench.stored(reopened.id).activity)

        bench.activity(agent(), reopened.id, WORKING, null)
        bench.alive(agent(), reopened.id)
        bench.clock.now += 3.minutes
        bench.sweep()
        val next = bench.reopen(agent(), reopened.id).session
        assertNull(next.activity, "a working is a guess after the end")
    }

    @Test
    fun anEndedSegmentAnotherPersonAndAnUnknownOneAreRefused() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        assertFailsWith<NotYourSessionException> { bench.activity(agent("launch-2"), "s-1", WORKING, null) }
        assertFailsWith<NotYourSessionException> { bench.activity(user("mia"), "s-1", WORKING, null) }
        assertFailsWith<SessionNotFoundException> { bench.activity(agent(), "s-9", WORKING, null) }
        bench.end(agent(), "s-1", EndSession())
        val ended = assertFailsWith<SessionEndedException> { bench.activity(agent(), "s-1", WORKING, null) }
        assertEquals(ErrorKind.CONFLICT to "session_ended", ended.kind to ended.type)
    }

    @Test
    fun onlyTheTwoStatesAreRead() {
        assertEquals(WORKING, SessionActivityState.parse("working"))
        assertEquals(WAITING_FOR_HUMAN, SessionActivityState.parse("waiting_for_human"))
        val refused = assertFailsWith<InvalidSessionActivityException> { SessionActivityState.parse("idle") }
        assertEquals("invalid_session_activity", refused.type)
        assertNull(SessionActivityState.stored("idle"))
        assertNull(SessionActivityState.stored(null))
    }
}
