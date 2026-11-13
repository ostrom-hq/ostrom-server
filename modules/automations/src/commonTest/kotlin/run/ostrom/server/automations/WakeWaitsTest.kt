// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AnswerWaitUseCaseImpl
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCaseImpl
import run.ostrom.server.automations.domain.InvalidWaitException
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitForbiddenException
import run.ostrom.server.automations.domain.WaitMessages
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitWakeResult
import run.ostrom.server.automations.domain.WakeWaitsUseCaseImpl
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.CountingSecrets
import run.ostrom.server.automations.fakes.InMemoryWaits
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.RecordingEvents
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.TestMembers
import run.ostrom.server.automations.fakes.alice
import run.ostrom.server.automations.fakes.bob
import run.ostrom.server.automations.fakes.testWaitSessions
import run.ostrom.server.automations.presentation.wakeRequestOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The wake of an agent's session by the answers to its waits (TeamCode `LocalWake`, `docs/waits.md`). */
class WakeWaitsTest {
    private val repository = InMemoryWaits()
    private val clock = TestClock()
    private val sleeps = mutableListOf<Long>()
    private var onSleep: suspend () -> Unit = {}
    private val wake = WakeWaitsUseCaseImpl(repository, testWaitSessions, clock) {
        sleeps += it
        clock.now += it.milliseconds
        onSleep()
    }
    private val ask =
        AskWaitUseCaseImpl(repository, TestMembers, RecordingEvents(), clock, testWaitSessions, CountingSecrets())
    private val answers = AnswerWaitUseCaseImpl(repository, RecordingEvents(), clock)

    /** Bob's agent, in its launch's segments `s-1` and `s-1~r2`. */
    private val agent =
        bob.copy(interactive = false, agent = true, session = "s-1~r2", segments = listOf("s-1", "s-1~r2"))

    private val review = WaitSpec(
        "Review the plan",
        fields = listOf(
            WaitField("feedback", WaitFieldType.TEXT, "Feedback"),
            WaitField("tags", WaitFieldType.CHOICE, "Tags", options = listOf("a", "b"), multiple = true),
            WaitField("note", WaitFieldType.INFO, "Read me"),
        ),
        actions = listOf(WaitAction("approve", "Approve"), WaitAction("changes", "Request changes")),
    )

    private suspend fun asked(session: String = "s-1", question: WaitSpec = review): Wait =
        ask.ask(agent.copy(session = session), AskWait(question, session, assignee = ALICE.toString()), null).wait

    @Test
    fun byDefaultAWakeWaitsOnTheCoroutineClockAndAWaitWithoutAnswerSaysOnlyItsId() = runTest {
        val open = asked()
        val held = WakeWaitsUseCaseImpl(repository, testWaitSessions, Clock.System)
        assertEquals(WaitWakeResult(true, emptyList()), held.wake(agent, 1))
        // A wait handed over without an answer (cancelled meanwhile) names only itself.
        repository.wakePending += open.id
        val bare = held.wake(agent, 0).messages.single()
        assertEquals("${WaitMessages.PREFIX} Review the plan\nwait: ${open.id}", bare)
    }

    @Test
    fun onlyAnAgentInItsSessionIsWokenAndWithinBounds() = runTest {
        assertFailsWith<WaitForbiddenException> { wake.wake(bob, 0) }
        assertFailsWith<WaitForbiddenException> { wake.wake(bob.copy(agent = true), 0) }
        assertFailsWith<InvalidWaitException> { wake.wake(agent, -1) }
        assertFailsWith<InvalidWaitException> { wake.wake(agent, 30_001) }
    }

