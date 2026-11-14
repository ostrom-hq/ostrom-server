// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.events

import kotlin.uuid.Uuid

/**
 * What a reader of the journal needs of a wait its events name (TeamCode's `WaitSummary`): `cloud.wait.created`
 * carries identifiers only, and the wait may have been answered since.
 *
 * @property id The wait's id.
 * @property assigneeId Who it asks: a member's id, or another name of the asker's.
 * @property sessionId The session it belongs to, if any.
 * @property kind Its kind (`ask_human`, `turn_failed`, ...).
 * @property resolver Who resolves it: `human`, `time` or `event`.
 * @property open Whether it is still open.
 * @property title Its question's title, as the asker wrote it.
 */
data class WaitSummary(
    val id: String,
    val assigneeId: String,
    val sessionId: String?,
    val kind: String,
    val resolver: String,
    val open: Boolean,
    val title: String,
)

/**
 * The organization's waits, read in one statement. The automations module binds it on its waits, so the notification
 * router tells a person of a wait without reading that module's tables. Unbound, no wait is known.
 */
fun interface WaitSummaries {
    /**
     * @param orgId The organization.
     * @param ids The waits' ids, as the journal names them.
     *
     * @return The waits that exist, in any order.
     */
    suspend fun of(orgId: Uuid, ids: Set<String>): List<WaitSummary>
}
