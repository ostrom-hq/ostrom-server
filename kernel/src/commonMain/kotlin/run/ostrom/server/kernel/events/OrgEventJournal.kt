// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.events

import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where a reader of the journal stands in one organization: it has turned everything up to [from] into its own work,
 * and the journal's committed head is [head] (TeamCode's `NotificationCursor`).
 *
 * @property orgId The organization.
 * @property from The last sequence the reader handled.
 * @property head The organization's last committed sequence, above [from].
 */
data class JournalPosition(val orgId: Uuid, val from: Long, val head: Long)

/**
 * One event of the journal as a reader gets it.
 *
 * @property seq Its place in the organization's sequence.
 * @property type Its type.
 * @property subjectId What it is about (a wait's id, a run's id).
 * @property memberId The member it belongs to; null for the whole organization.
 * @property at When it was published.
 * @property data Its facts ([OrgEventDraft.data]).
 */
data class JournalEntry(
    val seq: Long,
    val type: OrgEventType,
    val subjectId: String,
    val memberId: Uuid?,
    val at: Instant,
    val data: JsonObject,
)

/**
 * The events a reader gets after its position, and how far it may move.
 *
 * @property entries The events, in sequence order.
 * @property through Where the reader stands once it handled them: the last one read when the page was full, the head
 *   otherwise (the events of other types up to it are skipped).
 */
data class JournalSlice(val entries: List<JournalEntry>, val through: Long)

/**
 * The organization's event journal for a module that turns its events into its own work after they commit, as the
 * notification router does (TeamCode's `NotificationStore.behind`, `read` and `enqueue`): each reader, by name, keeps
 * one cursor per organization in the journal's module, and moves it in the transaction that writes the work, so a page
 * is never handled twice, even by two replicas. The events module binds it; unbound, nothing is ever behind.
 */
interface OrgEventJournal {
    /**
     * The organizations whose journal is ahead of [reader]'s cursor, at most [limit]. An organization the reader has
     * never seen starts at its journal's head: what happened before the reader existed is not handed to it.
     *
     * @param reader The reader's name (`notifications`): `[a-z][a-z0-9-]*`, at most 32 characters.
     * @param limit The most organizations.
     *
     * @return Their positions.
     */
    suspend fun behind(reader: String, limit: Int): List<JournalPosition>

    /**
     * The events of [types] after [position], up to its head, at most [limit].
     *
     * @param position Where the reader stands.
     * @param types The types the reader handles.
     * @param limit The most events.
     *
     * @return The events and how far the reader may move.
     */
    suspend fun read(position: JournalPosition, types: Set<OrgEventType>, limit: Int): JournalSlice

    /**
     * Moves [reader]'s cursor from [JournalPosition.from] to [through] inside [transaction], the reader's own
     * transaction confined to the organization, where it writes the work it made of the events: both commit, or
     * neither does.
     *
     * @param transaction The reader's transaction (`withOrganization`), confined to [JournalPosition.orgId].
     * @param reader The reader's name.
     * @param position Where the reader stood when it read.
     * @param through Where it stands now.
     *
     * @return False, with nothing written, when the cursor is no longer at [JournalPosition.from] (another replica
     *   handled the page): the caller rolls its work back.
     */
    suspend fun advanceIn(transaction: Transaction, reader: String, position: JournalPosition, through: Long): Boolean
}
