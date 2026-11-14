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
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
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
        val rows = (if (scope == null) MemoryScope.entries else listOf(scope)).flatMap { s ->
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
        // Every write takes the organization's memory lock, so each limit is exact: the organization's count is
        // shared by its members, and a member's own may be written by several of their agents at once. An expired
        // entry, which nobody reads or forgets any more, holds no place.
        fetchAll(Statement.create("SELECT pg_advisory_xact_lock(:key)").bind("key", lockKey(caller.orgId)))
            .getOrThrow()
        val limit = if (personal) MemoryRules.PERSONAL_LIMIT else MemoryRules.TEAM_LIMIT
        val now = clock.now()
        val held = fetchAll(
            Statement.create(
                "SELECT COUNT(*) AS n FROM memory_entries WHERE org_id = :org_id AND scope = :scope AND " +
                    "owner_id = :owner AND status IN ('active', 'proposed') " +
                    "AND (expires_at IS NULL OR expires_at > :now)",
            ).bind("org_id", caller.orgId).bind("scope", entry.scope.wire).bind("owner", owner).bind("now", now),
        ).getOrThrow().first().get("n").asInt()
        if (held >= limit) throw MemoryLimitException(limit)
        val id = Uuid.random()
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
        val before = (readable(caller, id, now, lock = true) ?: throw MemoryNotFoundException()).toEntry(emptyList())
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
            val entry = (readable(caller, id, clock.now(), lock = true) ?: throw MemoryNotFoundException())
                .toEntry(emptyList())
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
}
