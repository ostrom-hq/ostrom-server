// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCursor
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryIndexEntry
import run.ostrom.server.memory.domain.MemoryLimitException
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryPage
import run.ostrom.server.memory.domain.MemoryRepository
import run.ostrom.server.memory.domain.MemoryRules
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.MemoryUpdate
import run.ostrom.server.memory.domain.NewMemory
import io.github.smyrgeorge.sqlx4k.Driver
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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MemoryRepository] on PostgreSQL ([MemoryMigrations]), TeamCode's `SqlMemoryStore`. Visibility is in every query: an
 * organization's entry for each of its members, a personal entry for its owner only (`owner_id` names them), so no
 * route, administrator or key of another member reaches it. A list is at most three reads in one transaction (one per
 * scope on `memory_entries_visible`, then the tags of the rows returned), never one per row, and never reads `content`.
 *
 * @param driver The database.
 * @param clock The time an expiry is compared with and an entry is stamped with.
 */
internal class MemoryRepositoryImpl(private val driver: Driver, private val clock: Clock) : MemoryRepository {
    override suspend fun index(
        caller: MemoryCaller,
        scope: MemoryScope?,
        tags: List<String>,
        after: MemoryCursor?,
        limit: Int,
    ): MemoryPage = driver.inOrg(caller.orgId) {
        val now = clock.now()
        val rows = (scope?.let(::listOf) ?: MemoryScope.entries).flatMap { s ->
            val cursor = if (after == null) "" else AFTER_CURSOR
            val statement = Statement.create(
                "SELECT $INDEX_COLUMNS FROM memory_entries WHERE ${visible()}${tagged(tags)}$cursor " +
                    "ORDER BY updated_at DESC, id DESC LIMIT :limit",
            ).bindVisible(caller, s, now).bindTags(tags).bind("limit", limit + 1)
            if (after != null) statement.bind("after_at", after.updatedAt).bind("after_id", after.id)
            fetchAll(statement).getOrThrow().toList()
        }.sortedWith(NEWEST_FIRST)
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { MemoryCursor(it.updatedAt(), it.id()) } else null
        MemoryPage(indexEntries(page), next)
    }

    override suspend fun search(
        caller: MemoryCaller,
        terms: List<String>,
        tags: List<String>,
        limit: Int,
    ): List<MemoryIndexEntry> = driver.inOrg(caller.orgId) {
        val now = clock.now()
        // Every term in the title, the summary or a tag of the entry (never the content).
        val matching = terms.indices.joinToString("") { i ->
            " AND (title ILIKE :term$i ESCAPE '\\' OR summary ILIKE :term$i ESCAPE '\\' OR id IN (SELECT memory_id " +
                "FROM memory_tags WHERE org_id = :org_id AND scope = :scope AND owner_id = :owner " +
                "AND tag LIKE :term$i ESCAPE '\\'))"
        }
        val rows = MemoryScope.entries.flatMap { s ->
            val statement = Statement.create(
                "SELECT $INDEX_COLUMNS FROM memory_entries WHERE ${visible()}${tagged(tags)}$matching " +
                    "ORDER BY updated_at DESC, id DESC LIMIT :limit",
            ).bindVisible(caller, s, now).bindTags(tags).bind("limit", limit)
            terms.forEachIndexed { i, term -> statement.bind("term$i", "%${escape(term)}%") }
            fetchAll(statement).getOrThrow().toList()
        }.sortedWith(NEWEST_FIRST).take(limit)
        indexEntries(rows)
    }

    override suspend fun read(caller: MemoryCaller, id: Uuid): MemoryEntry = driver.inOrg(caller.orgId) {
        val now = clock.now()
        val row = readable(caller, id, now, lock = false) ?: throw MemoryNotFoundException()
        // An increment in place, so two readers of an organization's entry neither wait nor lose a count.
        execute(
            Statement.create(
                "UPDATE memory_entries SET last_read_at = :now, read_count = read_count + 1 " +
                    "WHERE org_id = :org_id AND id = :id",
            ).bind("now", now).bind("org_id", caller.orgId).bind("id", id),
        ).getOrThrow()
        val entry = row.toEntry(tagsOf(listOf(id))[id].orEmpty())
        entry.copy(lastReadAt = now, readCount = entry.readCount + 1)
    }

