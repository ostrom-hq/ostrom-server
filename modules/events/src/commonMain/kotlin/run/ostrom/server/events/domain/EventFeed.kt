// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.domain

import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * An opened stream: authorized and with a valid cursor, so the HTTP answer can still be a JSON error before it starts.
 *
 * @property gapOldest Set when events after the client's cursor were purged: the oldest kept sequence, which the
 *   client is told of before the events that follow.
 */
class EventFeed internal constructor(
    private val orgId: Uuid,
    private val audience: EventAudience,
    private val repository: OrgEventsRepository,
    private val types: Set<String>?,
    private var cursor: Long,
    val gapOldest: Long?,
    private val sleep: suspend (Duration) -> Unit,
    private val stillAllowed: suspend () -> Boolean,
) {
    /**
     * Calls [emit] for each page of visible events in order, and [idle] about every [EventRules.KEEPALIVE] of silence.
     * Returns only when the credential stopped being valid (checked every [EventRules.RECHECK]); otherwise it ends by
     * cancellation, when the client leaves.
     *
     * @param emit Sends events.
     * @param idle Sends a keep-alive.
     */
    suspend fun follow(emit: suspend (List<JournalEvent>) -> Unit, idle: suspend () -> Unit) {
        var quiet = Duration.ZERO
        var sinceCheck = Duration.ZERO
        while (true) {
            val page = repository.read(orgId, audience, cursor, types, EventRules.PAGE)
            if (page.events.isNotEmpty()) {
                emit(page.events)
                quiet = Duration.ZERO
            }
            cursor = maxOf(cursor, page.through)
            if (page.more) continue
            if (quiet >= EventRules.KEEPALIVE) {
                idle()
                quiet = Duration.ZERO
            }
            sleep(EventRules.POLL)
            quiet += EventRules.POLL
            sinceCheck += EventRules.POLL
            if (sinceCheck >= EventRules.RECHECK) {
                if (!stillAllowed()) return
                sinceCheck = Duration.ZERO
            }
        }
    }
}
