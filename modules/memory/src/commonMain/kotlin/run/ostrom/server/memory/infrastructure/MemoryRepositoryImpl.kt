// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCursor
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryIndexEntry
import run.ostrom.server.memory.domain.MemoryLimitException
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryPage
import run.ostrom.server.memory.domain.MemoryProposalsLimitException
import run.ostrom.server.memory.domain.MemoryRepository
import run.ostrom.server.memory.domain.MemoryRules
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.MemoryUpdate
import run.ostrom.server.memory.domain.NewMemory
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid


/**
 * [MemoryRepository] on PostgreSQL ([MemoryMigrations]), TeamCode's `SqlMemoryStore`. Visibility is in every query: an
 * organization's entry for each of its members, a personal entry for its owner only (`owner_id` names them), so no
 * route, administrator or key of another member reaches it. A list is at most three reads in one transaction (one per
 * scope on `memory_entries_visible`, then the tags of the rows returned), never one per row, and never reads `content`.
 *
 * @param database The serving role's pool.
 * @param clock The time an expiry is compared with and an entry is stamped with.
 */
internal class MemoryRepositoryImpl(private val database: JdbcDatabase, private val clock: Clock) : MemoryRepository {
    override suspend fun index(
        caller: MemoryCaller,
        scope: MemoryScope?,
        tags: List<String>,
        after: MemoryCursor?,
        limit: Int,
    ): MemoryPage = database.inOrg(caller.orgId) {
        val e = MemoryEntries
        val now = clock.now()
        val rows = (if (scope == null) MemoryScope.entries else listOf(scope)).flatMap { s ->
            val beyond = if (after == null) Op.TRUE else
                (e.updatedAt less after.updatedAt) or ((e.updatedAt eq after.updatedAt) and (e.id less after.id))
            e.select(INDEX_COLUMNS).where { visible(caller, s, now) and tagged(caller, s, tags) and beyond }
                .newestFirst().limit(limit + 1).toList()
        }.sortedWith(NEWEST_FIRST)
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.last().let { MemoryCursor(it[e.updatedAt], it[e.id]) } else null
        MemoryPage(indexEntries(page), next)
    }

    override suspend fun search(
        caller: MemoryCaller,
        terms: List<String>,
        tags: List<String>,
        limit: Int,
    ): List<MemoryIndexEntry> = database.inOrg(caller.orgId) {
        val e = MemoryEntries
        val t = MemoryTags
        val now = clock.now()
        val rows = MemoryScope.entries.flatMap { s ->
            // Every term in the title, the summary or a tag of the entry (never the content).
            val matching = terms.map { term ->
                val pattern = "%${escape(term)}%"
                val inTag = t.select(t.memoryId).where {
                    (t.orgId eq caller.orgId) and (t.scope eq s.wire) and (t.ownerId eq ownerOf(caller, s)) and
                        (t.tag like pattern)
                }
                (e.title ilike pattern) or (e.summary ilike pattern) or (e.id inSubQuery inTag)
            }
            e.select(INDEX_COLUMNS)
                .where {
                    matching.fold(visible(caller, s, now) and tagged(caller, s, tags)) { all, one -> all and one }
                }
                .newestFirst().limit(limit).toList()
        }.sortedWith(NEWEST_FIRST).take(limit)
        indexEntries(rows)
    }

    override suspend fun read(caller: MemoryCaller, id: Uuid): MemoryEntry = database.inOrg(caller.orgId) {
        val e = MemoryEntries
        val now = clock.now()
        val row = readable(caller, id, now, lock = false) ?: throw MemoryNotFoundException()
        // An increment in place, so two readers of an organization's entry neither wait nor lose a count.
        e.update({ (e.orgId eq caller.orgId) and (e.id eq id) }) {
            it[lastReadAt] = now
            it[readCount] = e.readCount + 1
        }
        val entry = row.toEntry(tagsOf(listOf(id))[id].orEmpty())
        entry.copy(lastReadAt = now, readCount = entry.readCount + 1)
    }

