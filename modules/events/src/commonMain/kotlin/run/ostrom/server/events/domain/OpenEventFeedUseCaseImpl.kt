// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.domain

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * [OpenEventFeedUseCase] on the journal.
 *
 * @param repository The journal.
 * @param sleep The pause between two reads of an open stream; tests replace it.
 */
class OpenEventFeedUseCaseImpl(
    private val repository: OrgEventsRepository,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) : OpenEventFeedUseCase {
    override suspend fun invoke(
        orgId: Uuid,
        audience: EventAudience,
        after: Long?,
        types: Set<String>?,
        stillAllowed: suspend () -> Boolean,
    ): EventFeed {
        if (after != null && after < 0) throw InvalidCursorException()
        val head = repository.head(orgId)
        if (after != null && after > head) throw InvalidCursorException()
        // Events between the client's cursor and the oldest kept one were purged: say so rather than skip silently.
        // When every event of the organization was purged, the history goes on after the head.
        val gap = if (after == null || after == 0L) {
            null
        } else {
            val oldest = repository.oldest(orgId)
            when {
                oldest == null -> if (after < head) head + 1 else null
                after < oldest - 1 -> oldest
                else -> null
            }
        }
        return EventFeed(orgId, audience, repository, types, after ?: head, gap, sleep, stillAllowed)
    }
}
