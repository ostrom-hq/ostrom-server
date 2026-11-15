// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCursor
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryNotProposedException
import run.ostrom.server.memory.domain.MemoryProposalStore
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.PendingProposal
import run.ostrom.server.memory.domain.ProposalEdits
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MemoryProposalStore] on `memory_entries` (TeamCode's proposal half of `SqlMemoryStore`): a proposal is an
 * organization entry with the status `proposed` and the id of its wait (`proposal_wait_id`). Every request reads in
 * the caller's organization; the settling pass finds the organizations to settle through `memory_settling_orgs`, a
 * function owned by `ostrom_router` that returns their ids only, then settles each one in its own.
 *
 * @param database The serving role's pool.
 * @param clock The time a decision is stamped with.
 */
internal class MemoryProposalStoreImpl(
    private val database: JdbcDatabase,
    private val clock: Clock,
) : MemoryProposalStore {
    override suspend fun attach(orgId: Uuid, id: Uuid, waitId: Uuid) {
        database.inOrg(orgId) {
            val e = MemoryEntries
            e.update({ (e.orgId eq orgId) and (e.id eq id) and (e.status eq STATUS_PROPOSED) }) {
                it[proposalWaitId] = waitId
            }
        }
    }

    override suspend fun proposals(caller: MemoryCaller, limit: Int): List<MemoryEntry> =
        database.inOrg(caller.orgId) {
            val e = MemoryEntries
            val rows = e.select(ENTRY_COLUMNS).where {
                (e.orgId eq caller.orgId) and (e.scope eq TEAM) and (e.ownerId eq "") and
                    (e.status eq STATUS_PROPOSED) and (if (caller.admin) Op.TRUE else e.authorId eq caller.memberId)
            }.orderBy(e.updatedAt to SortOrder.ASC, e.id to SortOrder.ASC).limit(limit).toList()
            entriesOf(rows)
        }

    override suspend fun decide(
        caller: MemoryCaller,
        id: Uuid,
        accept: Boolean,
        check: (MemoryEntry) -> Unit,
    ): PendingProposal = database.inOrg(caller.orgId) {
        val e = MemoryEntries
        val row = e.select(ENTRY_COLUMNS + e.proposalWaitId)
            .where { (e.orgId eq caller.orgId) and (e.id eq id) and (e.scope eq TEAM) }
            .forUpdate().firstOrNull() ?: throw MemoryNotFoundException()
        val entry = entriesOf(listOf(row)).single()
        check(entry)
        if (entry.status != MemoryStatus.PROPOSED) throw MemoryNotProposedException()
        val now = clock.now()
        closeOne(caller.orgId, id, accept, null, now)
        val status = if (accept) MemoryStatus.ACTIVE else MemoryStatus.REJECTED
        val settled = entry.copy(status = status, version = entry.version + 1, updatedAt = now)
        PendingProposal(settled, row[e.proposalWaitId])
    }

    // The serving role alone runs the function; it returns organizations' ids, nothing of an entry.
    override suspend fun organizations(after: Uuid?, limit: Int): List<Uuid> = database.query {
        val f = MemorySettlingOrgs
        f.of(after, limit).select(f.orgId).map { it[f.orgId] }
    }

    override suspend fun pending(orgId: Uuid, after: MemoryCursor?, limit: Int): List<PendingProposal> =
        database.inOrg(orgId) {
            val e = MemoryEntries
            val beyond = if (after == null) Op.TRUE else
                (e.updatedAt greater after.updatedAt) or ((e.updatedAt eq after.updatedAt) and (e.id greater after.id))
            val rows = e.select(ENTRY_COLUMNS + e.proposalWaitId)
                .where { (e.orgId eq orgId) and (e.status eq STATUS_PROPOSED) and beyond }
                .orderBy(e.updatedAt to SortOrder.ASC, e.id to SortOrder.ASC).limit(limit).toList()
            entriesOf(rows).zip(rows) { entry, row -> PendingProposal(entry, row[e.proposalWaitId]) }
        }

    override suspend fun close(orgId: Uuid, id: Uuid, accept: Boolean, edits: ProposalEdits?): Boolean =
        database.inOrg(orgId) { closeOne(orgId, id, accept, edits, clock.now()) }

    override suspend fun purgeRejected(orgId: Uuid, before: Instant) {
        database.inOrg(orgId) {
            val e = MemoryEntries
            e.deleteWhere { (e.orgId eq orgId) and (e.status eq REJECTED) and (e.updatedAt less before) }
        }
    }

    /** Accepts or rejects a proposal still waiting, once: a concurrent replica loses on the status. */
    private fun JdbcTransaction.closeOne(
        orgId: Uuid,
        id: Uuid,
        accept: Boolean,
        edits: ProposalEdits?,
        now: Instant,
    ): Boolean {
        val e = MemoryEntries
        val closed = e.update({ (e.orgId eq orgId) and (e.id eq id) and (e.status eq STATUS_PROPOSED) }) {
            it[status] = if (accept) STATUS_ACTIVE else REJECTED
            it[version] = version + 1
            it[updatedAt] = now
            if (edits != null) {
                it[title] = edits.title
                it[summary] = edits.summary
                it[content] = edits.content
            }
        }
        return closed == 1
    }

    private fun JdbcTransaction.entriesOf(rows: List<ResultRow>): List<MemoryEntry> {
        val tags = tagsOf(rows.map { it[MemoryEntries.id] })
        return rows.map { it.toEntry(tags[it[MemoryEntries.id]].orEmpty()) }
    }

    private companion object {
        const val REJECTED = "rejected"
    }
}
