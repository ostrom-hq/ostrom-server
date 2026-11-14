// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.memory.domain.CurationSettings
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCurationStore
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryLimitException
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryReview
import run.ostrom.server.memory.domain.MemoryReviewClosedException
import run.ostrom.server.memory.domain.MemoryReviewNotFoundException
import run.ostrom.server.memory.domain.MemoryReviewStaleException
import run.ostrom.server.memory.domain.MemoryRules
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.ResolvedReview
import run.ostrom.server.memory.domain.RestoredMemory
import run.ostrom.server.memory.domain.SupersededMemory
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MemoryCurationStore] on the memory's tables (TeamCode's `SqlMemoryCurationStore`, its requests' half). Every read
 * is in the caller's organization and names the caller's visibility: a personal review or superseded entry is its
 * owner's alone, administrators included.
 *
 * @param driver The database.
 * @param clock The time an answer or a restore is stamped with.
 */
internal class MemoryCurationStoreImpl(private val driver: Driver, private val clock: Clock) : MemoryCurationStore {
    override suspend fun settings(orgId: Uuid): CurationSettings = driver.inOrg(orgId) {
        fetchAll(
            Statement.create("SELECT $SETTINGS_COLUMNS FROM memory_settings WHERE org_id = :org_id")
                .bind("org_id", orgId),
        ).getOrThrow().firstOrNull()?.toSettings() ?: CurationSettings(enabled = false)
    }

    override suspend fun setEnabled(orgId: Uuid, enabled: Boolean): CurationSettings = driver.inOrg(orgId) {
        fetchAll(
            Statement.create(
                "INSERT INTO memory_settings (org_id, curation_enabled) VALUES (:org_id, :enabled) ON CONFLICT " +
                    "(org_id) DO UPDATE SET curation_enabled = EXCLUDED.curation_enabled RETURNING $SETTINGS_COLUMNS",
            ).bind("org_id", orgId).bind("enabled", enabled),
        ).getOrThrow().single().toSettings()
    }

    override suspend fun reviews(caller: MemoryCaller, limit: Int): List<MemoryReview> = driver.inOrg(caller.orgId) {
        fetchAll(
            Statement.create("$REVIEWS WHERE ${reachable(caller)} AND r.status = 'open' ORDER BY r.created_at, r.id " +
                "LIMIT :limit").bind("org_id", caller.orgId).bind("me", caller.memberId).bind("limit", limit),
        ).getOrThrow().map { it.toReview() }
    }

    override suspend fun resolve(caller: MemoryCaller, id: Uuid, apply: Boolean): ResolvedReview =
        driver.inOrg(caller.orgId) {
            val row = fetchAll(
                Statement.create("$REVIEWS WHERE ${reachable(caller)} AND r.id = :id FOR UPDATE OF r")
                    .bind("org_id", caller.orgId).bind("me", caller.memberId).bind("id", id),
            ).getOrThrow().firstOrNull() ?: throw MemoryReviewNotFoundException()
            if (row.get("status").asString() != OPEN) throw MemoryReviewClosedException()
            val review = row.toReview()
            val now = clock.now()
            if (apply) applyReview(caller, review, now)
            execute(
                Statement.create(
                    "UPDATE memory_reviews SET status = :status, resolved_at = :now " +
                        "WHERE org_id = :org_id AND id = :id",
                ).bind("status", if (apply) APPLIED else DISMISSED).bind("now", now).bind("org_id", caller.orgId)
                    .bind("id", id),
            ).getOrThrow()
            ResolvedReview(review, superseded = apply)
        }

    /** Supersedes the review's older entry by its newer one, both still active. */
    private suspend fun QueryExecutor.applyReview(caller: MemoryCaller, review: MemoryReview, now: Instant) {
        val active = fetchAll(
            Statement.create(
                "SELECT id, version FROM memory_entries WHERE org_id = :org_id AND id IN (:older, :newer) " +
                    "AND status = 'active' ORDER BY id FOR UPDATE",
            ).bind("org_id", caller.orgId).bind("older", review.olderId).bind("newer", review.newerId),
        ).getOrThrow().associate { it.id() to it.get("version").asInt() }
        val version = active[review.olderId]
        if (version == null || review.newerId !in active) throw MemoryReviewStaleException()
        supersede(caller.orgId, review.olderId, version, review.newerId, now)
    }

    override suspend fun superseded(caller: MemoryCaller, limit: Int): List<SupersededMemory> =
        driver.inOrg(caller.orgId) {
            val team = if (caller.admin) "" else " AND author_id = :me"
            fetchAll(
                Statement.create(
                    "SELECT id, scope, owner_id, title, summary, author_id, superseded_by_id, updated_at " +
                        "FROM memory_entries WHERE org_id = :org_id AND status = 'superseded' AND " +
                        "((scope = 'personal' AND owner_id = :me) OR (scope = 'team' AND owner_id = ''$team)) " +
                        "ORDER BY updated_at DESC, id DESC LIMIT :limit",
                ).bind("org_id", caller.orgId).bind("me", caller.memberId).bind("limit", limit),
            ).getOrThrow().map {
                SupersededMemory(
                    it.id(),
                    scopeOf(it.get("scope").asString()),
                    it.get("owner_id").asString().ifEmpty { null },
                    it.get("title").asString(),
                    it.get("summary").asString(),
                    it.get("author_id").asString(),
                    it.get("superseded_by_id").asUuidOrNull(),
                    it.updatedAt(),
                )
            }
        }

