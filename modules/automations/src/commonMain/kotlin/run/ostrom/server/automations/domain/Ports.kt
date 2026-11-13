// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Which waits a listing returns, always within one organization.
 *
 * @property orgId The organization.
 * @property assignee Only those asked of this member, if set.
 * @property involving Only those asked of or by this member, if set.
 * @property createdBy Only those asked by this member, if set.
 * @property status Only those in this state, if set.
 * @property sessionId Only those of this session, if set.
 * @property runId Only those of this run, if set.
 * @property sessionIds Only those of one of these sessions, if set.
 * @property resolver Only those this resolves, if set.
 */
data class WaitFilter(
    val orgId: Uuid,
    val assignee: String? = null,
    val involving: String? = null,
    val createdBy: String? = null,
    val status: WaitStatus? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val sessionIds: List<String>? = null,
    val resolver: WaitResolver? = null,
)

/**
 * A wait for a time or an event whose instant has come, in any organization.
 *
 * @property orgId Its organization.
 * @property id Its id.
 * @property resolver [WaitResolver.TIME] (its time came) or [WaitResolver.EVENT] (it timed out).
 */
data class DueWait(val orgId: Uuid, val id: Uuid, val resolver: WaitResolver)

/**
 * An open wait for an event, found by its id alone (its event address names no organization).
 *
 * @property orgId Its organization.
 * @property secretHash The SHA-256 of its secret, hexadecimal.
 */
data class WaitEventTarget(val orgId: Uuid, val secretHash: String)

/** Where waits are stored. */
interface WaitsRepository {
    /**
     * Stores a new wait, unless [maxOpen] waits of the same scope (its session, or its asker's waits without a
     * session) are open already.
     *
     * @param sessions The sessions whose open waits count with the wait's own session: an agent's are the segments its
     *   launch opened, so that ending and reopening its session does not open [maxOpen] more.
     * @return How it went; nothing is stored unless [CreateOutcome.CREATED].
     */
    suspend fun createWithinLimit(wait: Wait, maxOpen: Int, sessions: List<String> = emptyList()): CreateOutcome

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

    /**
     * Cancels every open wait of these sessions, at once.
     *
     * @param orgId The organization.
     * @param sessions The segments of a session.
     * @param now When.
     *
     * @return The waits it cancelled; none that was already answered or cancelled.
     */
    suspend fun cancelOpenOfSessions(orgId: Uuid, sessions: List<String>, now: Instant): List<Wait>

    /**
     * Takes the answered waits of these sessions not handed to their agent yet: each is taken once.
     *
     * @param orgId The organization.
     * @param sessions The segments of a session.
     * @param limit At most this many.
     *
     * @return The waits taken, oldest answer first.
     */
    suspend fun takeAnswered(orgId: Uuid, sessions: List<String>, limit: Int): List<Wait>

    /**
     * Whether a wait of these sessions is open.
     *
     * @param orgId The organization.
     * @param sessions The segments of a session.
     *
     * @return True when one is.
     */
    suspend fun hasOpen(orgId: Uuid, sessions: List<String>): Boolean
}

/**
 * Where the waits that resolve by themselves are found and resolved: across organizations by their instant or their
 * id alone (the clock, an event address), then in their own organization.
 */
interface TimedWaitsRepository {
    /**
     * The open waits for a time or an event whose instant ([WaitTimer.at]) has come, of every organization, the
     * earliest first.
     *
     * @param now The time.
     * @param limit At most this many.
     *
     * @return The waits; another replica may resolve them first.
     */
    suspend fun due(now: Instant, limit: Int): List<DueWait>

    /**
     * The open wait for an event [id] names, of any organization.
     *
     * @return It, or null when there is none open.
     */
    suspend fun eventTarget(id: Uuid): WaitEventTarget?

    /**
     * Resolves an open wait for a time or an event with the server's own answer, and marks it for its session's wake.
     *
     * @param orgId The organization.
     * @param id The wait.
     * @param answer The answer: `time_reached`, `event_received` or `timed_out`.
     * @param due Only once its instant has come (at [answer]'s time): the clock's resolution; otherwise only a wait
     *   for an event, which its event resolves.
     *
     * @return The resolved wait, or null when it is not open any more (or not due, or not an event's).
     */
    suspend fun settle(orgId: Uuid, id: Uuid, answer: WaitAnswer, due: Boolean): Wait?
}

/** The secrets of the event addresses: 256 random bits each, kept as their SHA-256 only. */
interface WaitEventSecrets {
    /** A new secret, `wev_` and 64 hexadecimal digits. */
    fun mint(): String

    /** The SHA-256 of [secret], hexadecimal. */
    suspend fun hash(secret: String): String
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
