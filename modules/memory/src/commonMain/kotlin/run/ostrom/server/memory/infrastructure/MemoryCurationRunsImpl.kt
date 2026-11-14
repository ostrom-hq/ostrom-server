// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.memory.domain.CuratedScope
import run.ostrom.server.memory.domain.CurationCandidate
import run.ostrom.server.memory.domain.CurationDecision
import run.ostrom.server.memory.domain.CurationScope
import run.ostrom.server.memory.domain.CurationTitle
import run.ostrom.server.memory.domain.MemoryCurationRuns
import run.ostrom.server.memory.domain.MemoryCurator
import run.ostrom.server.memory.domain.MemoryRules
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MemoryCurationRuns] on the memory's tables (TeamCode's `SqlMemoryCurationStore.curate`): the organizations come
 * from `memory_kept_orgs`, owned by `ostrom_router`, which returns their ids only; everything else is read and written
 * in each organization, one scope per transaction. A scope's titles are read first, then the content of at most
 * [MemoryRules.CURATION_GROUP_SIZE] entries of each same-title group.
 *
 * @param driver The database.
 */
internal class MemoryCurationRunsImpl(private val driver: Driver) : MemoryCurationRuns {
    override suspend fun organizations(after: Uuid?, limit: Int): List<Uuid> = driver.fetchAll(
        Statement.create("SELECT org_id FROM memory_kept_orgs(CAST(:after AS UUID), CAST(:limit AS INTEGER))")
            .bind("after", after).bind("limit", limit),
    ).getOrThrow().map { it.get("org_id").asUuid() }

    override suspend fun due(orgId: Uuid, room: Int, now: Instant): List<CurationScope> = driver.inOrg(orgId) {
        fetchAll(
            Statement.create(
                """
                SELECT e.scope, e.owner_id FROM (
                    SELECT scope, owner_id, MAX(updated_at) AS changed FROM memory_entries
                    WHERE org_id = :org_id AND status = 'active' GROUP BY scope, owner_id
                ) e
                LEFT JOIN memory_curation_state s
                    ON s.org_id = :org_id AND s.scope = e.scope AND s.owner_id = e.owner_id
                WHERE EXISTS (SELECT 1 FROM memory_settings WHERE org_id = :org_id AND curation_enabled)
                AND (s.curated_at IS NULL OR e.changed > s.curated_at OR s.curated_at < :stale)
                ORDER BY s.curated_at NULLS FIRST, e.scope, e.owner_id LIMIT :room
                """.trimIndent(),
            ).bind("org_id", orgId).bind("stale", now - MemoryRules.RECURATE_DAYS.days).bind("room", room),
        ).getOrThrow().map { CurationScope(scopeOf(it.get("scope").asString()), it.get("owner_id").asString()) }
    }

    override suspend fun curate(orgId: Uuid, scope: CurationScope, now: Instant): CuratedScope = driver.inOrg(orgId) {
        val rows = fetchAll(
            Statement.create(
                "SELECT id, title, version FROM memory_entries WHERE org_id = :org_id AND scope = :scope " +
                    "AND owner_id = :owner AND status = 'active' AND (expires_at IS NULL OR expires_at > :now) " +
                    "ORDER BY updated_at DESC, id DESC LIMIT :limit",
            ).bind("org_id", orgId).bind("scope", scope.scope.wire).bind("owner", scope.ownerId).bind("now", now)
                .bind("limit", MemoryRules.TEAM_LIMIT + 1),
        ).getOrThrow().toList()
        val versions = rows.associate { it.id() to it.get("version").asInt() }
        val groups = MemoryCurator.groups(rows.map { CurationTitle(it.id(), it.get("title").asString()) })
        val candidates = candidates(orgId, groups.flatten())
        val superseded = mutableListOf<Pair<Uuid, Uuid>>()
        var queued = 0
        for (decision in groups.flatMap { group -> MemoryCurator.decide(group.map(candidates::getValue)) }) {
            when (decision) {
                is CurationDecision.Supersede -> {
                    val older = decision.olderId
                    val done = supersede(orgId, older, versions.getValue(older), decision.newerId, now)
                    superseded += done.map { it to decision.newerId }
                }
                is CurationDecision.Queue -> queued += queue(orgId, scope, decision, now)
            }
        }
        execute(
            Statement.create(
                "INSERT INTO memory_curation_state (org_id, scope, owner_id, curated_at) VALUES (:org_id, :scope, " +
                    ":owner, :now) ON CONFLICT (org_id, scope, owner_id) DO UPDATE SET curated_at = :now",
            ).bind("org_id", orgId).bind("scope", scope.scope.wire).bind("owner", scope.ownerId).bind("now", now),
        ).getOrThrow()
        CuratedScope(superseded, queued)
    }

