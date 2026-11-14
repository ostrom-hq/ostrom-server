// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import kotlinx.serialization.json.JsonObject
import run.ostrom.server.kernel.events.JournalEntry
import run.ostrom.server.kernel.events.JournalPosition
import run.ostrom.server.kernel.events.JournalSlice
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.events.WaitSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The values a reader of the journal is handed, as the events and automations modules build them. */
class OrgEventJournalTest {
    @Test
    fun aSliceCarriesItsEntriesAndHowFarTheReaderMoves() {
        val org = Uuid.random()
        val position = JournalPosition(org, 2, 5)
        val entry = JournalEntry(3, OrgEventType.WAIT_CREATED, "w-3", null, Instant.parse("2026-11-14T00:00:00Z"),
            JsonObject(emptyMap()))
        val slice = JournalSlice(listOf(entry), position.head)
        assertEquals(5, slice.through)
        assertEquals(listOf(3L), slice.entries.map { it.seq })
        assertEquals(org, position.orgId)
        assertEquals(2, position.from)
        val wait = WaitSummary("w-3", "mia", null, "ask_human", "human", true, "Deploy?")
        assertEquals(wait, wait.copy())
    }
}
