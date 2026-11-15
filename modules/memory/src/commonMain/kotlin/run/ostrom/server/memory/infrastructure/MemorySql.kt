// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryScope
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The conditions and statements of the memory's queries shared by its repositories: visibility, tags, the
// organization's lock, the count of an owner's entries and the supersession.

internal const val STATUS_ACTIVE = "active"
internal const val STATUS_PROPOSED = "proposed"
internal const val STATUS_SUPERSEDED = "superseded"
internal const val TEAM = "team"

/** The organization's memory lock, apart from every other advisory lock by its first word. */
internal fun lockKey(orgId: Uuid): Long = orgId.toLongs { most, _ -> most } xor MEMORY_LOCK

/** `memory` in ASCII. */
internal const val MEMORY_LOCK = 0x6D656D6F7279L

/**
 * Takes the organization's memory lock until the transaction ends: `SELECT pg_advisory_xact_lock(key)` on the number
 * [lockKey] (not the text hashes of the other modules' locks), as the repository took it before the move to Exposed.
 *
 * @param orgId The organization.
 */
internal fun JdbcTransaction.lockMemory(orgId: Uuid) {
    Table.Dual.select(
        CustomFunction("pg_advisory_xact_lock", TextColumnType(), QueryParameter(lockKey(orgId), LongColumnType())),
    ).toList()
}

internal fun ownerOf(caller: MemoryCaller, scope: MemoryScope): String =
    if (scope == MemoryScope.PERSONAL) caller.memberId else ""

/** The visibility of one scope: active, unexpired, the organization's or the caller's own. */
internal fun visible(caller: MemoryCaller, scope: MemoryScope, now: Instant): Op<Boolean> {
    val e = MemoryEntries
    return (e.orgId eq caller.orgId) and (e.scope eq scope.wire) and (e.ownerId eq ownerOf(caller, scope)) and
        (e.status eq STATUS_ACTIVE) and (e.expiresAt.isNull() or (e.expiresAt greater now))
}

/** Entries having any of [tags], read on `memory_tags_lookup` within the same scope and owner. */
internal fun tagged(caller: MemoryCaller, scope: MemoryScope, tags: List<String>): Op<Boolean> {
    if (tags.isEmpty()) return Op.TRUE
    val t = MemoryTags
    val matching = t.select(t.memoryId).where {
        (t.orgId eq caller.orgId) and (t.scope eq scope.wire) and (t.ownerId eq ownerOf(caller, scope)) and
            (t.tag inList tags)
    }
    return MemoryEntries.id inSubQuery matching
}

/** A `LIKE` pattern's own characters, matched literally. */
internal fun escape(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** The entries an owner holds that count against the limit: active or proposed, unexpired. */
internal fun JdbcTransaction.held(orgId: Uuid, scope: String, owner: String, now: Instant): Long {
    val e = MemoryEntries
    return e.select(e.id).where {
        (e.orgId eq orgId) and (e.scope eq scope) and (e.ownerId eq owner) and
            (e.status inList listOf(STATUS_ACTIVE, STATUS_PROPOSED)) and
            (e.expiresAt.isNull() or (e.expiresAt greater now))
    }.count()
}

/** The organization's proposals of [author] that wait for an answer. */
internal fun JdbcTransaction.proposalsOf(orgId: Uuid, author: String): Long {
    val e = MemoryEntries
    return e.select(e.id).where {
        (e.orgId eq orgId) and (e.scope eq TEAM) and (e.ownerId eq "") and (e.status eq STATUS_PROPOSED) and
            (e.authorId eq author)
    }.count()
}

/**
 * The older entry becomes `superseded` by the newer one, still active, once: a concurrent change loses on the older
 * entry's status and version.
 * The newer entry names it (`supersedes_id`) unless it already names another. Nothing is deleted. Two writes: the
 * caller's transaction makes them one (the second only when the first changed a row).
 *
 * @return The older entry's id when this call superseded it; nothing otherwise.
 */
internal fun JdbcTransaction.supersede(orgId: Uuid, older: Uuid, version: Int, newer: Uuid, now: Instant): List<Uuid> {
    val e = MemoryEntries
    val n = e.alias("n")
    val changed = e.updateReturning(
        listOf(e.id),
        where = {
            (e.orgId eq orgId) and (e.id eq older) and (e.status eq STATUS_ACTIVE) and (e.version eq version) and
                exists(
                    n.select(intLiteral(1)).where {
                        (n[e.orgId] eq orgId) and (n[e.id] eq newer) and (n[e.status] eq STATUS_ACTIVE)
                    },
                )
        },
    ) {
        it[status] = STATUS_SUPERSEDED
        it[supersededById] = newer
        it[updatedAt] = now
        it[this.version] = e.version + 1
    }.map { it[e.id] }
    if (changed.isNotEmpty()) {
        e.update({ (e.orgId eq orgId) and (e.id eq newer) and e.supersedesId.isNull() }) { it[supersedesId] = older }
    }
    return changed
}

/** Newest first, then the larger id: the order of `memory_entries_visible` read backwards. */
internal fun Query.newestFirst(): Query =
    orderBy(MemoryEntries.updatedAt to SortOrder.DESC, MemoryEntries.id to SortOrder.DESC)