    override suspend fun restore(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit): RestoredMemory =
        driver.inOrg(caller.orgId) {
            // The organization's memory lock, as every write that may add an entry: each limit stays exact.
            fetchAll(Statement.create("SELECT pg_advisory_xact_lock(:key)").bind("key", lockKey(caller.orgId)))
                .getOrThrow()
            val row = fetchAll(
                Statement.create(
                    "SELECT $ENTRY_COLUMNS, superseded_by_id FROM memory_entries WHERE org_id = :org_id AND id = :id " +
                        "AND status = 'superseded' AND (scope = 'team' OR owner_id = :me) " +
                        "AND (expires_at IS NULL OR expires_at > :now) FOR UPDATE",
                ).bind("org_id", caller.orgId).bind("id", id).bind("me", caller.memberId).bind("now", clock.now()),
            ).getOrThrow().firstOrNull() ?: throw MemoryNotFoundException()
            val entry = row.toEntry(emptyList())
            check(entry)
            val now = clock.now()
            val replacement = row.get("superseded_by_id").asUuidOrNull()?.let { activeVersion(caller.orgId, it) }
            if (replacement == null) requireRoom(caller.orgId, entry, now)
            execute(
                Statement.create(
                    "UPDATE memory_entries SET status = 'active', superseded_by_id = NULL, updated_at = :now, " +
                        "version = version + 1 WHERE org_id = :org_id AND id = :id",
                ).bind("now", now).bind("org_id", caller.orgId).bind("id", id),
            ).getOrThrow()
            replacement?.let { (newer, version) -> supersede(caller.orgId, newer, version, id, now) }
            val restored = fetchAll(
                Statement.create("SELECT $ENTRY_COLUMNS FROM memory_entries WHERE org_id = :org_id AND id = :id")
                    .bind("org_id", caller.orgId).bind("id", id),
            ).getOrThrow().single().toEntry(tagsOf(listOf(id))[id].orEmpty())
            RestoredMemory(restored, replacement?.first)
        }

    /** The id and version of [id] when it is active, locked. */
    private suspend fun QueryExecutor.activeVersion(orgId: Uuid, id: Uuid): Pair<Uuid, Int>? = fetchAll(
        Statement.create(
            "SELECT id, version FROM memory_entries WHERE org_id = :org_id AND id = :id AND status = 'active' " +
                "FOR UPDATE",
        ).bind("org_id", orgId).bind("id", id),
    ).getOrThrow().firstOrNull()?.let { it.id() to it.get("version").asInt() }

    /** Nothing leaves to make room: the restored entry counts against its memory's limit like any new one. */
    private suspend fun QueryExecutor.requireRoom(orgId: Uuid, entry: MemoryEntry, now: Instant) {
        val limit = if (entry.scope == MemoryScope.TEAM) MemoryRules.TEAM_LIMIT else MemoryRules.PERSONAL_LIMIT
        val held = fetchAll(
            Statement.create(
                "SELECT COUNT(*) AS n FROM memory_entries WHERE org_id = :org_id AND scope = :scope AND " +
                    "owner_id = :owner AND status IN ('active', 'proposed') " +
                    "AND (expires_at IS NULL OR expires_at > :now)",
            ).bind("org_id", orgId).bind("scope", entry.scope.wire).bind("owner", entry.ownerId.orEmpty())
                .bind("now", now),
        ).getOrThrow().first().get("n").asInt()
        if (held >= limit) throw MemoryLimitException(limit)
    }

    private companion object {
        const val OPEN = "open"
        const val APPLIED = "applied"
        const val DISMISSED = "dismissed"
        const val SETTINGS_COLUMNS =
            "curation_enabled, last_run_at, last_run_id, last_superseded, last_queued"

        /** A review with its two entries' titles and origins. */
        const val REVIEWS = "SELECT r.id, r.scope, r.owner_id, r.kind, r.reason, r.status, r.created_at, " +
            "r.older_id, o.title AS older_title, o.origin AS older_origin, " +
            "r.newer_id, n.title AS newer_title, n.origin AS newer_origin FROM memory_reviews r " +
            "JOIN memory_entries o ON o.id = r.older_id JOIN memory_entries n ON n.id = r.newer_id"

        /** The reviews a caller may act on: their own personal ones, and the organization's for an administrator. */
        fun reachable(caller: MemoryCaller): String {
            val own = "(r.scope = 'personal' AND r.owner_id = :me)"
            val scopes = if (caller.admin) "($own OR (r.scope = 'team' AND r.owner_id = ''))" else own
            return "r.org_id = :org_id AND $scopes"
        }
    }
}
