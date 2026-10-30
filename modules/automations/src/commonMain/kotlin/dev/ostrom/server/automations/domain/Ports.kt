package dev.ostrom.server.automations.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Which waits a listing returns, always within one organization.
 *
 * @property orgId The organization.
 * @property assignee Only those asked of this member, if set.
 * @property involving Only those asked of or by this member, if set.
 * @property status Only those in this state, if set.
 * @property sessionId Only those of this session, if set.
 * @property runId Only those of this run, if set.
 */
data class WaitFilter(
    val orgId: Uuid,
    val assignee: String? = null,
    val involving: String? = null,
    val status: WaitStatus? = null,
    val sessionId: String? = null,
    val runId: String? = null,
)

/** Where waits are stored. */
interface WaitsRepository {
    /**
     * Stores a new wait, unless [maxOpen] waits of the same scope (its session, or its asker's waits without a
     * session) are open already.
     *
     * @return How it went; nothing is stored unless [CreateOutcome.CREATED].
     */
    suspend fun createWithinLimit(wait: Wait, maxOpen: Int): CreateOutcome

    /** The wait an asker created with an idempotency key, if any. */
    suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait?

    /** A wait of an organization. */
    suspend fun get(orgId: Uuid, id: Uuid): Wait?

    /** Waits matching [filter], newest first, created before the wait [before] when it is set. */
    suspend fun list(filter: WaitFilter, before: Uuid?, limit: Int): List<Wait>

    /**
     * Records the answer of an open wait.
     *
     * @return The resolved wait, or null when it is not open any more (another answer won).
     */
    suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer): Wait?

    /**
     * Cancels an open wait.
     *
     * @return The cancelled wait, or null when it is not open any more.
     */
    suspend fun cancel(orgId: Uuid, id: Uuid, now: Instant): Wait?
}

/** Who is an active member of an organization; bound by the module that owns the members. */
fun interface MemberDirectory {
    /** Whether [memberId] is an active member of [orgId]. */
    suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean
}

/** Told when a wait is created, answered or cancelled: notifications and the waking of sessions hook here. */
interface WaitEvents {
    /** A wait was created. */
    suspend fun created(wait: Wait)

    /** A wait was answered: its session, if any, can go on. */
    suspend fun resolved(wait: Wait)

    /** A wait was cancelled. */
    suspend fun cancelled(wait: Wait)
}
