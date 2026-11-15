// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.upsertReturning
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
 * sequence order and the sequence has no gap. The sqlx4k writer ([publishIn] on a sqlx4k transaction, for the modules
 * not yet on Exposed) and the Exposed one ([publishIn] on a `JdbcTransaction`) send the same upsert of the same row:
 * whichever pool a publisher runs on, it waits for the others' commit, and they share one sequence.
 *
 * @param driver The database, for [publish].
 * @param clock The time of the events.
 */
class JournalOrgEventPublisher(private val driver: Driver, private val clock: Clock) : OrgEventPublisher {
    override suspend fun publish(orgId: Uuid, events: List<OrgEventDraft>) {
        if (events.isEmpty()) return
        driver.withOrganization(orgId) { tx -> append(tx, orgId, events) }
    }

    override suspend fun publishIn(transaction: Transaction, orgId: Uuid, events: List<OrgEventDraft>) {
        if (events.isEmpty()) return
        append(transaction, orgId, events)
    }

    // The Exposed DSL runs in the caller's current transaction, which is [transaction].
    override fun publishIn(transaction: JdbcTransaction, orgId: Uuid, events: List<OrgEventDraft>) {
        if (events.isEmpty()) return
        val texts = texts(events)
        val count = events.size.toLong()
        val c = EventsCursors
        // INSERT … ON CONFLICT (org_id) DO UPDATE SET last_seq = events_cursors.last_seq + EXCLUDED.last_seq
        // RETURNING last_seq: ADVANCE_SQL, the same row lock until the caller commits.
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

    private suspend fun append(tx: Transaction, orgId: Uuid, events: List<OrgEventDraft>) {
        val texts = texts(events)
        val count = events.size.toLong()
        val last = tx.fetchAll(Statement.create(ADVANCE_SQL).bind("org_id", orgId).bind("count", count))
            .getOrThrow().first().get("last_seq").asLong()
        val at = clock.now()
        events.forEachIndexed { index, event ->
            val insert = Statement.create(INSERT_SQL)
                .bind("org_id", orgId)
                .bind("seq", last - count + 1 + index)
                .bind("type", event.type.wire)
                .bind("subject", event.type.subject)
                .bind("subject_id", event.subjectId)
                .bind("at", at)
                .bind("data", texts[index])
            val member = event.memberId
            val bound =
                if (member == null) insert.bindNull("member_id", Uuid::class) else insert.bind("member_id", member)
            tx.execute(bound).getOrThrow()
        }
    }

    private companion object {
        const val ADVANCE_SQL =
            "INSERT INTO events_cursors (org_id, last_seq) VALUES (:org_id, :count) " +
                "ON CONFLICT (org_id) DO UPDATE SET last_seq = events_cursors.last_seq + EXCLUDED.last_seq " +
                "RETURNING last_seq"
        const val INSERT_SQL =
            "INSERT INTO events_journal (org_id, seq, type, subject, subject_id, member_id, at, data) " +
                "VALUES (:org_id, :seq, :type, :subject, :subject_id, :member_id, :at, :data)"
    }
}
