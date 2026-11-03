// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.domain.repositories

import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionQuery
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An open session whose launcher stopped giving signs of life.
 *
 * @property orgId Its organization.
 * @property id The segment.
 * @property lastSeenAt Its last sign of life.
 */
data class StaleSession(val orgId: Uuid, val id: String, val lastSeenAt: Instant)

/** Stores the sessions, every read and write scoped to one organization. */
interface SessionsRepository {
    /**
     * Runs [block] in one transaction of [orgId]: nothing in it reads or writes another organization's rows.
     *
     * @param orgId The organization.
     * @param block The reads and writes.
     * @return What [block] returns.
     */
    suspend fun <T> transaction(orgId: Uuid, block: suspend SessionsTransaction.() -> T): T

    /**
     * Reads a segment.
     *
     * @param orgId The organization.
     * @param id The segment.
     * @return The segment, or null when the organization has none with this id.
     */
    suspend fun get(orgId: Uuid, id: String): Session?

    /**
     * Reads the last segment of an agent session, without locking it: the open one, when one is.
     *
     * @param orgId The organization.
     * @param rootId The agent's session id.
     * @return The segment with the highest number, or null when the organization has none of that session.
     */
    suspend fun last(orgId: Uuid, rootId: String): Session?

    /**
     * Lists segments, newest first.
     *
     * @param orgId The organization.
     * @param query The filters and the page.
     * @return The segments.
     */
    suspend fun list(orgId: Uuid, query: SessionQuery): List<Session>

    /**
     * Lists the open segments of every organization whose last sign of life is older than [seenBefore], for the sweep
     * that closes them. The only read across organizations, and a read only.
     *
     * @param seenBefore The cutoff.
     * @param limit At most this many.
     * @return The stale segments, oldest sign of life first.
     */
    suspend fun stale(seenBefore: Instant, limit: Int): List<StaleSession>
}

/** The reads and writes of one organization's transaction. Locks hold until the transaction ends. */
interface SessionsTransaction {
    /**
     * Serializes the starts and reopenings of one agent session, whichever launch they come from.
     *
     * @param rootId The agent's session id.
     */
    suspend fun lockRoot(rootId: String)

    /**
     * Lists the segments of an agent session and locks them. Call it after [lockRoot]: locks are always taken in that
     * order (the root, then its segments), so two transactions never wait on each other.
     *
     * @param rootId The agent's session id.
     * @return Its segments, by number.
     */
    suspend fun segments(rootId: String): List<Session>

    /**
     * Reads a segment and locks it.
     *
     * @param id The segment.
     * @return The segment, or null when there is none.
     */
    suspend fun lock(id: String): Session?

    /**
     * Inserts a segment.
     *
     * @param session The segment.
     */
    suspend fun insert(session: Session)

    /**
     * Writes a segment back.
     *
     * @param session The segment.
     */
    suspend fun update(session: Session)
}
