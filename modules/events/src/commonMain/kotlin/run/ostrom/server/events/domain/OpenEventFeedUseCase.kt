// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

import kotlin.uuid.Uuid

/** Opens an organization's event feed for one reader. */
interface OpenEventFeedUseCase {
    /**
     * Opens the feed after [after] (the reconnecting client's `Last-Event-ID`): null starts at the newest event, so a
     * first connection sees only what happens next; 0 starts at the oldest kept one.
     *
     * @param orgId The organization.
     * @param audience Who reads.
     * @param after The cursor, or null.
     * @param types The wire names to keep, or null for every type.
     * @param stillAllowed Whether the reader's credential still holds, asked again while the stream is open.
     *
     * @return The feed.
     *
     * @throws InvalidCursorException When [after] is negative or ahead of the organization's events.
     */
    suspend operator fun invoke(
        orgId: Uuid,
        audience: EventAudience,
        after: Long?,
        types: Set<String>?,
        stillAllowed: suspend () -> Boolean,
    ): EventFeed
}
