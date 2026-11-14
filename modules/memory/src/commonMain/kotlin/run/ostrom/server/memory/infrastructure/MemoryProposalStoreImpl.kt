// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryNotProposedException
import run.ostrom.server.memory.domain.MemoryProposalStore
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.PendingProposal
import run.ostrom.server.memory.domain.ProposalEdits
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MemoryProposalStore] on `memory_entries` (TeamCode's proposal half of `SqlMemoryStore`): a proposal is an
 * organization entry with the status `proposed` and the id of its wait (`proposal_wait_id`). Every request reads in
 * the caller's organization; the settling pass finds the organizations to settle through `memory_settling_orgs`, a
 * function owned by `ostrom_router` that returns their ids only, then settles each one in its own.
 *
 * @param driver The database.
 * @param clock The time a decision is stamped with.
 */
internal class MemoryProposalStoreImpl(private val driver: Driver, private val clock: Clock) : MemoryProposalStore {
    override suspend fun attach(orgId: Uuid, id: Uuid, waitId: Uuid) {
        driver.inOrg(orgId) {
            execute(
                Statement.create(
                    "UPDATE memory_entries SET proposal_wait_id = :wait WHERE org_id = :org_id AND id = :id " +
                        "AND status = 'proposed'",
                ).bind("wait", waitId).bind("org_id", orgId).bind("id", id),
            ).getOrThrow()
        }
    }

    override suspend fun proposals(caller: MemoryCaller, limit: Int): List<MemoryEntry> = driver.inOrg(caller.orgId) {
        val mine = if (caller.admin) "" else " AND author_id = :author"
        val statement = Statement.create(
            "SELECT $ENTRY_COLUMNS FROM memory_entries WHERE org_id = :org_id AND scope = 'team' AND owner_id = '' " +
                "AND status = 'proposed'$mine ORDER BY updated_at, id LIMIT :limit",
        ).bind("org_id", caller.orgId).bind("limit", limit)
        if (!caller.admin) statement.bind("author", caller.memberId)
        entriesOf(fetchAll(statement).getOrThrow().toList())
    }

    override suspend fun decide(
        caller: MemoryCaller,
        id: Uuid,
        accept: Boolean,
        check: (MemoryEntry) -> Unit,
    ): PendingProposal = driver.inOrg(caller.orgId) {
        val row = fetchAll(
            Statement.create(
                "SELECT $ENTRY_COLUMNS, proposal_wait_id FROM memory_entries WHERE org_id = :org_id AND id = :id " +
                    "AND scope = 'team' FOR UPDATE",
            ).bind("org_id", caller.orgId).bind("id", id),
        ).getOrThrow().firstOrNull() ?: throw MemoryNotFoundException()
        val entry = entriesOf(listOf(row)).single()
        if (entry.status != MemoryStatus.PROPOSED) throw MemoryNotProposedException()
        check(entry)
        val now = clock.now()
        closeOne(caller.orgId, id, accept, null, now)
        val status = if (accept) MemoryStatus.ACTIVE else MemoryStatus.REJECTED
        PendingProposal(
            entry.copy(status = status, version = entry.version + 1, updatedAt = now),
            row.get("proposal_wait_id").asUuidOrNull(),
        )
    }

    override suspend fun organizations(limit: Int): List<Uuid> = driver.fetchAll(
        // The serving role alone runs the function; it returns organizations' ids, nothing of an entry.
        Statement.create("SELECT org_id FROM memory_settling_orgs(CAST(:limit AS INTEGER))").bind("limit", limit),
    ).getOrThrow().map { it.get("org_id").asUuid() }

    override suspend fun pending(orgId: Uuid, limit: Int): List<PendingProposal> = driver.inOrg(orgId) {
        val rows = fetchAll(
            Statement.create(
                "SELECT $ENTRY_COLUMNS, proposal_wait_id FROM memory_entries WHERE org_id = :org_id " +
                    "AND status = 'proposed' ORDER BY updated_at, id LIMIT :limit",
            ).bind("org_id", orgId).bind("limit", limit),
        ).getOrThrow().toList()
        entriesOf(rows).zip(rows) { entry, row -> PendingProposal(entry, row.get("proposal_wait_id").asUuidOrNull()) }
    }

    override suspend fun close(orgId: Uuid, id: Uuid, accept: Boolean, edits: ProposalEdits?): Boolean =
        driver.inOrg(orgId) { closeOne(orgId, id, accept, edits, clock.now()) }

    override suspend fun purgeRejected(orgId: Uuid, before: Instant) {
        driver.inOrg(orgId) {
            execute(
                Statement.create(
                    "DELETE FROM memory_entries WHERE org_id = :org_id AND status = 'rejected' " +
                        "AND updated_at < :before",
                ).bind("org_id", orgId).bind("before", before),
            ).getOrThrow()
        }
    }

    /** Accepts or rejects a proposal still waiting, once: a concurrent replica loses on the status. */
    private suspend fun QueryExecutor.closeOne(
        orgId: Uuid,
        id: Uuid,
        accept: Boolean,
        edits: ProposalEdits?,
        now: Instant,
    ): Boolean {
        val text = if (edits == null) "" else ", title = :title, summary = :summary, content = :content"
        val statement = Statement.create(
            "UPDATE memory_entries SET status = :status, version = version + 1, updated_at = :now$text " +
                "WHERE org_id = :org_id AND id = :id AND status = 'proposed'",
        ).bind("status", if (accept) "active" else "rejected").bind("now", now).bind("org_id", orgId).bind("id", id)
        if (edits != null) {
            statement.bind("title", edits.title).bind("summary", edits.summary).bind("content", edits.content)
        }
        return execute(statement).getOrThrow() == 1L
    }

    private suspend fun QueryExecutor.entriesOf(rows: List<ResultSet.Row>): List<MemoryEntry> {
        val tags = tagsOf(rows.map { it.id() })
        return rows.map { it.toEntry(tags[it.id()].orEmpty()) }
    }
}
