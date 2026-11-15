// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.infrastructure

import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.EventPage
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [OrgEventsRepository] on PostgreSQL (the tables of [EventsMigrations]): every read in a transaction confined to its
 * organization (the serving role with the organization set, `withOrganization`), by primary-key or index ranges and
 * bounded; the purge through `events_purge`, the one read across organizations.
 *
 * @param database The serving role's pool.
 */
class OrgEventsRepositoryImpl(private val database: JdbcDatabase) : OrgEventsRepository {
    override suspend fun head(orgId: Uuid): Long = database.withOrganization(orgId) { headOf(orgId) }

    override suspend fun oldest(orgId: Uuid): Long? = database.withOrganization(orgId) {
        val j = EventsJournal
        val oldest = j.seq.min()
        j.select(oldest).where { j.orgId eq orgId }.single()[oldest]
    }

    override suspend fun read(
        orgId: Uuid,
        audience: EventAudience,
        after: Long,
        types: Set<String>?,
        limit: Int,
    ): EventPage = database.withOrganization(orgId) {
        // The head first: everything up to it is committed (a publisher holds the cursor row until it commits), and
        // nothing above it is read, so `through` is exact.
        val head = headOf(orgId)
        if (head <= after) return@withOrganization EventPage(emptyList(), after, false)
        val j = EventsJournal
        val rows = j.select(j.seq, j.type, j.subject, j.subjectId, j.memberId, j.at, j.data)
            .where {
                val typed = if (types == null) Op.TRUE else j.type inList types.sorted()
                (j.orgId eq orgId) and (j.seq greater after) and (j.seq lessEq head) and typed and seenBy(audience)
            }
            .orderBy(j.seq to SortOrder.ASC)
            .limit(limit + 1)
            .map { it.toJournalEvent() }
        val page = rows.take(limit)
        if (rows.size > limit) EventPage(page, page.last().seq, true) else EventPage(page, head, false)
    }

    override suspend fun purge(before: Instant, limit: Int): Int = database.query {
        countOf("events_purge", instantParam(before), intParam(limit)).toInt()
    }

    override suspend fun erase(orgId: Uuid) {
        database.withOrganization(orgId) {
            EventsJournal.deleteWhere { EventsJournal.orgId eq orgId }
            EventsCursors.deleteWhere { EventsCursors.orgId eq orgId }
            EventsReaders.deleteWhere { EventsReaders.orgId eq orgId }
        }
    }

    /** The approver of an approval's event, lowercased: `lower(CAST(data AS JSONB) ->> 'approver')`. */
    private class ApproverOf(private val data: Expression<String>) : ExpressionWithColumnType<String?>() {
        override val columnType = TextColumnType().apply { nullable = true }

        override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
            append("lower(CAST(")
            append(data)
            append(" AS JSONB) ->> 'approver')")
        }
    }

    private companion object {
        /** The events an approver sees though they name no member: the approvals asked of, or decided by, them. */
        val APPROVALS = listOf("cloud.approval.requested", "cloud.approval.decided")

        /**
         * Who sees an event: the member it names; one that names none, the whole organization's audience, or the
         * approver an approval's event names. A missing member or login is bound as NULL, which equals nothing.
         */
        fun seenBy(audience: EventAudience): Op<Boolean> {
            val j = EventsJournal
            val member = QueryParameter(audience.memberId, UuidColumnType().apply { nullable = true })
            val login = QueryParameter(audience.login?.lowercase(), TextColumnType().apply { nullable = true })
            val unnamed = if (audience.wholeOrg) {
                Op.TRUE
            } else {
                (j.type inList APPROVALS) and (ApproverOf(j.data) eq login)
            }
            return (j.memberId eq member) or (j.memberId.isNull() and unnamed)
        }
    }
}
