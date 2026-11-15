// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.memory.domain.CuratedScope
import run.ostrom.server.memory.domain.CurationCandidate
import run.ostrom.server.memory.domain.CurationDecision
import run.ostrom.server.memory.domain.CurationScope
import run.ostrom.server.memory.domain.CurationTitle
import run.ostrom.server.memory.domain.MemoryCurationRuns
import run.ostrom.server.memory.domain.MemoryCurator
import run.ostrom.server.memory.domain.MemoryRules
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MemoryCurationRuns] on the memory's tables (TeamCode's `SqlMemoryCurationStore.curate`): the organizations come
 * from `memory_kept_orgs`, owned by `ostrom_router`, which returns their ids only; everything else is read and written
 * in each organization, one scope per transaction. A scope's titles are read first, then the content of at most
 * [MemoryRules.CURATION_GROUP_SIZE] entries of each same-title group.
 *
 * @param database The serving role's pool.
 */
internal class MemoryCurationRunsImpl(private val database: JdbcDatabase) : MemoryCurationRuns {
    override suspend fun organizations(after: Uuid?, limit: Int): List<Uuid> = database.query {
        val f = MemoryKeptOrgs
        f.of(after, limit).select(f.orgId).map { it[f.orgId] }
    }

    override suspend fun due(orgId: Uuid, room: Int, now: Instant): List<CurationScope> = database.inOrg(orgId) {
        val e = MemoryEntries
        val s = MemoryCurationState
        val settings = MemorySettings
        val changed = e.updatedAt.max().alias("changed")
        val kept = e.select(e.scope, e.ownerId, changed)
            .where { (e.orgId eq orgId) and (e.status eq STATUS_ACTIVE) }
            .groupBy(e.scope, e.ownerId).alias("e")
        val keptScope = kept[e.scope]
        val keptOwner = kept[e.ownerId]
        val keptChanged = kept[changed]
        kept.join(
            s,
            JoinType.LEFT,
            onColumn = keptScope,
            otherColumn = s.scope,
            additionalConstraint = { (s.orgId eq orgId) and (keptOwner eq s.ownerId) },
        )
            .select(keptScope, keptOwner)
            .where {
                val enabled = settings.select(intLiteral(1))
                    .where { (settings.orgId eq orgId) and settings.curationEnabled }
                exists(enabled) and
                    (s.curatedAt.isNull() or (keptChanged greater s.curatedAt) or
                        (s.curatedAt less now - MemoryRules.RECURATE_DAYS.days))
            }
            .orderBy(s.curatedAt to SortOrder.ASC_NULLS_FIRST, keptScope to SortOrder.ASC, keptOwner to SortOrder.ASC)
            .limit(room)
            .map { CurationScope(scopeOf(it[keptScope]), it[keptOwner]) }
    }

    override suspend fun curate(orgId: Uuid, scope: CurationScope, now: Instant): CuratedScope = database.inOrg(orgId) {
        val e = MemoryEntries
        val rows = e.select(e.id, e.title, e.version)
            .where {
                (e.orgId eq orgId) and (e.scope eq scope.scope.wire) and (e.ownerId eq scope.ownerId) and
                    (e.status eq STATUS_ACTIVE) and (e.expiresAt.isNull() or (e.expiresAt greater now))
            }
            .newestFirst().limit((MemoryRules.TEAM_LIMIT + 1).toInt()).toList()
        val versions = rows.associate { it[e.id] to it[e.version] }
        val groups = MemoryCurator.groups(rows.map { CurationTitle(it[e.id], it[e.title]) })
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
        val s = MemoryCurationState
        s.upsert(s.orgId, s.scope, s.ownerId, onUpdate = { it[s.curatedAt] = now }) {
            it[s.orgId] = orgId
            it[s.scope] = scope.scope.wire
            it[ownerId] = scope.ownerId
            it[curatedAt] = now
        }
        CuratedScope(superseded, queued)
    }

    /** The content, origin and `external` tag of [ids], in one read each. */
    private fun JdbcTransaction.candidates(orgId: Uuid, ids: List<Uuid>): Map<Uuid, CurationCandidate> {
        if (ids.isEmpty()) return emptyMap()
        val t = MemoryTags
        val e = MemoryEntries
        val external = t.select(t.memoryId)
            .where { (t.orgId eq orgId) and (t.tag eq EXTERNAL) and (t.memoryId inList ids) }
            .map { it[t.memoryId] }.toSet()
        return e.select(e.id, e.content, e.origin).where { (e.orgId eq orgId) and (e.id inList ids) }.associate {
            it[e.id] to CurationCandidate(it[e.id], it[e.content], originOf(it[e.origin]), it[e.id] in external)
        }
    }

    /** Queues [decision]'s pair once: a pair already queued, answered or not, is not again. */
    private fun JdbcTransaction.queue(
        orgId: Uuid,
        scope: CurationScope,
        decision: CurationDecision.Queue,
        now: Instant,
    ): Int =
        MemoryReviews.insertIgnore {
            it[id] = Uuid.random()
            it[this.orgId] = orgId
            it[this.scope] = scope.scope.wire
            it[ownerId] = scope.ownerId
            it[olderId] = decision.olderId
            it[newerId] = decision.newerId
            it[kind] = decision.kind.wire
            it[reason] = decision.reason
            it[status] = OPEN
            it[createdAt] = now
        }.insertedCount

    override suspend fun recordRun(orgId: Uuid, runId: String, now: Instant, superseded: Int, queued: Int) {
        database.inOrg(orgId) {
            val s = MemorySettings
            s.update({ s.orgId eq orgId }) {
                it[lastRunAt] = now
                it[lastRunId] = runId
                it[lastSuperseded] = superseded
                it[lastQueued] = queued
            }
        }
    }

    override suspend fun retain(orgId: Uuid, now: Instant): List<Uuid> = database.inOrg(orgId) {
        val e = MemoryEntries
        val expiredIds = e.select(e.id)
            .where {
                (e.orgId eq orgId) and (e.status neq STATUS_PROPOSED) and
                    (e.expiresAt less now - MemoryRules.EXPIRED_DAYS.days)
            }
            .orderBy(e.expiresAt).limit(RETAIN_LIMIT)
        val expired = e.deleteReturning(listOf(e.id), where = { (e.orgId eq orgId) and (e.id inSubQuery expiredIds) })
            .map { it[e.id] }
        e.deleteWhere {
            (e.orgId eq orgId) and (e.status eq STATUS_SUPERSEDED) and
                (e.updatedAt less now - MemoryRules.SUPERSEDED_DAYS.days)
        }
        val r = MemoryReviews
        r.deleteWhere {
            (r.orgId eq orgId) and (r.status neq OPEN) and (r.resolvedAt less now - MemoryRules.REJECTED_DAYS.days)
        }
        expired
    }

    private companion object {
        /** The expired entries one run deletes in an organization; the rest go at the next run. */
        const val RETAIN_LIMIT = 1_000
        const val OPEN = "open"
        const val EXTERNAL = "external"
    }
}
