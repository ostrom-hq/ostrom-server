// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AnswerWaitUseCaseImpl
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCaseImpl
import run.ostrom.server.automations.domain.CancelSessionWaitsUseCaseImpl
import run.ostrom.server.automations.domain.InvalidAssigneeException
import run.ostrom.server.automations.domain.InvalidWaitAnswerException
import run.ostrom.server.automations.domain.InvalidWaitException
import run.ostrom.server.automations.domain.ReadWaitsUseCaseImpl
import run.ostrom.server.automations.domain.SessionNotRunningException
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitClosedException
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitForbiddenException
import run.ostrom.server.automations.domain.WaitKeyConflictException
import run.ostrom.server.automations.domain.WaitLimitException
import run.ostrom.server.automations.domain.WaitNotFoundException
import run.ostrom.server.automations.domain.WaitPage
import run.ostrom.server.automations.domain.WaitQuery
import run.ostrom.server.automations.domain.WaitSessionNotFoundException
import run.ostrom.server.automations.domain.WaitSessionOwnerOnlyException
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.CountingSecrets
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.CAROL
import run.ostrom.server.automations.fakes.DAVE
import run.ostrom.server.automations.fakes.ENDED_SESSION
import run.ostrom.server.automations.fakes.InMemoryWaits
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.RecordingEvents
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.TestMembers
import run.ostrom.server.automations.fakes.UNKNOWN_SESSION
import run.ostrom.server.automations.fakes.alice
import run.ostrom.server.automations.fakes.bob
import run.ostrom.server.automations.fakes.carol
import run.ostrom.server.automations.fakes.dave
import run.ostrom.server.automations.fakes.daveKey
import run.ostrom.server.automations.fakes.testWaitSessions
import run.ostrom.server.kernel.errors.InvalidPageException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class WaitUseCasesTest {
    private val repository = InMemoryWaits()
    private val events = RecordingEvents()
    private val clock = TestClock()
    private val ask = AskWaitUseCaseImpl(repository, TestMembers, events, clock, testWaitSessions, CountingSecrets())
    private val read = ReadWaitsUseCaseImpl(repository, testWaitSessions)
    private val answers = AnswerWaitUseCaseImpl(repository, events, clock)

    private val review = WaitSpec(
        "Review the plan",
        fields = listOf(WaitField("feedback", WaitFieldType.TEXT, "Feedback")),
        actions = listOf(WaitAction("approve", "Approve"), WaitAction("changes", "Changes", listOf("feedback"))),
    )

    private suspend fun asked(
        caller: WaitCaller = bob,
        assignee: String? = null,
        session: String? = if (caller == alice) "alice-1" else "session-1",
        key: String? = null,
    ): Wait = ask.ask(caller, AskWait(review, session, "run-1", assignee), key).wait.also { clock.now += 1.minutes }

    @Test
    fun anAgentAsksItsOwnerByDefaultOrAnotherMember() = runTest {
        val own = asked()
        assertEquals(BOB.toString(), own.assignee)
        assertEquals(BOB.toString(), own.createdBy)
        assertEquals(WaitStatus.OPEN, own.status)
        assertEquals("human_request", own.kind)
        assertEquals("session-1", own.sessionId)
        assertEquals("run-1", own.runId)
        assertEquals(clock.now - 1.minutes, own.createdAt)
        assertEquals(ALICE.toString(), asked(assignee = ALICE.toString()).assignee)
        assertEquals(BOB.toString(), asked(assignee = BOB.toString()).assignee)
        assertFailsWith<InvalidAssigneeException> { asked(assignee = CAROL.toString()) }
        assertEquals(listOf("created", "created", "created"), events.seen.map { it.first })
        // An agent asks a colleague as well, by id or by username, any case.
        val agent = bob.copy(interactive = false, agent = true)
        assertEquals(ALICE.toString(), asked(caller = agent, assignee = ALICE.toString()).assignee)
        assertEquals(ALICE.toString(), asked(caller = agent, assignee = "alice").assignee)
        assertEquals(DAVE.toString(), asked(caller = agent, assignee = "Dave").assignee)
        assertFailsWith<InvalidAssigneeException> { asked(caller = agent, assignee = "carol") }
        assertFailsWith<InvalidAssigneeException> { asked(assignee = "x".repeat(65)) }
        val explicit = ask.ask(bob, AskWait(review, kind = "human_request"), null).wait
        assertNull(explicit.sessionId)
    }

    @Test
    fun aResumedSessionsSegmentIsAsked() = runTest {
        // a session resumed by another launch is the segment `<id>~r<n>` the sessions module opened
        val asked = ask.ask(bob, AskWait(review, sessionId = "claude-session-1~r3"), null).wait
        assertEquals("claude-session-1~r3", asked.sessionId)
    }

    @Test
    fun anAgentNeverAnswersEvenTheQuestionItAskedItsPerson() = runTest {
        val agent = bob.copy(interactive = false, agent = true)
        val asked = ask.ask(agent, AskWait(review), null).wait
        val refused = assertFailsWith<WaitForbiddenException> { answers.answer(agent, asked.id, "done", emptyMap()) }
        assertEquals("An agent never answers a wait: its person does.", refused.message)
        assertEquals(WaitStatus.OPEN, repository.waits.getValue(asked.id).status)
    }

    @Test
    fun malformedAsksAreRefused() = runTest {
        assertFailsWith<InvalidWaitException> { ask.ask(bob, AskWait(review, kind = "turn_failed"), null) }
        assertFailsWith<InvalidWaitException> { ask.ask(bob, AskWait(review, sessionId = "a b"), null) }
        assertFailsWith<InvalidWaitException> { ask.ask(bob, AskWait(review, runId = ""), null) }
        assertFailsWith<InvalidWaitException> { ask.ask(bob, AskWait(review), "a key") }
        assertFailsWith<InvalidWaitException> { ask.ask(bob, AskWait(WaitSpec(" ")), null) }
        assertTrue(repository.waits.isEmpty())
    }

    @Test
    fun anIdempotencyKeyReturnsTheFirstWait() = runTest {
        val first = ask.ask(bob, AskWait(review, "s"), "key-1")
        assertFalse(first.replayed)
        val again = ask.ask(bob, AskWait(WaitSpec("Other"), "s"), "key-1")
        assertTrue(again.replayed)
        assertEquals(first.wait, again.wait)
        assertFalse(ask.ask(alice, AskWait(review, "alice-1"), "key-1").replayed)

        val raced = first.wait.copy(id = Uuid.random(), idempotencyKey = "key-2")
        repository.duplicateNext = raced
        val duplicate = ask.ask(bob, AskWait(review, "s"), "key-2")
        assertTrue(duplicate.replayed)
        assertEquals(raced, duplicate.wait)

        repository.duplicateNext = raced.copy(idempotencyKey = "other")
        assertFailsWith<WaitKeyConflictException> { ask.ask(bob, AskWait(review, "s"), "key-3") }
    }

    @Test
    fun openWaitsAreLimitedPerSessionAndPerAsker() = runTest {
        repeat(20) { asked() }
        assertTrue("20" in assertFailsWith<WaitLimitException> { asked() }.message)
        asked(session = "session-2")
        repeat(100) { asked(session = null) }
        assertTrue("100" in assertFailsWith<WaitLimitException> { asked(session = null) }.message)
        asked(caller = alice, session = null)
    }

    @Test
    fun aWaitIsReadByItsAssigneeItsAskerAndAnAdministrator() = runTest {
        val wait = asked(caller = bob, assignee = ALICE.toString())
        assertEquals(wait, read.get(alice, wait.id))
        assertEquals(wait, read.get(bob, wait.id))
        assertEquals(wait, read.get(dave, wait.id))
        val other = asked(caller = alice)
        assertFailsWith<WaitNotFoundException> { read.get(bob, other.id) }
        assertFailsWith<WaitNotFoundException> { read.get(carol, wait.id) }
        assertFailsWith<WaitNotFoundException> { read.get(bob, Uuid.random()) }
    }

    @Test
    fun listingsShowWhatWaitsForTheCaller() = runTest {
        val forAlice = asked(caller = bob, assignee = ALICE.toString())
        val forBob = asked(caller = alice, assignee = BOB.toString(), session = "alice-2")
        val aliceOwn = asked(caller = alice)
        fun ids(page: WaitPage) = page.items.map { it.id }

        assertEquals(listOf(forBob.id), ids(read.list(bob, WaitQuery(mine = true))))
        assertEquals(listOf(forBob.id, forAlice.id), ids(read.list(bob, WaitQuery())))
        assertEquals(listOf(forBob.id), ids(read.list(bob, WaitQuery(assignee = BOB.toString()))))
        assertEquals(listOf(forBob.id), ids(read.list(bob, WaitQuery(mine = true, assignee = "all"))))
        assertFailsWith<WaitForbiddenException> { read.list(bob, WaitQuery(assignee = "all")) }
        assertFailsWith<WaitForbiddenException> { read.list(bob, WaitQuery(assignee = ALICE.toString())) }
        assertEquals(3, read.list(dave, WaitQuery(assignee = "all")).items.size)
        assertEquals(listOf(aliceOwn.id, forAlice.id), ids(read.list(dave, WaitQuery(assignee = ALICE.toString()))))
        assertEquals(listOf(forBob.id), ids(read.list(alice, WaitQuery(sessionId = "alice-2"))))
        // A session's waits are listed for its person only, an administrator included.
        assertFailsWith<WaitSessionNotFoundException> {
            read.list(dave, WaitQuery(assignee = "all", sessionId = "alice-2"))
        }
        assertFailsWith<WaitSessionNotFoundException> { read.list(bob, WaitQuery(sessionId = UNKNOWN_SESSION)) }
        val agent = bob.copy(interactive = false, agent = true)
        assertEquals(emptyList(), ids(read.list(agent, WaitQuery(sessionId = "alice-2"))), "the agent's own asks only")
        assertEquals(3, read.list(dave, WaitQuery(assignee = "all", runId = "run-1")).items.size)

        answers.cancel(alice, aliceOwn.id)
        assertEquals(listOf(forBob.id, forAlice.id), ids(read.list(alice, WaitQuery())))
        assertEquals(listOf(aliceOwn.id), ids(read.list(alice, WaitQuery(status = "cancelled"))))
        assertEquals(3, read.list(alice, WaitQuery(status = "all")).items.size)
        assertEquals(emptyList(), ids(read.list(alice, WaitQuery(status = "resolved"))))
        assertFailsWith<InvalidWaitException> { read.list(alice, WaitQuery(status = "done")) }
        assertFailsWith<InvalidWaitException> { read.list(alice, WaitQuery(limit = 0)) }
        assertFailsWith<InvalidWaitException> { read.list(alice, WaitQuery(limit = 101)) }

        val first = read.list(dave, WaitQuery(assignee = "all", status = "all", limit = 2))
        assertEquals(listOf(aliceOwn.id, forBob.id), ids(first))
        assertEquals(forBob.id, first.next)
        val second = read.list(dave, WaitQuery(assignee = "all", status = "all", limit = 2, before = first.next))
        assertEquals(listOf(forAlice.id), ids(second))
        assertNull(second.next)
        val all = WaitQuery(assignee = "all", status = "all")
        assertEquals(emptyList(), ids(read.list(dave, all.copy(before = forAlice.id))), "After the last")
        assertFailsWith<InvalidPageException> { read.list(dave, all.copy(before = Uuid.random())) }
    }

    private val approve = emptyMap<String, JsonElement>()
    private val changes = mapOf<String, JsonElement>("feedback" to JsonPrimitive("Split it"))

    @Test
    fun theAssigneeAnswersAndTheFirstAnswerWins() = runTest {
        val wait = asked(caller = bob, assignee = ALICE.toString())
        assertFailsWith<WaitForbiddenException> { answers.answer(bob, wait.id, "approve", approve) }
        assertFailsWith<InvalidWaitAnswerException> { answers.answer(alice, wait.id, "changes", approve) }
        val resolved = answers.answer(alice, wait.id, "changes", changes)
        assertEquals(WaitStatus.RESOLVED, resolved.status)
        assertEquals("changes", resolved.answer?.action)
        assertEquals(changes, resolved.answer?.values)
        assertEquals(ALICE.toString(), resolved.answer?.author)
        assertEquals(clock.now, resolved.resolvedAt)
        assertEquals(resolved, answers.answer(alice, wait.id, "changes", changes))
        assertFailsWith<WaitClosedException> { answers.answer(alice, wait.id, "approve", approve) }
        assertFailsWith<WaitClosedException> { answers.answer(alice, wait.id, "changes", approve) }
        assertFailsWith<WaitClosedException> { answers.answer(dave, wait.id, "changes", changes) }
        assertEquals(listOf("created", "resolved"), events.seen.map { it.first })
    }

    @Test
    fun anAdministratorAnswersOnlyWhenSignedInThemselves() = runTest {
        val wait = asked(caller = bob, assignee = ALICE.toString())
        assertFailsWith<WaitForbiddenException> { answers.answer(daveKey, wait.id, "approve", approve) }
        assertEquals(dave.memberId, answers.answer(dave, wait.id, "approve", approve).answer?.author)
    }

    @Test
    fun anAnswerThatLosesTheRaceLearnsTheWaitIsClosed() = runTest {
        val wait = asked(caller = bob, assignee = ALICE.toString())
        repository.loseNextAnswer = true
        assertFailsWith<WaitClosedException> { answers.answer(alice, wait.id, "approve", approve) }
        assertEquals(listOf("created"), events.seen.map { it.first })
        assertFailsWith<WaitClosedException> { answers.answer(alice, wait.id, "approve", approve) }
    }

    @Test
    fun theAskerTheAssigneeOrAnAdministratorCancels() = runTest {
        val first = asked(caller = bob, assignee = ALICE.toString())
        assertEquals(WaitStatus.CANCELLED, answers.cancel(bob, first.id).status)
        assertFailsWith<WaitClosedException> { answers.cancel(bob, first.id) }
        val second = asked(caller = bob, assignee = ALICE.toString())
        assertEquals(WaitStatus.CANCELLED, answers.cancel(alice, second.id).status)
        val third = asked(caller = alice)
        assertFailsWith<WaitNotFoundException> { answers.cancel(bob, third.id) }
        assertFailsWith<WaitForbiddenException> { answers.cancel(daveKey, third.id) }
        assertEquals(clock.now, answers.cancel(dave, third.id).resolvedAt)
        val cancelled = events.seen.map { it.first }.filter { it != "created" }
        assertEquals(listOf("cancelled", "cancelled", "cancelled"), cancelled)
    }

    @Test
    fun aMemberAsksOnlyInARunningSessionOfTheirs() = runTest {
        assertFailsWith<WaitSessionOwnerOnlyException> { asked(caller = alice, session = "session-1") }
        assertFailsWith<WaitSessionOwnerOnlyException> { asked(caller = dave, session = "session-1") }
        assertFailsWith<WaitSessionOwnerOnlyException> { asked(caller = bob.copy(memberId = "x"), session = "s") }
        assertFailsWith<WaitSessionNotFoundException> { asked(session = UNKNOWN_SESSION) }
        assertFailsWith<SessionNotRunningException> { asked(session = ENDED_SESSION) }
        assertTrue(repository.waits.isEmpty())
        // An agent token's session is the open segment the server found for it.
        val agent = bob.copy(interactive = false, agent = true, session = "alice-1")
        assertEquals("alice-1", asked(caller = agent, session = "alice-1").sessionId)
    }

    @Test
    fun aSessionThatEndsCancelsItsOpenWaitsOnly() = runTest {
        val cancel = CancelSessionWaitsUseCaseImpl(repository, events, clock)
        val answered = asked(assignee = ALICE.toString())
        answers.answer(alice, answered.id, "approve", approve)
        val open = asked()
        val elsewhere = asked(session = "session-2")
        val alone = asked(session = null)
        events.seen.clear()
        cancel.ended(ORG_A, emptyList())
        assertTrue(events.seen.isEmpty())
        cancel.ended(ORG_B, listOf("session-1"))
        assertTrue(events.seen.isEmpty(), "Another organization's session")
        cancel.ended(ORG_A, listOf("session-0", "session-1"))
        assertEquals(listOf("cancelled" to open.id), events.seen)
        assertEquals(WaitStatus.CANCELLED, repository.waits.getValue(open.id).status)
        assertEquals(clock.now, repository.waits.getValue(open.id).resolvedAt)
        assertEquals(WaitStatus.RESOLVED, repository.waits.getValue(answered.id).status)
        assertEquals(WaitStatus.OPEN, repository.waits.getValue(elsewhere.id).status)
        assertEquals(WaitStatus.OPEN, repository.waits.getValue(alone.id).status)
        cancel.ended(ORG_A, listOf("session-1"))
        assertEquals(1, events.seen.size, "Once")
    }
}