    /** The content, origin and `external` tag of [ids], in one read each. */
    private suspend fun QueryExecutor.candidates(orgId: Uuid, ids: List<Uuid>): Map<Uuid, CurationCandidate> {
        if (ids.isEmpty()) return emptyMap()
        val list = ids.joinToString(",")
        val external = fetchAll(
            Statement.create(
                "SELECT memory_id FROM memory_tags WHERE org_id = :org_id AND tag = 'external' " +
                    "AND memory_id = ANY(CAST(string_to_array(:ids, ',') AS UUID[]))",
            ).bind("org_id", orgId).bind("ids", list),
        ).getOrThrow().map { it.get("memory_id").asUuid() }.toSet()
        return fetchAll(
            Statement.create(
                "SELECT id, content, origin FROM memory_entries WHERE org_id = :org_id " +
                    "AND id = ANY(CAST(string_to_array(:ids, ',') AS UUID[]))",
            ).bind("org_id", orgId).bind("ids", list),
        ).getOrThrow().associate {
            it.id() to CurationCandidate(
                it.id(),
                it.get("content").asString(),
                originOf(it.get("origin").asString()),
                it.id() in external,
            )
        }
    }

    /** Queues [decision]'s pair once: a pair already queued, answered or not, is not again. */
    private suspend fun QueryExecutor.queue(
        orgId: Uuid,
        scope: CurationScope,
        decision: CurationDecision.Queue,
        now: Instant,
    ): Int = execute(
        Statement.create(
            "INSERT INTO memory_reviews (id, org_id, scope, owner_id, older_id, newer_id, kind, reason, status, " +
                "created_at) VALUES (:id, :org_id, :scope, :owner, :older, :newer, :kind, :reason, 'open', :now) " +
                "ON CONFLICT (older_id, newer_id) DO NOTHING",
        ).bind("id", Uuid.random()).bind("org_id", orgId).bind("scope", scope.scope.wire).bind("owner", scope.ownerId)
            .bind("older", decision.olderId).bind("newer", decision.newerId).bind("kind", decision.kind.wire)
            .bind("reason", decision.reason).bind("now", now),
    ).getOrThrow().toInt()

    override suspend fun recordRun(orgId: Uuid, runId: String, now: Instant, superseded: Int, queued: Int) {
        driver.inOrg(orgId) {
            execute(
                Statement.create(
                    "UPDATE memory_settings SET last_run_at = :now, last_run_id = :run, " +
                        "last_superseded = :superseded, last_queued = :queued WHERE org_id = :org_id",
                ).bind("now", now).bind("run", runId).bind("superseded", superseded).bind("queued", queued)
                    .bind("org_id", orgId),
            ).getOrThrow()
        }
    }

    override suspend fun retain(orgId: Uuid, now: Instant): List<Uuid> = driver.inOrg(orgId) {
        val expired = fetchAll(
            Statement.create(
                "DELETE FROM memory_entries WHERE org_id = :org_id AND id IN (SELECT id FROM memory_entries " +
                    "WHERE org_id = :org_id AND status <> 'proposed' AND expires_at < :expired " +
                    "ORDER BY expires_at LIMIT :limit) RETURNING id",
            ).bind("org_id", orgId).bind("expired", now - MemoryRules.EXPIRED_DAYS.days).bind("limit", RETAIN_LIMIT),
        ).getOrThrow().map { it.id() }
        execute(
            Statement.create(
                "DELETE FROM memory_entries WHERE org_id = :org_id AND status = 'superseded' AND updated_at < :before",
            ).bind("org_id", orgId).bind("before", now - MemoryRules.SUPERSEDED_DAYS.days),
        ).getOrThrow()
        execute(
            Statement.create(
                "DELETE FROM memory_reviews WHERE org_id = :org_id AND status <> 'open' AND resolved_at < :before",
            ).bind("org_id", orgId).bind("before", now - MemoryRules.REJECTED_DAYS.days),
        ).getOrThrow()
        expired
    }

    private companion object {
        /** The expired entries one run deletes in an organization; the rest go at the next run. */
        const val RETAIN_LIMIT = 1_000
    }
}
