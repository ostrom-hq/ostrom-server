// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.EventPage
import run.ostrom.server.events.domain.JournalEvent
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.database.withOrganization
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [OrgEventsRepository] on PostgreSQL (the tables of [EventsMigrations]): every read in a transaction confined to its
 * organization, by primary-key or index ranges and bounded; the purge through `events_purge`, the one read across
 * organizations.
 *
 * @param driver The database.
 */
class OrgEventsRepositoryImpl(private val driver: Driver) : OrgEventsRepository {
    override suspend fun head(orgId: Uuid): Long = driver.withOrganization(orgId) { tx -> tx.headOf(orgId) }

    override suspend fun oldest(orgId: Uuid): Long? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(OLDEST_SQL).bind("org_id", orgId)).getOrThrow().first().get("seq").asLongOrNull()
    }

    override suspend fun read(
        orgId: Uuid,
        audience: EventAudience,
        after: Long,
        types: Set<String>?,
        limit: Int,
    ): EventPage = driver.withOrganization(orgId) { tx ->
        // The head first: everything up to it is committed (a publisher holds the cursor row until it commits), and
        // nothing above it is read, so `through` is exact.
        val head = tx.headOf(orgId)
        if (head <= after) return@withOrganization EventPage(emptyList(), after, false)
        val rows = tx.fetchAll(
            Statement.create(READ_SQL)
                .bind("org_id", orgId)
                .bind("after", after)
                .bind("head", head)
                .bind("all_types", types == null)
                .bind("types", types.orEmpty().sorted().joinToString(","))
                .bind("whole_org", audience.wholeOrg)
                .bind("member_id", audience.memberId)
                .bind("limit", limit + 1),
        ).getOrThrow().map { it.toEvent() }
        val page = rows.take(limit)
        if (rows.size > limit) EventPage(page, page.last().seq, true) else EventPage(page, head, false)
    }

    override suspend fun purge(before: Instant, limit: Int): Int = driver.fetchAll(
        Statement.create("SELECT events_purge(CAST(:before AS TIMESTAMPTZ), CAST(:limit AS INTEGER)) AS n")
            .bind("before", before)
            .bind("limit", limit),
    ).getOrThrow().first().get("n").asLong().toInt()

    override suspend fun erase(orgId: Uuid) {
        driver.withOrganization(orgId) { tx ->
            tx.execute(Statement.create(ERASE_JOURNAL_SQL).bind("org_id", orgId)).getOrThrow()
            tx.execute(Statement.create(ERASE_CURSOR_SQL).bind("org_id", orgId)).getOrThrow()
        }
    }

    private companion object {
        const val HEAD_SQL = "SELECT COALESCE(max(last_seq), 0) AS last_seq FROM events_cursors WHERE org_id = :org_id"
        const val OLDEST_SQL = "SELECT min(seq) AS seq FROM events_journal WHERE org_id = :org_id"
        const val READ_SQL =
            "SELECT seq, type, subject, subject_id, member_id, at, data FROM events_journal " +
                "WHERE org_id = :org_id AND seq > :after AND seq <= :head " +
                "AND (CAST(:all_types AS BOOLEAN) OR type = ANY(string_to_array(CAST(:types AS TEXT), ','))) " +
                "AND (member_id = :member_id OR (CAST(:whole_org AS BOOLEAN) AND member_id IS NULL)) " +
                "ORDER BY seq LIMIT :limit"
        const val ERASE_JOURNAL_SQL = "DELETE FROM events_journal WHERE org_id = :org_id"
        const val ERASE_CURSOR_SQL = "DELETE FROM events_cursors WHERE org_id = :org_id"

        suspend fun QueryExecutor.headOf(orgId: Uuid): Long =
            fetchAll(Statement.create(HEAD_SQL).bind("org_id", orgId)).getOrThrow().first().get("last_seq").asLong()

        fun ResultSet.Row.toEvent() = JournalEvent(
            seq = get("seq").asLong(),
            type = get("type").asString(),
            subject = get("subject").asString(),
            subjectId = get("subject_id").asString(),
            memberId = get("member_id").asUuidOrNull(),
            at = get("at").asInstant(),
            data = get("data").asString(),
        )
    }
}