    override suspend fun create(
        caller: MemoryCaller,
        entry: NewMemory,
        origin: MemoryOrigin,
        sessionId: String?,
        status: MemoryStatus,
    ): MemoryEntry = driver.inOrg(caller.orgId) {
        val personal = entry.scope == MemoryScope.PERSONAL
        val owner = ownerOf(caller, entry.scope)
        // The organization's count is shared by every member: its writes take the organization's memory lock, so the
        // limit is exact; a member's own entries are only written by them.
        if (!personal) {
            fetchAll(Statement.create("SELECT pg_advisory_xact_lock(:key)").bind("key", lockKey(caller.orgId)))
                .getOrThrow()
        }
        val limit = if (personal) MemoryRules.PERSONAL_LIMIT else MemoryRules.TEAM_LIMIT
        val held = fetchAll(
            Statement.create(
                "SELECT COUNT(*) AS n FROM memory_entries WHERE org_id = :org_id AND scope = :scope AND " +
                    "owner_id = :owner AND status IN ('active', 'proposed')",
            ).bind("org_id", caller.orgId).bind("scope", entry.scope.wire).bind("owner", owner),
        ).getOrThrow().first().get("n").asInt()
        if (held >= limit) throw MemoryLimitException(limit)
        val id = Uuid.random()
        val now = clock.now()
        execute(
            Statement.create(
                """
                INSERT INTO memory_entries (id, org_id, scope, owner_id, title, summary, content, refs, status, origin,
                author_id, session_id, expires_at, version, created_at, updated_at, read_count)
                VALUES (:id, :org_id, :scope, :owner, :title, :summary, :content, :refs, :status, :origin, :author,
                :session_id, :expires_at, 1, :now, :now, 0)
                """,
            ).bind("id", id).bind("org_id", caller.orgId).bind("scope", entry.scope.wire).bind("owner", owner)
                .bind("title", entry.title).bind("summary", entry.summary).bind("content", entry.content)
                .bind("refs", REFS.encode(entry.refs)).bind("status", status.wire).bind("origin", origin.wire)
                .bind("author", caller.memberId).bind("session_id", sessionId).bind("expires_at", entry.expiresAt)
                .bind("now", now),
        ).getOrThrow()
        insertTags(caller, id, entry.scope, owner, entry.tags)
        MemoryEntry(
            id, entry.scope, owner.ifEmpty { null }, entry.title, entry.summary, entry.content, entry.tags, entry.refs,
            status, origin, caller.memberId, sessionId, null, entry.expiresAt, 1, now, now, null, 0,
        )
    }

    override suspend fun update(
        caller: MemoryCaller,
        id: Uuid,
        update: MemoryUpdate,
        check: (MemoryEntry) -> Unit,
    ): MemoryEntry = driver.inOrg(caller.orgId) {
        val now = clock.now()
        val before = readable(caller, id, now, lock = true)?.toEntry(emptyList()) ?: throw MemoryNotFoundException()
        check(before)
        execute(
            Statement.create(
                """
                UPDATE memory_entries SET title = :title, summary = :summary, content = :content, refs = :refs,
                expires_at = :expires_at, version = :version, updated_at = :now WHERE org_id = :org_id AND id = :id
                """,
            ).bind("title", update.title).bind("summary", update.summary).bind("content", update.content)
                .bind("refs", REFS.encode(update.refs)).bind("expires_at", update.expiresAt)
                .bind("version", before.version + 1).bind("now", now).bind("org_id", caller.orgId).bind("id", id),
        ).getOrThrow()
        execute(
            Statement.create("DELETE FROM memory_tags WHERE org_id = :org_id AND memory_id = :id")
                .bind("org_id", caller.orgId).bind("id", id),
        ).getOrThrow()
        insertTags(caller, id, before.scope, before.ownerId.orEmpty(), update.tags)
        before.copy(
            title = update.title, summary = update.summary, content = update.content, tags = update.tags,
            refs = update.refs, expiresAt = update.expiresAt, version = before.version + 1, updatedAt = now,
        )
    }

    override suspend fun delete(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit) {
        driver.inOrg(caller.orgId) {
            val entry = readable(caller, id, clock.now(), lock = true)?.toEntry(emptyList())
                ?: throw MemoryNotFoundException()
            check(entry)
            // The tags go with the entry (ON DELETE CASCADE).
            execute(
                Statement.create("DELETE FROM memory_entries WHERE org_id = :org_id AND id = :id")
                    .bind("org_id", caller.orgId).bind("id", id),
            ).getOrThrow()
        }
    }

    override suspend fun purge(orgId: Uuid) {
        driver.inOrg(orgId) {
            execute(Statement.create("DELETE FROM memory_entries WHERE org_id = :org_id").bind("org_id", orgId))
                .getOrThrow()
        }
    }

