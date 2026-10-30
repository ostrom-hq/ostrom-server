package dev.ostrom.server.sessions

import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.NotYourSessionException
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionClosedException
import dev.ostrom.server.sessions.domain.models.SessionNotFoundException
import dev.ostrom.server.sessions.domain.models.SessionQuery
import dev.ostrom.server.sessions.domain.models.SessionResumedElsewhereException
import dev.ostrom.server.sessions.domain.models.StartSession
import dev.ostrom.server.sessions.domain.usecases.CloseSessionUseCase
import dev.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCaseImpl
import dev.ostrom.server.sessions.fakes.ORG
import dev.ostrom.server.sessions.fakes.OTHER_ORG
import dev.ostrom.server.sessions.fakes.SessionBench
import dev.ostrom.server.sessions.fakes.agent
import dev.ostrom.server.sessions.fakes.user
import dev.ostrom.server.sessions.infrastructure.SessionsGateGuard
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SessionLifecycleTest {
    private val start = StartSession(id = "s-1", agent = "claude-code", repo = "acme/api", packageDigest = "pkg")

    @Test
    fun onlyTheSessionsPersonAndLaunchWriteIntoIt() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        assertFailsWith<NotYourSessionException> { bench.end(agent(person = "mallory"), session.id, EndSession()) }
        assertFailsWith<NotYourSessionException> { bench.end(agent("launch-2"), session.id, EndSession()) }
        assertFailsWith<NotYourSessionException> { bench.alive(user("rev-b"), session.id) }
        assertFailsWith<SessionNotFoundException> { bench.end(agent(org = OTHER_ORG), session.id, EndSession()) }
        assertFailsWith<SessionNotFoundException> { bench.alive(agent(), "nope") }
        assertFailsWith<SessionNotFoundException> { bench.reopen(agent(), "nope") }
        assertFailsWith<SessionNotFoundException> { bench.seen(agent(), "nope", "skill", "x", "hook") }
        assertNull(bench.repository.get(ORG, session.id)?.endedAt)
        assertEquals("rcpt_session_s-1", bench.end(user("lyes"), session.id, EndSession()).receiptId)
    }

    @Test
    fun aSignOfLifeKeepsTheSessionOpenAndTellsAnEndedOneWhy() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        val seen = bench.alive(agent(), session.id)
        assertEquals(bench.clock.now, seen.lastSeenAt)
        assertFalse(seen.lost)

        bench.clock.now += 3.minutes
        assertEquals(listOf("s-1"), bench.sweep())
        val ended = bench.alive(agent(), session.id)
        assertTrue(ended.lost, "Closed for lack of a sign of life: the launcher reopens a segment")
        assertEquals("launcher lost: no sign of life since 2026-10-30T09:00:00.123Z", ended.source)
        assertEquals(ended, bench.alive(agent(), session.id), "An ended session is not touched")
        assertFalse(bench.end(agent(), bench.start(agent(), start.copy(id = "s-2")).id, EndSession()).lost)
    }

    @Test
    fun theLauncherOfALostSessionReopensTheNextSegment() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        assertEquals(session, bench.reopen(agent(), session.id).session, "An open session is already open")
        assertFalse(bench.reopen(agent(), session.id).reopened)

        bench.alive(agent(), session.id)
        bench.clock.now += 3.minutes
        bench.sweep()
        val reopened = bench.reopen(agent(), session.id)
        assertTrue(reopened.reopened)
        val next = reopened.session
        assertEquals("s-1~r2", next.id)
        assertEquals("launch-1", next.launchId)
        assertEquals("pkg", next.packageDigest)
        assertEquals("rcpt_session_s-1", next.previousReceiptId)
        assertEquals(bench.clock.now, next.lastSeenAt, "A reopened segment is followed at once")
        assertTrue(next.source.orEmpty().startsWith("reopened: the launcher came back after \"launcher lost"))

        val again = bench.reopen(agent(), session.id)
        assertEquals(next, again.session, "Reopening twice gives the same segment")
        assertFalse(again.reopened)
        assertFailsWith<NotYourSessionException> { bench.reopen(agent("launch-2"), session.id) }
    }

    @Test
    fun aSessionResumedByAnotherLaunchIsNotReopenedHere() = runTest {
        val bench = SessionBench()
        val first = bench.start(agent(), start)
        bench.end(agent(), first.id, EndSession(source = "hook"))
        bench.start(agent("launch-2"), start)
        val error = assertFailsWith<SessionResumedElsewhereException> { bench.reopen(agent(), first.id) }
        assertEquals(ErrorKind.CONFLICT, error.kind)
        assertEquals("session s-1 was resumed by another launch (s-1~r2): this launch cannot reopen it", error.message)

        val solo = bench.start(user("rev-b"), start.copy(id = "s-9"))
        bench.end(user("rev-b"), solo.id, EndSession())
        val reopened = bench.reopen(user("rev-b"), solo.id).session
        assertEquals("reopened: the launcher came back after \"ended\"", reopened.source)
    }

    @Test
    fun theSkillsAndAgentsOpenedAreRecordedOnce() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        bench.seen(agent(), session.id, "skill", "deploy", "hook")
        bench.seen(agent(), session.id, "skill", "deploy", "mcp")
        bench.seen(agent(), session.id, "agent", "deploy", "mcp")
        val seen = bench.repository.get(ORG, session.id)?.seen.orEmpty()
        assertEquals(listOf("skill" to "hook", "agent" to "mcp"), seen.map { it.kind to it.via })

        repeat(250) { bench.seen(agent(), session.id, "skill", "s-$it", "hook") }
        assertEquals(200, bench.repository.get(ORG, session.id)?.seen?.size, "At most 200 per session")

        bench.end(agent(), session.id, EndSession())
        val closed = assertFailsWith<SessionClosedException> { bench.seen(agent(), session.id, "skill", "x", "hook") }
        assertTrue(closed.message.orEmpty().startsWith("session closed: s-1 ended at "))
        assertTrue("POST /api/v1/sessions/s-1/reopen" in closed.message.orEmpty())
    }

    @Test
    fun aSessionIsReadWithItsGatesAndReceipt() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        val gate = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
        bench.gates.gates["s-1"] =
            listOf(SessionGate(gate, "push-main", GateMode.ENFORCED, GateStatus.PENDING, null, emptyList()))
        val open = bench.get(user("rev-b"), session.id)
        assertEquals(listOf(gate.toString()), open.gates)
        assertNull(open.receipt)

        bench.end(agent(), session.id, EndSession())
        assertEquals("rcpt_session_s-1", bench.get(agent(), session.id).receipt?.get("id")?.toString()?.trim('"'))
        assertFailsWith<SessionNotFoundException> { bench.get(agent("launch-2"), session.id) }
        assertFailsWith<SessionNotFoundException> { bench.get(user(org = OTHER_ORG), session.id) }
    }

    @Test
    fun anAgentTokenListsItsOwnLaunchOnly() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        bench.clock.now += 1.seconds
        bench.start(agent("launch-2"), start.copy(id = "s-2"))
        assertEquals(listOf("s-2", "s-1"), bench.list(user(), SessionQuery(limit = 500)).map { it.id })
        assertEquals(listOf("s-1"), bench.list(agent(), SessionQuery(launchId = "launch-2")).map { it.id })
        assertEquals(listOf("s-2"), bench.list(user(), SessionQuery(limit = 0)).map { it.id })
    }

    @Test
    fun theSweepClosesOnlyFollowedSilentSessionsAndKeepsGoing() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        bench.alive(agent(), "s-1")
        bench.start(agent("launch-2"), start.copy(id = "s-2"))
        bench.alive(agent("launch-2"), "s-2")
        bench.start(agent("launch-3"), start.copy(id = "s-3"))
        bench.clock.now += 119.seconds
        assertEquals(emptyList(), bench.sweep())
        assertEquals(bench.clock.now - 120.seconds, bench.repository.staleCutoff)
        assertTrue(bench.logger.lines.isEmpty())

        bench.clock.now += 2.seconds
        assertEquals(listOf("s-1", "s-2"), bench.sweep())
        assertEquals(emptyList(), bench.sweep(), "A closed session is closed once")
        assertEquals("sessions closed, their launcher stopped answering", bench.logger.lines.single().first)
        assertNull(bench.repository.get(ORG, "s-3")?.endedAt, "Never a sign of life: not followed")
    }

    @Test
    fun aSweepFailureIsLoggedAndTheOthersClosed() = runTest {
        val bench = SessionBench()
        for (id in listOf("s-1", "s-2")) {
            bench.start(agent(id), start.copy(id = id))
            bench.alive(agent(id), id)
        }
        bench.clock.now += 3.minutes
        val failing = SweepStaleSessionsUseCaseImpl(
            bench.repository,
            object : CloseSessionUseCase {
                override suspend fun invoke(orgId: Uuid, id: String, end: EndSession): Session =
                    if (id == "s-1") error("boom") else bench.close(orgId, id, end)
            },
            bench.clock,
            bench.logger,
        )
        assertEquals(listOf("s-2"), failing())
        assertEquals("lost session not closed", bench.logger.lines.first().first)
        assertEquals("boom", bench.logger.lines.first().second?.message)
    }

    @Test
    fun aGateIsAskedOnlyInAnOpenSessionOfItsLaunch() = runTest {
        val bench = SessionBench()
        val guard = SessionsGateGuard(bench.repository)
        val session = bench.start(agent(), start)
        guard.assertOpen(ORG, session.id, "launch-1")
        guard.assertOpen(ORG, session.id, null)
        assertEquals("claude-code", guard.agentOf(ORG, session.id))
        assertNull(guard.agentOf(OTHER_ORG, session.id))
        assertFailsWith<NotYourSessionException> { guard.assertOpen(ORG, session.id, "launch-2") }
        assertFailsWith<SessionNotFoundException> { guard.assertOpen(OTHER_ORG, session.id, null) }

        bench.end(agent(), session.id, EndSession())
        val closed = assertFailsWith<SessionClosedException> { guard.assertOpen(ORG, session.id, "launch-1") }
        assertEquals(ErrorKind.CONFLICT, closed.kind)
        assertTrue("(ended)" in closed.message.orEmpty(), closed.message)
        assertTrue(Session.LOST.isNotEmpty())
    }
}
