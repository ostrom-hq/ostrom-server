// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * An agent's entries for the organization's memory (TeamCode #272): stored `proposed`, read by nobody, until the
 * person of the session they come from accepts them in a wait, or an administrator in the queue.
 */
interface MemoryProposalsService {
    /**
     * Proposes [entry] (validated, tags sorted) from the session the caller is in, and asks its person.
     *
     * @throws MemoryTeamForbiddenException For a key or a token, which is no agent's session.
     * @throws MemoryProposalSessionException When it names no session.
     * @throws MemoryProposalsLimitException When too many of the member's proposals wait.
     */
    suspend fun propose(caller: MemoryCaller, entry: NewMemory, sessionId: String?): MemoryEntry

    /**
     * The waiting proposals: every member's for an administrator, the caller's own otherwise; a person only.
     *
     * @throws MemoryTeamForbiddenException For a token or an agent.
     */
    suspend fun proposals(caller: MemoryCaller): List<MemoryEntry>

    /**
     * Accepts ([accept]: an administrator) or rejects (an administrator or the author) a waiting proposal; a person
     * only. Its wait is cancelled.
     *
     * @throws MemoryProposalForbiddenException For anyone else.
     * @throws MemoryNotFoundException When the organization has no such entry of its own.
     * @throws MemoryNotProposedException When it does not wait.
     */
    suspend fun decide(caller: MemoryCaller, id: Uuid, accept: Boolean): MemoryEntry

    /**
     * Applies the answers of the waits, rejects the proposals unanswered for [MemoryRules.PROPOSAL_DAYS] days and
     * deletes the entries rejected [MemoryRules.REJECTED_DAYS] days ago, every organization's.
     *
     * @return The proposals accepted or rejected.
     */
    suspend fun settle(): Int
}

/**
 * [MemoryProposalsService].
 *
 * @param repository The memory's entries.
 * @param store The proposals.
 * @param waits The waits asking the sessions' people.
 * @param audit The organization's audit log.
 * @param clock The time the proposals' age is read with.
 */
class MemoryProposalsServiceImpl(
    private val repository: MemoryRepository,
    private val store: MemoryProposalStore,
    private val waits: MemoryProposalWaits,
    private val audit: MemoryAudit,
    private val clock: Clock,
) : MemoryProposalsService {
    override suspend fun propose(caller: MemoryCaller, entry: NewMemory, sessionId: String?): MemoryEntry {
        // A key or a token is no agent's session: nobody would answer its proposal.
        if (caller.principal.kind != PrincipalKind.AGENT && !caller.person) {
            throw MemoryTeamForbiddenException(
                "A key cannot write to the team memory. Write a personal entry, or ask a member to add it.",
            )
        }
        val named = sessionId?.takeIf { it.isNotBlank() } ?: throw MemoryProposalSessionException()
        val session = waits.session(caller, named)
        val stored = repository.create(caller, entry, MemoryOrigin.AGENT, session.segment, MemoryStatus.PROPOSED)
        var asked = false
        try {
            store.attach(caller.orgId, stored.id, waits.open(caller, session, stored))
            asked = true
        } finally {
            // Nobody was asked, so nobody can ever accept it: it must not wait for the expiry.
            if (!asked) withContext(NonCancellable) { store.close(caller.orgId, stored.id, accept = false, null) }
        }
        audit.record(caller, "memory.proposed", stored.id, "scope" to stored.scope.wire, "origin" to "agent")
        return stored
    }

    override suspend fun proposals(caller: MemoryCaller): List<MemoryEntry> {
        requirePerson(caller)
        return store.proposals(caller, MemoryRules.PROPOSALS_PAGE)
    }

    override suspend fun decide(caller: MemoryCaller, id: Uuid, accept: Boolean): MemoryEntry {
        requirePerson(caller)
        val decided = store.decide(caller, id, accept) { entry ->
            // Accepting changes what the organization reads: an administrator's call. The author may withdraw theirs.
            if (!caller.admin && (accept || entry.authorId != caller.memberId)) throw MemoryProposalForbiddenException()
        }
        decided.waitId?.let { waits.cancel(caller.orgId, decided.entry.authorId, it) }
        val facts = if (accept) arrayOf("edited" to "false") else emptyArray()
        audit.record(caller, if (accept) "memory.accepted" else "memory.rejected", id, *facts)
        return decided.entry
    }

    override suspend fun settle(): Int =
        store.organizations(SETTLE_ORGANIZATIONS).sumOf { settle(it) }

    private suspend fun settle(orgId: Uuid): Int {
        val now = clock.now()
        val pending = store.pending(orgId, SETTLE_PAGE)
        val states = waits.states(orgId, pending.mapNotNull { it.waitId })
        var changed = 0
        for (proposal in pending) {
            val wait = proposal.waitId?.let(states::get)
            val fresh = proposal.entry.updatedAt >= now - MemoryRules.PROPOSAL_DAYS.days
            // A proposal whose wait is still being opened (bound just after the row) is left alone for a minute.
            val opening = proposal.waitId == null && proposal.entry.updatedAt >= now - OPENING
            if (wait?.open == true && fresh || opening) continue
            val answer = wait?.takeIf { it.action == ACCEPT }
            val accepted = answer != null
            val edits = answer?.let { edits(proposal.entry, it.values) }
            if (store.close(orgId, proposal.entry.id, accepted, edits)) {
                changed++
                val by = "by" to (wait?.author ?: SYSTEM)
                val event = if (accepted) "memory.accepted" else "memory.rejected"
                val facts = if (accepted) arrayOf(by, "edited" to "${edits != null}") else arrayOf(by)
                audit.record(orgId, MEMORY_ACTOR, event, proposal.entry.id, *facts)
            }
            if (wait?.open == true) waits.cancel(orgId, proposal.entry.authorId, wait.id)
        }
        // Rejected proposals are kept for the audit, then deleted (their tags go with them).
        store.purgeRejected(orgId, now - MemoryRules.REJECTED_DAYS.days)
        return changed
    }

    /** The person's edits, when they changed something and keep the rules; else the proposal as it was sent. */
    private fun edits(entry: MemoryEntry, values: Map<String, String>): ProposalEdits? {
        val edits = ProposalEdits(
            values["title"] ?: entry.title,
            values["summary"] ?: entry.summary,
            values["content"] ?: entry.content,
        )
        if (edits == ProposalEdits(entry.title, entry.summary, entry.content)) return null
        return edits.takeIf {
            try {
                MemoryRules.validate(it.title, it.summary, it.content, entry.tags, entry.refs, null, clock.now())
                true
            } catch (_: InvalidMemoryException) {
                false
            } catch (_: MemorySecretException) {
                false
            }
        }
    }

    private fun requirePerson(caller: MemoryCaller) {
        if (!caller.person || caller.agent) {
            throw MemoryTeamForbiddenException("Proposals are decided by a signed-in member.")
        }
    }

    private companion object {
        const val ACCEPT = "accept"
        const val SYSTEM = "ostrom"
        const val SETTLE_PAGE = 100
        const val SETTLE_ORGANIZATIONS = 1_000
        val OPENING = 1.minutes
    }
}

/** The settling pass, as it appears in the audit log: the member who answered is in its facts (`by`). */
val MEMORY_ACTOR: AuthenticatedPrincipal = AuthenticatedPrincipal("memory", PrincipalKind.SERVICE, emptySet())
