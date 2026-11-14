// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryIndexEntry
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The rows of the memory's tables, read back as the domain's entries (MemoryRepositoryImpl).

/** The tags of [ids], sorted, in one read. */
internal suspend fun QueryExecutor.tagsOf(ids: List<Uuid>): Map<Uuid, List<String>> {
    if (ids.isEmpty()) return emptyMap()
    return fetchAll(
        Statement.create(
            "SELECT memory_id, tag FROM memory_tags " +
                "WHERE memory_id = ANY(CAST(string_to_array(:ids, ',') AS UUID[])) ORDER BY tag",
        ).bind("ids", ids.joinToString(",")),
    ).getOrThrow().groupBy({ it.get("memory_id").asUuid() }, { it.get("tag").asString() })
}

internal suspend fun QueryExecutor.indexEntries(rows: List<ResultSet.Row>): List<MemoryIndexEntry> {
    val tags = tagsOf(rows.map { it.id() })
    return rows.map {
        MemoryIndexEntry(
            it.id(),
            scopeOf(it.get("scope").asString()),
            it.get("title").asString(),
            it.get("summary").asString(),
            tags[it.id()].orEmpty(),
            it.updatedAt(),
        )
    }
}

internal fun ResultSet.Row.toEntry(tags: List<String>) = MemoryEntry(
    id = id(),
    scope = scopeOf(get("scope").asString()),
    ownerId = get("owner_id").asString().ifEmpty { null },
    title = get("title").asString(),
    summary = get("summary").asString(),
    content = get("content").asString(),
    tags = tags,
    refs = REFS.decode(get("refs").asString()),
    status = MemoryStatus.entries.single { it.wire == get("status").asString() },
    origin = MemoryOrigin.entries.single { it.wire == get("origin").asString() },
    authorId = get("author_id").asString(),
    sessionId = get("session_id").asStringOrNull(),
    supersedesId = get("supersedes_id").asUuidOrNull(),
    expiresAt = get("expires_at").asInstantOrNull(),
    version = get("version").asInt(),
    createdAt = get("created_at").asInstant(),
    updatedAt = updatedAt(),
    lastReadAt = get("last_read_at").asInstantOrNull(),
    readCount = get("read_count").asInt(),
)

/** Newest first, then the larger id: the order of `memory_entries_visible` read backwards. */
internal val NEWEST_FIRST: Comparator<ResultSet.Row> =
    compareByDescending<ResultSet.Row> { it.updatedAt() }.thenByDescending { it.id().toString() }

internal val REFS = RefsJson()

internal fun ResultSet.Row.id(): Uuid = get("id").asUuid()

internal fun ResultSet.Row.updatedAt(): Instant = get("updated_at").asInstant()

internal fun scopeOf(wire: String): MemoryScope = MemoryScope.entries.single { it.wire == wire }

/** The references of an entry, a JSON array of strings in one column. */
internal class RefsJson {
    private val serializer = ListSerializer(String.serializer())

    fun encode(refs: List<String>): String = Json.encodeToString(serializer, refs)

    fun decode(text: String): List<String> = Json.decodeFromString(serializer, text)
}
