// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.presentation.toDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The contract's form of an entry. */
class MemoryMappersTest {
    @Test
    fun anEntryNamesTheOneItReplaces() {
        val at = Instant.parse("2026-11-14T12:00:00Z")
        val replaced = Uuid.random()
        val entry = MemoryEntry(
            Uuid.random(), MemoryScope.TEAM, null, "Deploy order", "Core first", "Merge core.", emptyList(),
            emptyList(), MemoryStatus.ACTIVE, MemoryOrigin.HUMAN, "ada", null, replaced, null, 2, at, at, null, 0,
        )
        assertEquals(replaced.toString(), entry.toDto().supersedesId)
    }
}
