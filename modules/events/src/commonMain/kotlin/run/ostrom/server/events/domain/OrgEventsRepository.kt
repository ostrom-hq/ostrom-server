// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The journal of the organizations' feeds: one ordered, gap-free sequence per organization. */
interface OrgEventsRepository {
    /**
     * The newest sequence of an organization.
     *
     * @param orgId The organization.
     *
     * @return The sequence, 0 when it has no event yet.
     */
    suspend fun head(orgId: Uuid): Long

    /**
     * The oldest sequence still kept.
     *
     * @param orgId The organization.
     *
     * @return The sequence, null when nothing is kept.
     */
    suspend fun oldest(orgId: Uuid): Long?

    /**
     * Events with `after < seq`, ascending, visible to [audience] and, when [types] is set, of those types.
     *
     * @param orgId The organization.
     * @param audience Who reads.
     * @param after The cursor.
     * @param types The wire names to keep, or null for every type.
     * @param limit The most events.
     *
     * @return The page.
     */
    suspend fun read(orgId: Uuid, audience: EventAudience, after: Long, types: Set<String>?, limit: Int): EventPage

    /**
     * Deletes one batch of every organization's events older than [before].
     *
     * @param before The cutoff.
     * @param limit The most events deleted.
     *
     * @return How many were deleted.
     */
    suspend fun purge(before: Instant, limit: Int): Int

    /**
     * Erases an organization's journal and its cursor, once its deletion is final.
     *
     * @param orgId The organization.
     */
    suspend fun erase(orgId: Uuid)
}
