// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One event of an organization's journal, as it was written.
 *
 * @property seq The organization's sequence: gap-free, allocated under the organization's cursor row lock, so commit
 *   order is sequence order and a reader never skips an event that commits late.
 * @property type The wire name of its type; a newer replica may write one this one does not know, which is passed on.
 * @property subject What it is about (`wait`, `workflow_run`...).
 * @property subjectId Which one.
 * @property memberId The member it belongs to; null for an event of the whole organization.
 * @property at When its change happened.
 * @property data The type's facts, a JSON object as text.
 */
data class JournalEvent(
    val seq: Long,
    val type: String,
    val subject: String,
    val subjectId: String,
    val memberId: Uuid?,
    val at: Instant,
    val data: String,
)

/**
 * What a reader may see: the events of [memberId], and the events of the whole organization (no member's) when
 * [wholeOrg]. Never another member's, an owner's or an administrator's feed included (TeamCode #238: the event feed
 * is its owner's, `readsTeamSessions` is false for every credential).
 *
 * @property memberId The reader.
 * @property wholeOrg An owner or an admin, or a key that reads for one.
 */
data class EventAudience(val memberId: Uuid, val wholeOrg: Boolean) {
    /**
     * Whether an event belonging to [owner] is visible.
     *
     * @param owner The event's member, null for the whole organization's.
     *
     * @return True when visible.
     */
    fun sees(owner: Uuid?): Boolean = owner == memberId || (wholeOrg && owner == null)
}

/**
 * One read of the journal: the visible events after a cursor, and how far the cursor may move (invisible events are
 * skipped by moving it past them).
 *
 * @property events The visible events, ascending.
 * @property through The sequence the next read starts after.
 * @property more True when the read stopped at its limit: read again at once.
 */
data class EventPage(val events: List<JournalEvent>, val through: Long, val more: Boolean)

/** The feed's numbers, TeamCode's (`TeamEventService`). */
object EventRules {
    /** Events read at once. */
    const val PAGE: Int = 100

    /** How often an open stream reads the journal. */
    val POLL: Duration = 1.seconds

    /** A keep-alive comment after this much silence. */
    val KEEPALIVE: Duration = 15.seconds

    /** How often an open stream checks its credential again. */
    val RECHECK: Duration = 15.seconds

    /** How long events are kept. */
    val RETENTION: Duration = 7.days

    /** The most types a stream may filter on. */
    const val MAX_TYPES: Int = 64
}