    @Test
    fun withNoOpenWaitTheStopPassesAtOnce() = runTest {
        assertEquals(WaitWakeResult(false, emptyList()), wake.wake(agent, null))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun anOpenWaitHoldsTheCallUntilItsDeadline() = runTest {
        asked()
        assertEquals(WaitWakeResult(true, emptyList()), wake.wake(agent, 0))
        assertEquals(WaitWakeResult(true, emptyList()), wake.wake(agent, null))
        assertEquals(25, sleeps.size, "25 s by default, a look a second")
        assertTrue(sleeps.all { it == WakeWaitsUseCaseImpl.POLL_MILLIS })
    }

    @Test
    fun anAnswerWakesTheHeldCallOnceAndInOrder() = runTest {
        val first = asked()
        val second = asked(session = "s-1~r2")
        val elsewhere = asked(session = "s-2")
        onSleep = {
            clock.now += 1.minutes
            answers.answer(alice, second.id, "changes", mapOf("feedback" to JsonPrimitive("Split it\ninto two")))
            clock.now += 1.minutes
            answers.answer(alice, first.id, "approve", emptyMap())
            answers.answer(alice, elsewhere.id, "approve", emptyMap())
            onSleep = {}
        }
        val woken = wake.wake(agent, 30_000)
        assertEquals(false, woken.open)
        assertEquals(
            listOf(
                """
                [Ostrom wait answered] Review the plan
                wait: ${second.id}
                action: Request changes (changes)
                answered by: alice
                Feedback: Split it
                  into two
                """.trimIndent(),
                "[Ostrom wait answered] Review the plan\nwait: ${first.id}\naction: Approve (approve)\n" +
                    "answered by: alice",
            ),
            woken.messages,
        )
        assertEquals(WaitWakeResult(false, emptyList()), wake.wake(agent, 0), "Handed over once")
        assertEquals(listOf(elsewhere.id), repository.wakePending.toList(), "Another session's stays")
    }

    @Test
    fun aCancelledOrAnsweredOutsideASessionWaitWakesNothing() = runTest {
        val open = asked()
        answers.cancel(agent, open.id)
        val alone = ask.ask(bob, AskWait(review), null).wait
        answers.answer(bob, alone.id, "approve", emptyMap())
        assertTrue(repository.wakePending.isEmpty())
        assertEquals(WaitWakeResult(false, emptyList()), wake.wake(agent, 0))
    }

    @Test
    fun theMessageNamesEachFilledFieldAndIsCut() {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val wait = Wait(
            id, ORG_A, "human_request", WaitStatus.RESOLVED, "s-1", null, ALICE.toString(), ALICE.toString(),
            review.copy(fields = review.fields + WaitField("extra", WaitFieldType.TEXT, "Extra")), null,
            Instant.parse("2026-10-30T10:00:00Z"), null, null,
        )
        assertEquals("[Ostrom wait answered] Review the plan\nwait: $id", WaitMessages.answered(wait, null))
        val values = mapOf<String, JsonElement>(
            "feedback" to JsonPrimitive(" "),
            "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonNull, JsonPrimitive("b"))),
            "note" to JsonNull,
            "extra" to JsonObject(mapOf("k" to JsonPrimitive(1))),
        )
        val answered = wait.copy(answer = WaitAnswer("gone", values, ALICE.toString(), wait.createdAt))
        assertEquals(
            "[Ostrom wait answered] Review the plan\nwait: $id\naction: gone (gone)\nanswered by: $ALICE\n" +
                "Tags: a, b\nExtra: {\"k\":1}",
            WaitMessages.answered(answered, null),
        )
        val empty = answered.copy(answer = answered.answer?.copy(values = mapOf("tags" to JsonArray(emptyList()))))
        assertEquals(4, WaitMessages.answered(empty, "alice").lines().size, "An empty list fills nothing")
        val huge = mapOf<String, JsonElement>("feedback" to JsonPrimitive("x".repeat(40_000)))
        val long = answered.copy(answer = answered.answer?.copy(values = huge))
        val cut = WaitMessages.answered(long, "alice")
        assertEquals(WaitMessages.MAX_LENGTH, cut.length)
        assertTrue(cut.endsWith("\n…"))
        // Emoji are two UTF-16 units: whichever parity the cut falls on, it never keeps half of one.
        val emoji = listOf("", "x").map { lead ->
            val values = mapOf<String, JsonElement>("feedback" to JsonPrimitive(lead + "\uD83D\uDE00".repeat(20_000)))
            WaitMessages.answered(answered.copy(answer = answered.answer?.copy(values = values)), "alice")
        }
        assertTrue(emoji.none { it.dropLast(2).last().isHighSurrogate() })
        assertEquals(setOf(WaitMessages.MAX_LENGTH, WaitMessages.MAX_LENGTH - 1), emoji.map { it.length }.toSet())
    }

    @Test
    fun anEmptyBodyTakesTheDefaultsAndAMalformedOneIsRefused() {
        assertEquals(null, wakeRequestOf(" ").waitMillis)
        assertEquals(0L, wakeRequestOf("""{"waitMillis":0}""").waitMillis)
        assertFailsWith<InvalidWaitException> { wakeRequestOf("{") }
    }
}
