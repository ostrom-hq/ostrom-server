// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.fakes

import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCursor
import run.ostrom.server.memory.domain.MemoryEntry
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryNotProposedException
import run.ostrom.server.memory.domain.MemoryProposalStore
import run.ostrom.server.memory.domain.MemoryProposalWaits
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemorySessionNotFoundException
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.PendingProposal
import run.ostrom.server.memory.domain.ProposalEdits
import run.ostrom.server.memory.domain.ProposalSession
import run.ostrom.server.memory.domain.ProposalWait
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The waits of the proposals: `s1` is every caller's session, any other is nobody's; [failOpen] makes asking fail.
 * The states are set by the test.
 */
class FakeProposalWaits : MemoryProposalWaits {
    val opened = mutableListOf<Pair<ProposalSession, MemoryEntry>>()
    val cancelled = mutableListOf<Pair<String, Uuid>>()
    val states = mutableMapOf<Uuid, ProposalWait>()

    /** The wait each entry was proposed in. */
    val waitOf = mutableMapOf<Uuid, Uuid>()
    var failOpen = false

    /** The asking coroutine is cancelled while the wait opens. */
    var cancelOpen = false

    override suspend fun session(caller: MemoryCaller, requested: String): ProposalSession =
        if (requested.startsWith("s1")) {
            ProposalSession(requested, listOf(requested))
        } else {
            throw MemorySessionNotFoundException()
        }

    override suspend fun open(caller: MemoryCaller, session: ProposalSession, entry: MemoryEntry): Uuid {
        check(!failOpen) { "The waits are down." }
        if (cancelOpen) throw CancellationException("The call was cancelled.")
        opened += session to entry
        return Uuid.random().also {
            states[it] = ProposalWait(it, open = true)
            waitOf[entry.id] = it
        }
    }

    override suspend fun cancel(orgId: Uuid, memberId: String, waitId: Uuid) {
        cancelled += memberId to waitId
    }

    override suspend fun states(orgId: Uuid, waitIds: Collection<Uuid>): Map<Uuid, ProposalWait> =
        states.filterKeys { it in waitIds }
}

/** The proposals on [repository]'s entries, as the real store keeps them. */
class FakeProposalStore(private val repository: FakeRepository) : MemoryProposalStore {
    val waits = mutableMapOf<Uuid, Uuid>()
    val purged = mutableListOf<Instant>()
    var organizations = listOf(ORG_A)

    /** Another replica closes every proposal first. */
    var lostRace = false

    /** Binding a wait fails, as a database error would. */
    var failAttach = false

    /** An organization whose proposals cannot be read. */
    var broken: Uuid? = null

    override suspend fun attach(orgId: Uuid, id: Uuid, waitId: Uuid) {
        check(!failAttach) { "The database is down." }
        waits[id] = waitId
    }

    override suspend fun proposals(caller: MemoryCaller, limit: Int): List<MemoryEntry> =
        repository.entries.values.filter {
            it.status == MemoryStatus.PROPOSED && (caller.admin || it.authorId == caller.memberId)
        }.take(limit)

    override suspend fun decide(
        caller: MemoryCaller,
        id: Uuid,
        accept: Boolean,
        check: (MemoryEntry) -> Unit,
    ): PendingProposal {
        val entry = repository.entries[id]?.takeIf { it.scope == MemoryScope.TEAM } ?: throw MemoryNotFoundException()
        check(entry)
        if (entry.status != MemoryStatus.PROPOSED) throw MemoryNotProposedException()
        close(caller.orgId, id, accept, null)
        return PendingProposal(repository.entries.getValue(id), waits[id])
    }

    override suspend fun organizations(after: Uuid?, limit: Int): List<Uuid> =
        organizations.drop(if (after == null) 0 else organizations.indexOf(after) + 1).take(limit)

    override suspend fun pending(orgId: Uuid, after: MemoryCursor?, limit: Int): List<PendingProposal> {
        check(orgId != broken) { "This organization's proposals cannot be read." }
        // In the order they were written, which the cursor names a place in, whatever became of its entry since.
        val all = repository.entries.values.toList()
        val from = if (after == null) 0 else all.indexOfFirst { it.id == after.id } + 1
        return all.drop(from).filter { it.status == MemoryStatus.PROPOSED }.take(limit)
            .map { PendingProposal(it, waits[it.id]) }
    }

    override suspend fun close(orgId: Uuid, id: Uuid, accept: Boolean, edits: ProposalEdits?): Boolean {
        val entry = repository.entries[id]?.takeIf { it.status == MemoryStatus.PROPOSED && !lostRace } ?: return false
        val status = if (accept) MemoryStatus.ACTIVE else MemoryStatus.REJECTED
        repository.entries[id] = entry.copy(
            status = status,
            title = edits?.title ?: entry.title,
            summary = edits?.summary ?: entry.summary,
            content = edits?.content ?: entry.content,
        )
        return true
    }

    override suspend fun purgeRejected(orgId: Uuid, before: Instant) {
        purged += before
    }
}
