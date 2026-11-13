// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import run.ostrom.server.events.domain.EventRules
import run.ostrom.server.events.infrastructure.EventsOrgDeletionListener
import run.ostrom.server.events.infrastructure.EventsPurgeWorker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The purge of the events past their seven days, and the erasure of a deleted organization's journal. */
class EventsPurgeWorkerTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun aPassDeletesABatchOfTheEventsOlderThanSevenDays() = runTest {
        val events = InMemoryEvents()
        EventsPurgeWorker(events, clock, RecordingLogger()).pass()
        assertEquals(listOf(now - EventRules.RETENTION to 1_000), events.purges)
    }

    @Test
    fun aFailedPassIsLoggedByItsTypeOnlyAndACancellationIsNeverSwallowed() = runTest {
        val logger = RecordingLogger()
        val events = InMemoryEvents().apply { failure = IllegalStateException("secret") }
        EventsPurgeWorker(events, clock, logger).pass()
        val failure = "events purge failed" to mapOf<String, Any?>("error" to "IllegalStateException")
        assertEquals(listOf(failure), logger.lines)
        events.failure = CancellationException("stop")
        assertFailsWith<CancellationException> { EventsPurgeWorker(events, clock, logger).pass() }
    }

    @Test
    fun aPassRunsEveryMinuteUntilCancelled() = runTest {
        val events = InMemoryEvents()
        val job = launch { EventsPurgeWorker(events, clock, RecordingLogger()).run() }
        advanceTimeBy(2.minutes + 1.seconds)
        job.cancel()
        assertEquals(3, events.purges.size)
    }

    @Test
    fun aDeletedOrganizationKeepsItsJournalUntilItsDeletionIsFinal() = runTest {
        val events = InMemoryEvents()
        val org = Uuid.random()
        val listener = EventsOrgDeletionListener(events)
        listener.deleted(org)
        assertEquals(emptyList(), events.erased)
        listener.purge(org)
        assertEquals(listOf(org), events.erased)
    }
}
