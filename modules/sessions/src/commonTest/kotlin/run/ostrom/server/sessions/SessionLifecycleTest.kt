// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sessions

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.services.OpenSegment
import run.ostrom.server.gates.domain.usecases.SessionGate
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.sessions.domain.models.EndSession
import run.ostrom.server.sessions.domain.models.NotYourSessionException
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionClosedException
import run.ostrom.server.sessions.domain.models.SessionNotFoundException
import run.ostrom.server.sessions.domain.models.SessionQuery
import run.ostrom.server.sessions.domain.models.SessionResumedElsewhereException
import run.ostrom.server.sessions.domain.models.StartSession
import run.ostrom.server.sessions.domain.repositories.SessionsTransaction
import run.ostrom.server.sessions.domain.services.NoSessionCosts
import run.ostrom.server.sessions.domain.usecases.CloseSessionUseCase
import run.ostrom.server.sessions.domain.usecases.EndSessionUseCaseImpl
import run.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCaseImpl
import run.ostrom.server.sessions.fakes.ORG
import run.ostrom.server.sessions.fakes.OTHER_ORG
import run.ostrom.server.sessions.fakes.SessionBench
import run.ostrom.server.sessions.fakes.agent
import run.ostrom.server.sessions.fakes.member
import run.ostrom.server.sessions.fakes.user
import run.ostrom.server.sessions.infrastructure.SessionsAgentDirectory
import run.ostrom.server.sessions.infrastructure.SessionsGateGuard
import run.ostrom.server.sessions.infrastructure.SessionsReceiptOwners
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SessionLifecycleTest {
    private val start = StartSession(id = "s-1", agent = "claude-code", repo = "acme/api", packageDigest = "pkg")

    @Test
    fun s248AnAgentTokenWithoutItsLaunchReadsNothing() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        val launchless = GateActor("agent", ORG, ActorKind.AGENT_SESSION, personId = "lyes")
        assertFailsWith<SessionNotFoundException> { bench.get(launchless, session.id) }
        assertTrue(bench.list(launchless, SessionQuery()).isEmpty(), "not every session of its person")
        assertFailsWith<NotYourSessionException> { bench.end(launchless, session.id, EndSession()) }
    }

    @Test
    fun s248TheCostIsTheServersNeverTheAgents() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        bench.costs.cost = 0.42
        val ended = bench.end(agent(), session.id, EndSession(costUsd = 99.0))
        assertEquals(0.42, ended.costUsd)
        assertEquals("0.42", bench.sign.signed.getValue("rcpt_session_${session.id}")["costUsd"].toString())
    }

    @Test
    fun withoutTheUsageModuleNoCostIsKnown() = runTest {
        assertNull(NoSessionCosts().costOf(ORG, "lch_1", Instant.DISTANT_PAST, Instant.DISTANT_FUTURE))
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        assertNull(bench.end(agent(), session.id, EndSession(costUsd = 99.0)).costUsd, "never the agent's figure")
        assertFalse("costUsd" in bench.sign.signed.getValue("rcpt_session_${session.id}"))
    }

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
        assertTrue(bench.endings.isEmpty(), "A lost launcher reopens: its session has not ended")
        val reopened = bench.reopen(agent(), session.id)
        assertTrue(reopened.reopened)
        val next = reopened.session
        assertEquals("s-1~r2", next.id)
        assertEquals("launch-1", next.launchId)
        assertEquals("pkg", next.packageDigest)
        assertEquals("rcpt_session_s-1", next.previousReceiptId)
        val now = Instant.fromEpochMilliseconds(bench.clock.now.toEpochMilliseconds())
        assertEquals(now, next.lastSeenAt, "A reopened segment is followed at once, to the millisecond")
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
        val open = bench.get(member("rev-b", OrgRole.ADMIN), session.id)
        assertEquals(listOf(gate.toString()), open.gates)
        assertNull(open.receipt)

        bench.end(agent(), session.id, EndSession())
        assertEquals("rcpt_session_s-1", bench.get(agent(), session.id).receipt?.get("id")?.toString()?.trim('"'))
        assertFailsWith<SessionNotFoundException> { bench.get(agent("launch-2"), session.id) }
        assertFailsWith<SessionNotFoundException> { bench.get(user(org = OTHER_ORG), session.id) }
    }

    @Test
    fun aMemberReadsTheirOwnSessionsAndOnlyAnOwnerOrAnAdminReadsTheTeams() = runTest {
        // TeamCode #238: a session is its member's; a role other than owner or admin opens nobody else's.
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        bench.clock.now += 1.seconds
        bench.start(agent("launch-2", person = "mia"), start.copy(id = "s-2"))
        assertEquals(session.id, bench.get(member("lyes"), session.id).session.id)
        listOf(OrgRole.MEMBER, OrgRole.APPROVER, OrgRole.BILLING).forEach { role ->
            assertFailsWith<SessionNotFoundException>(role.name) { bench.get(member("rev-b", role), session.id) }
            assertEquals(emptyList(), bench.list(member("rev-b", role), SessionQuery()), role.name)
        }
        listOf(OrgRole.OWNER, OrgRole.ADMIN).forEach { role ->
            assertEquals(session.id, bench.get(member("rev-b", role), session.id).session.id, role.name)
            assertEquals(listOf("s-2", "s-1"), bench.list(member("rev-b", role), SessionQuery()).map { it.id })
            val lyes = bench.list(member("rev-b", role), SessionQuery(dev = "lyes"))
            assertEquals(listOf("s-1"), lyes.map { it.id })
        }
        assertEquals(listOf("s-2"), bench.list(member("mia"), SessionQuery()).map { it.id })
        assertEquals(listOf("s-2"), bench.list(member("mia"), SessionQuery(dev = "mia")).map { it.id })
        assertFailsWith<NotYourSessionException> { bench.list(member("mia"), SessionQuery(dev = "lyes")) }
        // Another's session as a cursor names nothing the member reads: a 400, as a forged one, before any page.
        assertFailsWith<InvalidPageException> { bench.list(member("mia"), SessionQuery(before = "s-1")) }
        val nobody = GateActor("nobody", ORG, ActorKind.USER)
        assertFailsWith<NotYourSessionException>("No person, no session") { bench.list(nobody, SessionQuery()) }
        assertFailsWith<NotYourSessionException> { bench.end(member("rev-b", OrgRole.ADMIN), session.id, EndSession()) }
    }

    @Test
    fun aReceiptAboutASessionBelongsToItsPerson() = runTest {
        // TeamCode #238: the receipts read who owns the session a receipt is about; nobody owns an unknown one.
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        val owners = SessionsReceiptOwners(bench.repository)
        assertEquals("lyes", owners.ownerOf(ORG, session.id))
        assertNull(owners.ownerOf(ORG, "nope"))
        assertNull(owners.ownerOf(OTHER_ORG, session.id))
    }

    @Test
    fun anApproverOpensTheSessionBehindTheGateTheyAnswerReadOnly() = runTest {
        // Parity TC-NEW-675a530-2, TeamCode waits.md: the one a gate was sent to reads what they judge, nothing more.
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        bench.clock.now += 1.seconds
        bench.start(agent("launch-2", person = "mia"), start.copy(id = "s-2"))
        bench.approvers.sessions["rev-b"] = listOf(session.id)

        assertEquals(session.id, bench.get(member("rev-b"), session.id).session.id, "The session behind their gate")
        assertFailsWith<SessionNotFoundException>("Another session") { bench.get(member("rev-b"), "s-2") }
        assertFailsWith<SessionNotFoundException>("Another organization") {
            bench.get(member("rev-b", org = OTHER_ORG), session.id)
        }
        assertEquals(emptyList(), bench.list(member("rev-b"), SessionQuery()), "Never in their list")
        assertFailsWith<InvalidPageException>("Nor a cursor of theirs") {
            bench.list(member("rev-b"), SessionQuery(before = session.id))
        }
        assertFailsWith<NotYourSessionException> { bench.end(member("rev-b"), session.id, EndSession()) }
        assertFailsWith<NotYourSessionException> { bench.alive(member("rev-b"), session.id) }
        assertFailsWith<NotYourSessionException> { bench.reopen(member("rev-b"), session.id) }
        assertFailsWith<NotYourSessionException> { bench.seen(member("rev-b"), session.id, "skill", "x", "hook") }
        assertNull(bench.repository.get(ORG, session.id)?.endedAt)
    }

    @Test
    fun onlyAPersonApprovesNeitherATokenNorACredentialWithoutALogin() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        bench.approvers.sessions["lyes"] = listOf(session.id)
        bench.approvers.sessions["rev-b"] = listOf(session.id)
        assertFailsWith<SessionNotFoundException>("Another launch's agent token") {
            bench.get(agent("launch-2", person = "rev-b"), session.id)
        }
        assertFailsWith<SessionNotFoundException>("A device") {
            bench.get(GateActor("phone", ORG, ActorKind.DEVICE, personId = "rev-b"), session.id)
        }
        assertFailsWith<SessionNotFoundException>("No login") {
            bench.get(GateActor("nobody", ORG, ActorKind.USER), session.id)
        }
        assertFailsWith<SessionNotFoundException>("No such session") { bench.get(member("rev-b"), "nope") }
        assertEquals(session.id, bench.get(member("lyes"), session.id).session.id)
        assertEquals(session.id, bench.get(member("root", OrgRole.ADMIN), session.id).session.id)
        assertEquals(emptyList(), bench.approvers.asked, "Asked only of a person who does not read it already")
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
        // After a cursor: the older sessions, none after the last; a session the organization does not hold is a 400.
        assertEquals(listOf("s-1"), bench.list(user(), SessionQuery(before = "s-2")).map { it.id })
        assertEquals(emptyList(), bench.list(user(), SessionQuery(before = "s-1")))
        assertFailsWith<InvalidPageException> { bench.list(user(), SessionQuery(before = "nope")) }
        assertFailsWith<InvalidPageException> { bench.list(user(org = OTHER_ORG), SessionQuery(before = "s-1")) }
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

                override suspend fun closeLocked(tx: SessionsTransaction, session: Session, end: EndSession) =
                    bench.close.closeLocked(tx, session, end)
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

    @Test
    fun theOpenSegmentIsFoundFromAnyIdOfTheAgentSessionWithItsLaunchsSegments() = runTest {
        val bench = SessionBench()
        val guard = SessionsGateGuard(bench.repository)
        val session = bench.start(agent(), start)
        assertEquals(OpenSegment("s-1", listOf("s-1")), guard.openSegment(ORG, "s-1", "launch-1"), "Itself")

        bench.end(agent(), session.id, EndSession())
        val closed = assertFailsWith<SessionClosedException> { guard.openSegment(ORG, "s-1", "launch-1") }
        assertTrue("(ended)" in closed.message.orEmpty(), "Nothing open yet: the last segment's refusal")
        bench.reopen(agent(), session.id)
        val reopened = OpenSegment("s-1~r2", listOf("s-1", "s-1~r2"))
        assertEquals(reopened, guard.openSegment(ORG, "s-1", "launch-1"), "The agent's id, its segment reopened")
        assertEquals(reopened, guard.openSegment(ORG, "s-1~r2", null))
        bench.end(agent(), "s-1~r2", EndSession())

        // Resumed by another launch: its own segment only, never what the first launch opened.
        bench.start(agent("launch-2"), start)
        assertEquals(OpenSegment("s-1~r3", listOf("s-1~r3")), guard.openSegment(ORG, "s-1~r2", "launch-2"))
        assertFailsWith<NotYourSessionException> { guard.openSegment(ORG, "s-1", "launch-1") }
        assertFailsWith<SessionNotFoundException> { guard.openSegment(OTHER_ORG, "s-1", "launch-2") }
        assertFailsWith<SessionNotFoundException> { guard.openSegment(ORG, "s-9", "launch-2") }
    }

    @Test
    fun theSessionEndsForItsListenersOnlyOnceNoSegmentIsOpen() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        bench.end(agent(), session.id, EndSession())
        assertEquals(listOf(ORG to listOf("s-1")), bench.endings)
        bench.reopen(agent(), session.id)
        // The first segment's end sent again while the second is open: the session goes on.
        bench.end(agent(), "s-1", EndSession())
        assertEquals(1, bench.endings.size)
        bench.end(agent(), "s-1~r2", EndSession())
        assertEquals(ORG to listOf("s-1", "s-1~r2"), bench.endings.last())
    }

    @Test
    fun withoutListenersAnEndReadsNoSegment() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)
        val end = EndSessionUseCaseImpl(bench.repository, bench.close)
        assertEquals("rcpt_session_s-1", end(agent(), session.id, EndSession()).receiptId)
    }

    @Test
    fun anAgentSessionIsFoundWithItsOwnerFromAnyOfItsSegments() = runTest {
        val bench = SessionBench()
        val directory = SessionsAgentDirectory(bench.repository)
        assertNull(directory.find(ORG, "s-1"))
        val session = bench.start(agent(), start)
        assertEquals(AgentSession(session.dev, true, listOf("s-1")), directory.find(ORG, "s-1"))
        bench.end(agent(), session.id, EndSession())
        bench.reopen(agent(), session.id)
        assertEquals(AgentSession(session.dev, true, listOf("s-1", "s-1~r2")), directory.find(ORG, "s-1"))
        bench.end(agent(), "s-1~r2", EndSession())
        assertEquals(false, directory.find(ORG, "s-1~r2")?.open)
        assertNull(directory.find(OTHER_ORG, "s-1"))
    }
}