    override suspend fun create(
        caller: MemoryCaller,
        entry: NewMemory,
        origin: MemoryOrigin,
        sessionId: String?,
        status: MemoryStatus,
    ): MemoryEntry = database.inOrg(caller.orgId) {
        val personal = entry.scope == MemoryScope.PERSONAL
        val owner = ownerOf(caller, entry.scope)
        // Every write takes the organization's memory lock, so each limit is exact: the organization's count is
        // shared by its members, and a member's own may be written by several of their agents at once. An expired
        // entry, which nobody reads or forgets any more, holds no place.
        lockMemory(caller.orgId)
        val limit = if (personal) MemoryRules.PERSONAL_LIMIT else MemoryRules.TEAM_LIMIT
        val now = clock.now()
        if (held(caller.orgId, entry.scope.wire, owner, now) >= limit) throw MemoryLimitException(limit)
        // Per member: the proposals of all their agents, so one runaway agent cannot fill the organization's room.
        val proposing = status == MemoryStatus.PROPOSED
        if (proposing && proposalsOf(caller.orgId, caller.memberId) >= MemoryRules.PROPOSALS_LIMIT) {
            throw MemoryProposalsLimitException(MemoryRules.PROPOSALS_LIMIT)
        }
        val id = Uuid.random()
        MemoryEntries.insert {
            it[this.id] = id
            it[orgId] = caller.orgId
            it[scope] = entry.scope.wire
            it[ownerId] = owner
            it[title] = entry.title
            it[summary] = entry.summary
            it[content] = entry.content
            it[refs] = REFS.encode(entry.refs)
            it[this.status] = status.wire
            it[this.origin] = origin.wire
            it[authorId] = caller.memberId
            it[this.sessionId] = sessionId
            it[expiresAt] = entry.expiresAt
            it[version] = 1
            it[createdAt] = now
            it[updatedAt] = now
            it[readCount] = 0
        }
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
    ): MemoryEntry = database.inOrg(caller.orgId) {
        val e = MemoryEntries
        val now = clock.now()
        val before = (readable(caller, id, now, lock = true) ?: throw MemoryNotFoundException()).toEntry(emptyList())
        check(before)
        e.update({ (e.orgId eq caller.orgId) and (e.id eq id) }) {
            it[title] = update.title
            it[summary] = update.summary
            it[content] = update.content
            it[refs] = REFS.encode(update.refs)
            it[expiresAt] = update.expiresAt
            it[version] = before.version + 1
            it[updatedAt] = now
        }
        MemoryTags.deleteWhere { (MemoryTags.orgId eq caller.orgId) and (MemoryTags.memoryId eq id) }
        insertTags(caller, id, before.scope, before.ownerId.orEmpty(), update.tags)
        before.copy(
            title = update.title, summary = update.summary, content = update.content, tags = update.tags,
            refs = update.refs, expiresAt = update.expiresAt, version = before.version + 1, updatedAt = now,
        )
    }

    override suspend fun delete(caller: MemoryCaller, id: Uuid, check: (MemoryEntry) -> Unit) {
        database.inOrg(caller.orgId) {
            val entry = (readable(caller, id, clock.now(), lock = true) ?: throw MemoryNotFoundException())
                .toEntry(emptyList())
            check(entry)
            // The tags go with the entry (ON DELETE CASCADE).
            MemoryEntries.deleteWhere { (orgId eq caller.orgId) and (MemoryEntries.id eq id) }
        }
    }

    override suspend fun purge(orgId: Uuid) {
        database.inOrg(orgId) {
            // The reviews and tags go with their entries.
            MemoryEntries.deleteWhere { MemoryEntries.orgId eq orgId }
            MemorySettings.deleteWhere { MemorySettings.orgId eq orgId }
            MemoryCurationState.deleteWhere { MemoryCurationState.orgId eq orgId }
        }
    }

    /** An active, unexpired entry the caller may read: the organization's, or their own personal one. */
    private fun JdbcTransaction.readable(caller: MemoryCaller, id: Uuid, now: Instant, lock: Boolean): ResultRow? {
        val e = MemoryEntries
        val query = e.select(ENTRY_COLUMNS).where {
            (e.orgId eq caller.orgId) and (e.id eq id) and (e.status eq STATUS_ACTIVE) and
                (e.expiresAt.isNull() or (e.expiresAt greater now)) and
                (((e.scope eq TEAM) and (e.ownerId eq "")) or
                    ((e.scope eq MemoryScope.PERSONAL.wire) and (e.ownerId eq caller.memberId)))
        }
        return (if (lock) query.forUpdate() else query).firstOrNull()
    }

    private fun JdbcTransaction.insertTags(
        caller: MemoryCaller,
        id: Uuid,
        scope: MemoryScope,
        owner: String,
        tags: List<String>,
    ) {
        for (tag in tags) {
            MemoryTags.insert {
                it[memoryId] = id
                it[this.tag] = tag
                it[orgId] = caller.orgId
                it[this.scope] = scope.wire
                it[ownerId] = owner
            }
        }
    }
}
