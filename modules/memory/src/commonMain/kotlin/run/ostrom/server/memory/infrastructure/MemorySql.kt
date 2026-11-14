// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryScope
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The SQL fragments of the memory's queries: visibility, tags, the cursor and the organization's lock.

internal const val AFTER_CURSOR = " AND (updated_at < :after_at OR (updated_at = :after_at AND id < :after_id))"
internal const val INDEX_COLUMNS = "id, scope, title, summary, updated_at"
internal const val ENTRY_COLUMNS = "id, scope, owner_id, title, summary, content, refs, status, origin, author_id, " +
    "session_id, supersedes_id, expires_at, version, created_at, updated_at, last_read_at, read_count"

/** The organization's memory lock, apart from every other advisory lock by its first word. */
internal fun lockKey(orgId: Uuid): Long = orgId.toLongs { most, _ -> most } xor MEMORY_LOCK

/** `memory` in ASCII. */
internal const val MEMORY_LOCK = 0x6D656D6F7279L

internal fun ownerOf(caller: MemoryCaller, scope: MemoryScope): String =
    if (scope == MemoryScope.PERSONAL) caller.memberId else ""

/** The visibility of one scope: active, unexpired, the organization's or the caller's own. */
internal fun visible(): String = "org_id = :org_id AND scope = :scope AND owner_id = :owner AND status = 'active' " +
    "AND (expires_at IS NULL OR expires_at > :now)"

internal fun Statement.bindVisible(caller: MemoryCaller, scope: MemoryScope, now: Instant): Statement =
    bind("org_id", caller.orgId).bind("scope", scope.wire).bind("owner", ownerOf(caller, scope))
        .bind("now", now)

/** Entries having any of [tags], read on `memory_tags_lookup` within the same scope and owner. */
internal fun tagged(tags: List<String>): String = if (tags.isEmpty()) "" else
    " AND id IN (SELECT memory_id FROM memory_tags WHERE org_id = :org_id AND scope = :scope AND " +
        "owner_id = :owner AND tag = ANY(string_to_array(:tags, ',')))"

internal fun Statement.bindTags(tags: List<String>): Statement =
    if (tags.isEmpty()) this else bind("tags", tags.joinToString(","))

/** A `LIKE` pattern's own characters, matched literally. */
internal fun escape(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** The organization's proposals of [author] that wait for an answer. */
internal suspend fun QueryExecutor.proposalsOf(orgId: Uuid, author: String): Int = fetchAll(
    Statement.create(
        "SELECT COUNT(*) AS n FROM memory_entries WHERE org_id = :org_id AND scope = 'team' AND owner_id = '' " +
            "AND status = 'proposed' AND author_id = :author",
    ).bind("org_id", orgId).bind("author", author),
).getOrThrow().first().get("n").asInt()

/**
 * The older entry becomes `superseded` by the newer one, once: a concurrent change loses on its status and version.
 * The newer entry names it (`supersedes_id`) unless it already names another. Nothing is deleted. One statement.
 *
 * @return The older entry's id when this call superseded it; nothing otherwise.
 */
internal suspend fun QueryExecutor.supersede(
    orgId: Uuid,
    older: Uuid,
    version: Int,
    newer: Uuid,
    now: Instant,
): List<Uuid> = fetchAll(
    Statement.create(
        """
        WITH changed AS (
            UPDATE memory_entries SET status = 'superseded', superseded_by_id = :newer, updated_at = :now,
                version = version + 1
            WHERE org_id = :org_id AND id = :older AND status = 'active' AND version = :version RETURNING id
        ), named AS (
            UPDATE memory_entries SET supersedes_id = :older WHERE org_id = :org_id AND id = :newer
                AND supersedes_id IS NULL AND EXISTS (SELECT 1 FROM changed)
        )
        SELECT id FROM changed
        """.trimIndent(),
    ).bind("newer", newer).bind("now", now).bind("org_id", orgId).bind("older", older).bind("version", version),
).getOrThrow().map { it.get("id").asUuid() }
