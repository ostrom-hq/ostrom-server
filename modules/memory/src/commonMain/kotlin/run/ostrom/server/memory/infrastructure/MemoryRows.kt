// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.memory.domain.CurationSettings
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryIndexEntry
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryReview
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.ReviewKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

// The rows of the memory's tables, read back as the domain's entries (MemoryRepositoryImpl).

/** The columns of an index entry. */
internal val INDEX_COLUMNS: List<Expression<*>> = MemoryEntries.let {
    listOf(it.id, it.scope, it.title, it.summary, it.updatedAt)
}

/** The columns of an entry, its content included. */
internal val ENTRY_COLUMNS: List<Expression<*>> = MemoryEntries.let {
    listOf(
        it.id, it.scope, it.ownerId, it.title, it.summary, it.content, it.refs, it.status, it.origin, it.authorId,
        it.sessionId, it.supersedesId, it.expiresAt, it.version, it.createdAt, it.updatedAt, it.lastReadAt,
        it.readCount,
    )
}

/** The tags of [ids], sorted, in one read. */
internal fun JdbcTransaction.tagsOf(ids: List<Uuid>): Map<Uuid, List<String>> {
    if (ids.isEmpty()) return emptyMap()
    val t = MemoryTags
    return t.select(t.memoryId, t.tag).where { t.memoryId inList ids }.orderBy(t.tag)
        .groupBy({ it[t.memoryId] }, { it[t.tag] })
}

internal fun JdbcTransaction.indexEntries(rows: List<ResultRow>): List<MemoryIndexEntry> {
    val e = MemoryEntries
    val tags = tagsOf(rows.map { it[e.id] })
    return rows.map {
        MemoryIndexEntry(
            it[e.id],
            scopeOf(it[e.scope]),
            it[e.title],
            it[e.summary],
            tags[it[e.id]].orEmpty(),
            it[e.updatedAt],
        )
    }
}

internal fun ResultRow.toEntry(tags: List<String>): MemoryEntry {
    val e = MemoryEntries
    return MemoryEntry(
        id = this[e.id],
        scope = scopeOf(this[e.scope]),
        ownerId = this[e.ownerId].ifEmpty { null },
        title = this[e.title],
        summary = this[e.summary],
        content = this[e.content],
        tags = tags,
        refs = REFS.decode(this[e.refs]),
        status = MemoryStatus.entries.single { it.wire == this[e.status] },
        origin = originOf(this[e.origin]),
        authorId = this[e.authorId],
        sessionId = this[e.sessionId],
        supersedesId = this[e.supersedesId],
        expiresAt = this[e.expiresAt],
        version = this[e.version],
        createdAt = this[e.createdAt],
        updatedAt = this[e.updatedAt],
        lastReadAt = this[e.lastReadAt],
        readCount = this[e.readCount],
    )
}

/** [NEWEST_FIRST_ORDER] for rows read in several scopes. */
internal val NEWEST_FIRST: Comparator<ResultRow> =
    compareByDescending<ResultRow> { it[MemoryEntries.updatedAt] }.thenByDescending { it[MemoryEntries.id].toString() }

internal val REFS = RefsJson()

internal fun scopeOf(wire: String): MemoryScope = MemoryScope.entries.single { it.wire == wire }

/** The references of an entry, a JSON array of strings in one column. */
internal class RefsJson {
    private val serializer = ListSerializer(String.serializer())

    fun encode(refs: List<String>): String = Json.encodeToString(serializer, refs)

    fun decode(text: String): List<String> = Json.decodeFromString(serializer, text)
}

/** The columns of a setting. */
internal val SETTINGS_COLUMNS: List<Expression<*>> = MemorySettings.let {
    listOf(it.curationEnabled, it.lastRunAt, it.lastRunId, it.lastSuperseded, it.lastQueued)
}

internal fun ResultRow.toSettings(): CurationSettings {
    val s = MemorySettings
    return CurationSettings(
        enabled = this[s.curationEnabled],
        lastRunAt = this[s.lastRunAt],
        lastRunId = this[s.lastRunId],
        lastSuperseded = this[s.lastSuperseded],
        lastQueued = this[s.lastQueued],
    )
}

/** A review joined with its two entries' titles and origins. */
internal fun ResultRow.toReview(older: Alias<MemoryEntries>, newer: Alias<MemoryEntries>): MemoryReview {
    val r = MemoryReviews
    return MemoryReview(
        id = this[r.id],
        scope = scopeOf(this[r.scope]),
        ownerId = this[r.ownerId].ifEmpty { null },
        kind = ReviewKind.entries.single { it.wire == this[r.kind] },
        reason = this[r.reason],
        olderId = this[r.olderId],
        olderTitle = this[older[MemoryEntries.title]],
        olderOrigin = originOf(this[older[MemoryEntries.origin]]),
        newerId = this[r.newerId],
        newerTitle = this[newer[MemoryEntries.title]],
        newerOrigin = originOf(this[newer[MemoryEntries.origin]]),
        createdAt = this[r.createdAt],
    )
}

internal fun originOf(wire: String): MemoryOrigin = MemoryOrigin.entries.single { it.wire == wire }
