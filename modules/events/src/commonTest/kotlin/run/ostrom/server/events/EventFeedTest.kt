// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import kotlinx.coroutines.test.runTest
import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.EventRules
import run.ostrom.server.events.domain.InvalidCursorException
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.OpenEventFeedUseCaseImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** Opening and following the feed (TeamCode's `TeamEventService` and `TeamEventFeed`). */
class EventFeedTest {
    private val org = Uuid.random()
    private val mia = Uuid.random()
    private val tom = Uuid.random()

    @Test
    fun aCursorThatIsNegativeOrAheadOfTheOrganizationIsRefused() = runTest {
        val events = InMemoryEvents(head = 3, oldest = 1)
        val open = OpenEventFeedUseCaseImpl(events) {}

        assertFailsWith<InvalidCursorException> { open(org, EventAudience(mia, false), -1, null) { true } }
        assertFailsWith<InvalidCursorException> { open(org, EventAudience(mia, false), 4, null) { true } }
        assertNull(open(org, EventAudience(mia, false), 3, null) { true }.gapOldest, "The head itself is a cursor")
    }

    @Test
    fun aGapIsToldWhenTheEventsAfterTheCursorWerePurged() = runTest {
        val purged = InMemoryEvents(head = 10, oldest = 6)
        val open = OpenEventFeedUseCaseImpl(purged) {}
        val audience = EventAudience(mia, false)

        assertNull(open(org, audience, null, null) { true }.gapOldest, "A first connection starts now")
        assertNull(open(org, audience, 0, null) { true }.gapOldest, "0 starts at the oldest kept")
        assertNull(open(org, audience, 5, null) { true }.gapOldest, "The next event is the oldest kept")
        assertEquals(6L, open(org, audience, 4, null) { true }.gapOldest, "Event 5 was purged")
        val empty = OpenEventFeedUseCaseImpl(InMemoryEvents(head = 10, oldest = null)) {}
        assertEquals(11L, empty(org, audience, 4, null) { true }.gapOldest, "Everything was purged")
        assertNull(empty(org, audience, 10, null) { true }.gapOldest, "Nothing was missed")
    }

    @Test
    fun theFeedSendsWhatTheReaderSeesInOrderAndStartsAtTheHeadWithoutACursor() = runTest {
        val events = InMemoryEvents()
        events.add(mia)
        val sent = mutableListOf<JournalEvent>()
        var polls = 0
        val open = OpenEventFeedUseCaseImpl(events) {
            polls++
            if (polls == 1) {
                events.add(tom)
                events.add(mia)
                events.add(null)
            }
        }
        val feed = open(org, EventAudience(mia, false), null, null) { polls < 20 }

        feed.follow(emit = { sent += it }, idle = {})
        assertEquals(listOf(3L), sent.map { it.seq }, "Only mia's, after the head of the opening")
        assertEquals(1L, events.reads.first(), "The first read starts after the head")
        assertEquals(4L, events.reads.last(), "The cursor moved past the events mia does not see")
    }

    @Test
    fun aFullPageIsFollowedAtOnceAndTheTypesAndTheWholeOrganizationAreKept() = runTest {
        val events = InMemoryEvents()
        repeat(EventRules.PAGE + 5) {
            events.add(if (it % 2 == 0) mia else null, if (it == 3) "cloud.wait.created" else "cloud.todo")
        }
        val sent = mutableListOf<List<JournalEvent>>()
        var sleeps = 0
        val open = OpenEventFeedUseCaseImpl(events) { sleeps++ }
        val feed = open(org, EventAudience(tom, true), 0, setOf("cloud.todo")) {
            false
        }

        feed.follow(emit = { sent += it }, idle = {})
        assertEquals(listOf(EventRules.PAGE, 4), sent.map { it.size }, "Two pages, the second without a pause")
        assertEquals(EventRules.PAGE + 4, sent.flatten().map { it.seq }.distinct().size)
        assertEquals((EventRules.RECHECK / EventRules.POLL).toInt(), sleeps, "Until the first check refused it")
    }

    @Test
    fun silenceSendsAKeepAliveAndTheCredentialIsCheckedEveryFifteenSeconds() = runTest {
        val events = InMemoryEvents()
        var slept = Duration.ZERO
        var idles = 0
        var checks = 0
        val feed = OpenEventFeedUseCaseImpl(events) { slept += it }(org, EventAudience(mia, false), null, null) {
            checks++
            checks < 3
        }

        feed.follow(emit = {}, idle = { idles++ })
        assertEquals(3, checks)
        assertEquals(EventRules.RECHECK * 3, slept, "Checked at 15, 30 and 45 seconds")
        assertEquals(2, idles, "A keep-alive after each 15 seconds of silence but the last")
    }
}
