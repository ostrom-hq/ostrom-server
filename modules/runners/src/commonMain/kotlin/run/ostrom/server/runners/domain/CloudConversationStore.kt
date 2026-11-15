// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Transcript, inputs and subagents of conversation sessions (TeamCode's `CloudConversationStore`). Every write that
 * orders events or counts inputs locks the session row, so replicas agree on sequence numbers and on the pending-input
 * count. Every read and write is confined to the session's organization.
 */
@Suppress("TooManyFunctions") // TeamCode's one port of the conversation's tables.
interface CloudConversationStore {
    /**
     * Ascending page: events after [after] (forward), or the newest ones before [before] (backward). With [feed]
     * (`main`, or a subagent's id) only that feed's events, and the cursors and `firstSeq` follow that feed.
     *
     * @return The page.
     */
    @Suppress("LongParameterList") // The page's coordinates, as TeamCode's.
    suspend fun events(
        orgId: Uuid,
        sessionId: Uuid,
        after: Long?,
        before: Long?,
        limit: Int,
        feed: String?,
    ): CloudEventPage

    /** @return The session's subagents, oldest first, the newest [limit] of them. */
    suspend fun subagents(orgId: Uuid, sessionId: Uuid, limit: Int): List<CloudSubagent>

    /** @return The session's newest event sequence; 0 before any. */
    suspend fun lastSeq(orgId: Uuid, sessionId: Uuid): Long

    /**
     * Inserts an input under the session row lock: refuses a session that is ending, a stale view, more than
     * [pendingLimit] queued or delivered inputs, and replays an existing idempotency key (or refuses a different
     * request with the same key). A permission answer's [NewInput.permissionCheck] runs after the replay, under the
     * same lock, with the request as the transcript has it then. A message that asks to wake a sleeping session is
     * marked to wake it until [clearWake].
     *
     * @return What it did.
     * @throws CloudConversationException The refusal.
     * @throws CloudSessionNotFoundException When the session is gone.
     */
    suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome

    /** @return The input [id] of the session, or null. */
    suspend fun input(orgId: Uuid, sessionId: Uuid, id: Uuid): CloudInput?

    /**
     * @return The session's inputs, newest first, before [beforeId].
     * @throws CloudConversationException `invalid_cursor` when [beforeId] is not one of the session's inputs.
     */
    suspend fun inputs(orgId: Uuid, sessionId: Uuid, beforeId: Uuid?, limit: Int): CloudInputPage

    /** Marks the session's pending wake requests handled. */
    suspend fun clearWake(orgId: Uuid, sessionId: Uuid)

    /** @return Sessions of every organization holding a queued message still waiting to wake them. */
    suspend fun pendingWakes(limit: Int): List<PendingWake>

    /**
     * Cancels the queued and delivered inputs of sessions that ended (bounded), each with an `input` event.
     *
     * @return How many changed.
     */
    suspend fun cancelEnded(limit: Int, now: Instant): Int

    /**
     * Deletes the events, and the finished inputs, of every organization older than [before] (bounded).
     *
     * @return The rows deleted.
     */
    suspend fun purge(before: Instant, limit: Int): Int

    /**
     * Records or removes the owner's standing approval, unless the session ended.
     *
     * @return False when the session ended meanwhile.
     */
    suspend fun setPermissionBypass(orgId: Uuid, sessionId: Uuid, bypass: PermissionBypass?, now: Instant): Boolean

    /**
     * Records the agent's model, or its effort when [effort] is true, unless the session ended.
     *
     * @return False when the session ended meanwhile.
     */
    suspend fun setAgentOption(orgId: Uuid, sessionId: Uuid, effort: Boolean, value: String, now: Instant): Boolean
}

/**
 * A session holding a queued message that asked to wake it and did not (its wake was refused, or lost a race).
 *
 * @property inputId The oldest such message, named in the audit log of the wake.
 */
data class PendingWake(val orgId: Uuid, val sessionId: Uuid, val inputId: Uuid)
