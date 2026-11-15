// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MemoryCurationStore] on the memory's tables (TeamCode's `SqlMemoryCurationStore`, its requests' half). Every read
 * is in the caller's organization and names the caller's visibility: a personal review or superseded entry is its
 * owner's alone, administrators included.
 *
 * @param database The serving role's pool.
 * @param clock The time an answer or a restore is stamped with.
 */
internal class MemoryCurationStoreImpl(
    private val database: JdbcDatabase,
    private val clock: Clock,
) : MemoryCurationStore {
    override suspend fun settings(orgId: Uuid): CurationSettings = database.inOrg(orgId) {
        val s = MemorySettings
        val row = s.select(SETTINGS_COLUMNS).where { s.orgId eq orgId }.firstOrNull()
        if (row == null) CurationSettings(enabled = false) else row.toSettings()
    }

    override suspend fun setEnabled(orgId: Uuid, enabled: Boolean): CurationSettings = database.inOrg(orgId) {
        val s = MemorySettings
        s.upsertReturning(
            s.orgId,
            returning = SETTINGS_COLUMNS,
            onUpdate = { it[s.curationEnabled] = insertValue(s.curationEnabled) },
        ) {
            it[s.orgId] = orgId
            it[curationEnabled] = enabled
        }.single().toSettings()
    }

    override suspend fun reviews(caller: MemoryCaller, limit: Int): List<MemoryReview> =
        database.inOrg(caller.orgId) {
            val r = MemoryReviews
            val (older, newer) = reviewEntries()
            reviewsQuery(older, newer).where { reachable(caller) and (r.status eq OPEN) }
                .orderBy(r.createdAt to SortOrder.ASC, r.id to SortOrder.ASC)
                .limit(limit).map { it.toReview(older, newer) }
        }

    override suspend fun resolve(caller: MemoryCaller, id: Uuid, apply: Boolean): ResolvedReview =
        database.inOrg(caller.orgId) {
            val r = MemoryReviews
            val (older, newer) = reviewEntries()
            val row = reviewsQuery(older, newer).where { reachable(caller) and (r.id eq id) }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ofTables = arrayOf(r)))
                .firstOrNull() ?: throw MemoryReviewNotFoundException()
            if (row[r.status] != OPEN) throw MemoryReviewClosedException()
            val review = row.toReview(older, newer)
            val now = clock.now()
            if (apply) applyReview(caller, review, now)
            r.update({ (r.orgId eq caller.orgId) and (r.id eq id) }) {
                it[status] = if (apply) APPLIED else DISMISSED
                it[resolvedAt] = now
            }
            ResolvedReview(review, superseded = apply)
        }

    /** Supersedes the review's older entry by its newer one, both still active. */
    private fun JdbcTransaction.applyReview(caller: MemoryCaller, review: MemoryReview, now: Instant) {
        val e = MemoryEntries
        val active = e.select(e.id, e.version)
            .where {
                (e.orgId eq caller.orgId) and (e.id inList listOf(review.olderId, review.newerId)) and
                    (e.status eq STATUS_ACTIVE)
            }
            .orderBy(e.id).forUpdate().associate { it[e.id] to it[e.version] }
        val version = active[review.olderId]
        if (version == null || review.newerId !in active) throw MemoryReviewStaleException()
        supersede(caller.orgId, review.olderId, version, review.newerId, now)
    }

    override suspend fun superseded(caller: MemoryCaller, limit: Int): List<SupersededMemory> =
        database.inOrg(caller.orgId) {
            val e = MemoryEntries
            val team = if (caller.admin) Op.TRUE else e.authorId eq caller.memberId
            e.select(e.id, e.scope, e.ownerId, e.title, e.summary, e.authorId, e.supersededById, e.updatedAt)
                .where {
                    (e.orgId eq caller.orgId) and (e.status eq STATUS_SUPERSEDED) and
                        (((e.scope eq MemoryScope.PERSONAL.wire) and (e.ownerId eq caller.memberId)) or
                            ((e.scope eq TEAM) and (e.ownerId eq "") and team))
                }
                .newestFirst().limit(limit).map {
                    SupersededMemory(
                        it[e.id],
                        scopeOf(it[e.scope]),
                        it[e.ownerId].ifEmpty { null },
                        it[e.title],
                        it[e.summary],
                        it[e.authorId],
                        it[e.supersededById],
                        it[e.updatedAt],
                    )
                }
        }

    override suspend fun restore(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit): RestoredMemory =
        database.inOrg(caller.orgId) {
            val e = MemoryEntries
            // The organization's memory lock, as every write that may add an entry: each limit stays exact.
            lockMemory(caller.orgId)
            val row = e.select(ENTRY_COLUMNS + e.supersededById)
                .where {
                    (e.orgId eq caller.orgId) and (e.id eq id) and (e.status eq STATUS_SUPERSEDED) and
                        ((e.scope eq TEAM) or (e.ownerId eq caller.memberId)) and
                        (e.expiresAt.isNull() or (e.expiresAt greater clock.now()))
                }
                .forUpdate().firstOrNull() ?: throw MemoryNotFoundException()
            val entry = row.toEntry(emptyList())
            check(entry)
            val now = clock.now()
            val replacement = row[e.supersededById]?.let { activeVersion(caller.orgId, it) }
            if (replacement == null) requireRoom(caller.orgId, entry, now)
            e.update({ (e.orgId eq caller.orgId) and (e.id eq id) }) {
                it[status] = STATUS_ACTIVE
                it[supersededById] = null
                it[updatedAt] = now
                it[version] = version + 1
            }
            replacement?.let { (newer, version) -> supersede(caller.orgId, newer, version, id, now) }
            val restored = e.select(ENTRY_COLUMNS).where { (e.orgId eq caller.orgId) and (e.id eq id) }.single()
                .toEntry(tagsOf(listOf(id))[id].orEmpty())
            RestoredMemory(restored, replacement?.first)
        }

    /** The id and version of [id] when it is active, locked. */
    private fun JdbcTransaction.activeVersion(orgId: Uuid, id: Uuid): Pair<Uuid, Int>? {
        val e = MemoryEntries
        return e.select(e.id, e.version)
            .where { (e.orgId eq orgId) and (e.id eq id) and (e.status eq STATUS_ACTIVE) }
            .forUpdate().firstOrNull()?.let { it[e.id] to it[e.version] }
    }

    /** Nothing leaves to make room: the restored entry counts against its memory's limit like any new one. */
    private fun JdbcTransaction.requireRoom(orgId: Uuid, entry: MemoryEntry, now: Instant) {
        val limit = if (entry.scope == MemoryScope.TEAM) MemoryRules.TEAM_LIMIT else MemoryRules.PERSONAL_LIMIT
        if (held(orgId, entry.scope.wire, entry.ownerId.orEmpty(), now) >= limit) throw MemoryLimitException(limit)
    }

    private companion object {
        const val OPEN = "open"
        const val APPLIED = "applied"
        const val DISMISSED = "dismissed"
    }
}