    /** An active, unexpired entry the caller may read: the organization's, or their own personal one. */
    private suspend fun QueryExecutor.readable(
        caller: MemoryCaller,
        id: Uuid,
        now: Instant,
        lock: Boolean,
    ): ResultSet.Row? =
        fetchAll(
            Statement.create(
                """
                SELECT $ENTRY_COLUMNS FROM memory_entries WHERE org_id = :org_id AND id = :id AND status = 'active'
                AND (expires_at IS NULL OR expires_at > :now)
                AND ((scope = 'team' AND owner_id = '') OR (scope = 'personal' AND owner_id = :me))
                """.trimIndent() + if (lock) " FOR UPDATE" else "",
            ).bind("org_id", caller.orgId).bind("id", id).bind("now", now).bind("me", caller.memberId),
        ).getOrThrow().firstOrNull()

    private suspend fun QueryExecutor.insertTags(
        caller: MemoryCaller,
        id: Uuid,
        scope: MemoryScope,
        owner: String,
        tags: List<String>,
    ) {
        for (tag in tags) {
            execute(
                Statement.create(
                    "INSERT INTO memory_tags (memory_id, tag, org_id, scope, owner_id) " +
                        "VALUES (:id, :tag, :org_id, :scope, :owner)",
                ).bind("id", id).bind("tag", tag).bind("org_id", caller.orgId).bind("scope", scope.wire)
                    .bind("owner", owner),
            ).getOrThrow()
        }
    }

    /** The tags of [ids], sorted, in one read. */
    private suspend fun QueryExecutor.tagsOf(ids: List<Uuid>): Map<Uuid, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        return fetchAll(
            Statement.create(
                "SELECT memory_id, tag FROM memory_tags " +
                    "WHERE memory_id = ANY(CAST(string_to_array(:ids, ',') AS UUID[])) ORDER BY tag",
            ).bind("ids", ids.joinToString(",")),
        ).getOrThrow().groupBy({ it.get("memory_id").asUuid() }, { it.get("tag").asString() })
    }

    private suspend fun QueryExecutor.indexEntries(rows: List<ResultSet.Row>): List<MemoryIndexEntry> {
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

    private fun ResultSet.Row.toEntry(tags: List<String>) = MemoryEntry(
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

    private companion object {
        const val AFTER_CURSOR = " AND (updated_at < :after_at OR (updated_at = :after_at AND id < :after_id))"
        const val INDEX_COLUMNS = "id, scope, title, summary, updated_at"
        const val ENTRY_COLUMNS = "id, scope, owner_id, title, summary, content, refs, status, origin, author_id, " +
            "session_id, supersedes_id, expires_at, version, created_at, updated_at, last_read_at, read_count"

        /** Newest first, then the larger id: the order of `memory_entries_visible` read backwards. */
        val NEWEST_FIRST: Comparator<ResultSet.Row> =
            compareByDescending<ResultSet.Row> { it.updatedAt() }.thenByDescending { it.id().toString() }

        val REFS = RefsJson()

        /** The organization's memory lock, apart from every other advisory lock by its first word. */
        fun lockKey(orgId: Uuid): Long = orgId.toLongs { most, _ -> most } xor MEMORY_LOCK

        /** `memory` in ASCII. */
        const val MEMORY_LOCK = 0x6D656D6F7279L

        fun ResultSet.Row.id(): Uuid = get("id").asUuid()

        fun ResultSet.Row.updatedAt(): Instant = get("updated_at").asInstant()

        fun scopeOf(wire: String): MemoryScope = MemoryScope.entries.single { it.wire == wire }

        fun ownerOf(caller: MemoryCaller, scope: MemoryScope): String =
            if (scope == MemoryScope.PERSONAL) caller.memberId else ""

        /** The visibility of one scope: active, unexpired, the organization's or the caller's own. */
        fun visible(): String = "org_id = :org_id AND scope = :scope AND owner_id = :owner AND status = 'active' " +
            "AND (expires_at IS NULL OR expires_at > :now)"

        fun Statement.bindVisible(caller: MemoryCaller, scope: MemoryScope, now: Instant): Statement =
            bind("org_id", caller.orgId).bind("scope", scope.wire).bind("owner", ownerOf(caller, scope))
                .bind("now", now)

        /** Entries having any of [tags], read on `memory_tags_lookup` within the same scope and owner. */
        fun tagged(tags: List<String>): String = if (tags.isEmpty()) "" else
            " AND id IN (SELECT memory_id FROM memory_tags WHERE org_id = :org_id AND scope = :scope AND " +
                "owner_id = :owner AND tag = ANY(string_to_array(:tags, ',')))"

        fun Statement.bindTags(tags: List<String>): Statement =
            if (tags.isEmpty()) this else bind("tags", tags.joinToString(","))

        /** A `LIKE` pattern's own characters, matched literally. */
        fun escape(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

/** The references of an entry, a JSON array of strings in one column. */
internal class RefsJson {
    private val serializer = ListSerializer(String.serializer())

    fun encode(refs: List<String>): String = Json.encodeToString(serializer, refs)

    fun decode(text: String): List<String> = Json.decodeFromString(serializer, text)
}
