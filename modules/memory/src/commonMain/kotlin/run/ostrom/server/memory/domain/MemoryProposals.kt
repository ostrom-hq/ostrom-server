// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The session an agent's proposal comes from: its person answers the proposal there.
 *
 * @property segment The segment the wait is asked in.
 * @property segments Every segment of the session.
 */
data class ProposalSession(val segment: String, val segments: List<String>)

/**
 * Where a proposal's wait stands.
 *
 * @property id The wait.
 * @property open Whether it waits for an answer.
 * @property action The answer's action, once answered (`accept`, `reject`).
 * @property values The answer's text fields by id (`title`, `summary`, `content`), the person's edits.
 * @property author The member who answered.
 */
data class ProposalWait(
    val id: Uuid,
    val open: Boolean,
    val action: String? = null,
    val values: Map<String, String> = emptyMap(),
    val author: String? = null,
)

/** The action of a proposal's wait that accepts it; any other answer rejects it. */
const val PROPOSAL_ACCEPT = "accept"

/**
 * A proposal waiting for its answer.
 *
 * @property entry The proposed entry (status `proposed`).
 * @property waitId Its wait, null while it is being opened.
 */
data class PendingProposal(val entry: MemoryEntry, val waitId: Uuid?)

/**
 * The person's edits of an accepted proposal, applied only if they keep the rules.
 */
data class ProposalEdits(val title: String, val summary: String, val content: String)

/**
 * Asks the person of a session whether an agent's proposal joins the organization's memory, through an ordinary wait
 * (TeamCode's `MemoryProposalWaits`).
 */
interface MemoryProposalWaits {
    /**
     * The session a proposal comes from: an agent token's own, or the session a person's relayed agent names, which
     * must be theirs.
     *
     * @param caller An agent token, or a person's relayed agent.
     * @param requested The session the request names.
     * @return The session.
     * @throws MemorySessionNotFoundException When it is not the caller's.
     */
    suspend fun session(caller: MemoryCaller, requested: String): ProposalSession

    /**
     * Opens the wait asking the session's person.
     *
     * @return The wait's id.
     */
    suspend fun open(caller: MemoryCaller, session: ProposalSession, entry: MemoryEntry): Uuid

    /** Cancels a proposal's wait still open, in the name of the member who asked it; a closed one is left. */
    suspend fun cancel(orgId: Uuid, memberId: String, waitId: Uuid)

    /** The state of these waits of the organization; an unknown one is absent. */
    suspend fun states(orgId: Uuid, waitIds: Collection<Uuid>): Map<Uuid, ProposalWait>
}

/** The proposals' storage, on the memory's tables. */
interface MemoryProposalStore {
    /** Binds a proposal to its wait. */
    suspend fun attach(orgId: Uuid, id: Uuid, waitId: Uuid)

    /** The waiting proposals, oldest first: every member's for an administrator, the caller's own otherwise. */
    suspend fun proposals(caller: MemoryCaller, limit: Int): List<MemoryEntry>

    /**
     * Accepts or rejects a waiting proposal. [check] runs first on the organization's entry, whatever its status, under
     * the row's lock: it decides whether the caller may know of it.
     *
     * @return The entry as it is now, and its wait.
     * @throws MemoryNotFoundException When the organization has no such entry of its own.
     * @throws MemoryNotProposedException When it is not a proposal waiting.
     */
    suspend fun decide(
        caller: MemoryCaller,
        id: Uuid,
        accept: Boolean,
        check: (MemoryEntry) -> Unit,
    ): PendingProposal

    /** The organizations holding proposals or rejected entries, for the settling pass, by id after [after]. */
    suspend fun organizations(after: Uuid?, limit: Int): List<Uuid>

    /** An organization's waiting proposals, oldest first, after [after]: changed later, or at once with a larger id. */
    suspend fun pending(orgId: Uuid, after: MemoryCursor?, limit: Int): List<PendingProposal>

    /**
     * Accepts (with [edits], when given) or rejects a proposal still waiting, once.
     *
     * @return Whether this call changed it: false when another replica did first.
     */
    suspend fun close(orgId: Uuid, id: Uuid, accept: Boolean, edits: ProposalEdits?): Boolean

    /** Deletes the rejected entries last changed before [before]. */
    suspend fun purgeRejected(orgId: Uuid, before: Instant)
}
