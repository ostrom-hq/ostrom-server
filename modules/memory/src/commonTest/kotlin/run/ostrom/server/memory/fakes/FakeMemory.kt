// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.fakes

import run.ostrom.server.memory.domain.MemoryAudit
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCursor
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryIndexEntry
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryPage
import run.ostrom.server.memory.domain.MemoryRepository
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.MemoryUpdate
import run.ostrom.server.memory.domain.NewMemory
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The memory in a map, with the repository's visibility; it records what the service asked. */
class FakeRepository : MemoryRepository {
    val entries = mutableMapOf<Uuid, MemoryEntry>()
    var lastLimit: Int? = null
    var lastTags: List<String>? = null
    var lastTerms: List<String>? = null
    var lastSession: String? = null

    private fun visible(caller: MemoryCaller, entry: MemoryEntry) =
        entry.scope == MemoryScope.TEAM || entry.ownerId == caller.memberId

    override suspend fun index(
        caller: MemoryCaller,
        scope: MemoryScope?,
        tags: List<String>,
        after: MemoryCursor?,
        limit: Int,
    ): MemoryPage {
        lastLimit = limit
        lastTags = tags
        return MemoryPage(entries.values.filter { visible(caller, it) }.map { it.index() }, null)
    }

    override suspend fun search(
        caller: MemoryCaller,
        terms: List<String>,
        tags: List<String>,
        limit: Int,
    ): List<MemoryIndexEntry> {
        lastLimit = limit
        lastTerms = terms
        lastTags = tags
        return emptyList()
    }

    override suspend fun read(caller: MemoryCaller, id: Uuid): MemoryEntry =
        entries[id]?.takeIf { visible(caller, it) } ?: throw MemoryNotFoundException()

    override suspend fun create(
        caller: MemoryCaller,
        entry: NewMemory,
        origin: MemoryOrigin,
        sessionId: String?,
        status: MemoryStatus,
    ): MemoryEntry {
        lastSession = sessionId
        val stored = MemoryEntry(
            Uuid.random(), entry.scope, caller.memberId.takeIf { entry.scope == MemoryScope.PERSONAL }, entry.title,
            entry.summary, entry.content, entry.tags, entry.refs, status, origin, caller.memberId, sessionId, null,
            entry.expiresAt, 1, AT, AT, null, 0,
        )
        entries[stored.id] = stored
        return stored
    }

    override suspend fun update(
        caller: MemoryCaller,
        id: Uuid,
        update: MemoryUpdate,
        check: (MemoryEntry) -> Unit,
    ): MemoryEntry {
        val entry = read(caller, id)
        check(entry)
        val updated = entry.copy(title = update.title, tags = update.tags, version = entry.version + 1)
        entries[id] = updated
        return updated
    }

    override suspend fun delete(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit) {
        check(read(caller, id))
        entries.remove(id)
    }

    override suspend fun purge(orgId: Uuid) = entries.clear()

    private fun MemoryEntry.index() = MemoryIndexEntry(id, scope, title, summary, tags, updatedAt)

    companion object {
        val AT: Instant = Instant.parse("2026-11-14T12:00:00Z")
    }
}

/** The audit events written, as `event id facts`. */
class RecordingAudit : MemoryAudit {
    val events = mutableListOf<String>()

    override suspend fun record(caller: MemoryCaller, event: String, id: Uuid, vararg facts: Pair<String, String>) {
        events += "$event ${facts.joinToString(",") { "${it.first}=${it.second}" }}".trim()
    }
}
