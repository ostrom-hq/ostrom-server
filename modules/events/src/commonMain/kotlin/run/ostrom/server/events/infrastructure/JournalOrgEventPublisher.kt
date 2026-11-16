// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.events.MAX_EVENT_DATA
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [OrgEventPublisher] on the journal of [EventsMigrations]. The organization's cursor moves once by the number of
 * events, in one statement that takes its row lock and returns the last sequence (a read then an insert would let two
 * first publishers of an organization deadlock); the lock is held until the caller commits, so commit order is
 * sequence order and the sequence has no gap: every publisher of an organization waits for the others' commit, and
 * they share one sequence.
 *
 * [publish] runs the Exposed writer in its own transaction, the serving role with the organization set.
 *
 * @param database The serving role's pool, for [publish].
 * @param clock The time of the events.
 */
class JournalOrgEventPublisher(private val database: JdbcDatabase, private val clock: Clock) : OrgEventPublisher {
    override suspend fun publish(orgId: Uuid, events: List<OrgEventDraft>) {
        if (events.isEmpty()) return
        database.withOrganization(orgId) { publishIn(this, orgId, events) }
    }

    // The Exposed DSL runs in the caller's current transaction, which is [transaction].
    override fun publishIn(transaction: JdbcTransaction, orgId: Uuid, events: List<OrgEventDraft>) {
        if (events.isEmpty()) return
        val texts = texts(events)
        val count = events.size.toLong()
        val c = EventsCursors
        // INSERT … ON CONFLICT (org_id) DO UPDATE SET last_seq = events_cursors.last_seq + EXCLUDED.last_seq
        // RETURNING last_seq: the cursor's row lock, held until the caller commits.
        val last = c.upsertReturning(
            c.orgId,
            returning = listOf(c.lastSeq),
            onUpdate = { it[c.lastSeq] = c.lastSeq + insertValue(c.lastSeq) },
        ) {
            it[c.orgId] = orgId
            it[c.lastSeq] = count
        }.single()[c.lastSeq]
        val at = clock.now()
        val j = EventsJournal
        // One statement for every event (multi-row VALUES), each at its place after the cursor's previous value.
        j.batchInsert(events.withIndex(), useMultiRowValues = true, shouldReturnGeneratedValues = false) { (i, event) ->
            this[j.orgId] = orgId
            this[j.seq] = last - count + 1 + i
            this[j.type] = event.type.wire
            this[j.subject] = event.type.subject
            this[j.subjectId] = event.subjectId
            this[j.memberId] = event.memberId
            this[j.at] = at
            this[j.data] = texts[i]
        }
    }

    private fun texts(events: List<OrgEventDraft>): List<String> = events.map { event ->
        event.data.toString().also {
            require(it.length <= MAX_EVENT_DATA) { "An event's data is at most $MAX_EVENT_DATA characters." }
        }
    }
}
