// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.sessions.domain.models.SeenItem
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionActivityState
import run.ostrom.server.sessions.domain.models.SessionQuery
import run.ostrom.server.sessions.domain.repositories.SessionsRepository
import run.ostrom.server.sessions.domain.repositories.SessionsTransaction
import run.ostrom.server.sessions.domain.repositories.StaleSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SessionsRepository] on `sessions_sessions` (table of [SessionsMigrations]). Every transaction is the serving role's
 * with `ostrom.org_id` set (`withOrganization`), which the row-level security policy reads: a query never sees or
 * writes another organization's rows, even if its `WHERE` forgot it; every statement filters on the organization too.
 *
 * @param database The serving role's pool.
 */
class PostgresSessionsRepository(private val database: JdbcDatabase) : SessionsRepository {
    override suspend fun <T> transaction(orgId: Uuid, block: SessionsTransaction.() -> T): T =
        database.withOrganization(orgId) { Tx(this, orgId).block() }

    override suspend fun get(orgId: Uuid, id: String): Session? = database.withOrganization(orgId) {
        selectOf(orgId) { s.id eq id }.singleOrNull()?.toSession()
    }

    // The primary key finds the named segment, the unique (org_id, root_id, segment) index its session's.
    override suspend fun segmentsOf(orgId: Uuid, id: String): List<Session> = database.withOrganization(orgId) {
        val root = s.select(s.rootId).where { (s.orgId eq orgId) and (s.id eq id) }
        selectOf(orgId) { s.rootId eq wrapAsExpression<String>(root) }
            .orderBy(s.segment)
            .map { it.toSession() }
    }

    override suspend fun list(orgId: Uuid, query: SessionQuery): List<Session> = database.withOrganization(orgId) {
        selectOf(orgId) { filtersOf(orgId, query) }
            .orderBy(s.startedAt to SortOrder.DESC, s.id to SortOrder.DESC)
            .limit(query.limit)
            .map { it.toSession() }
    }

    // The one read across organizations, through the function of SessionsMigrations version 2.
    override suspend fun stale(seenBefore: Instant, limit: Int): List<StaleSession> = database.query {
        val t = SessionsStale
        t.of(seenBefore, limit).select(t.orgId, t.id, t.lastSeenAt).map {
            StaleSession(it[t.orgId], it[t.id], it[t.lastSeenAt])
        }
    }

    private class Tx(private val tx: JdbcTransaction, private val orgId: Uuid) : SessionsTransaction {
        override fun lockRoot(rootId: String) {
            tx.lockKeyUntilCommit("sessions:$orgId:$rootId")
        }

        override fun segments(rootId: String): List<Session> =
            selectOf(orgId) { s.rootId eq rootId }.orderBy(s.segment).forUpdate().map { it.toSession() }

        override fun lock(id: String): Session? =
            selectOf(orgId) { s.id eq id }.forUpdate().singleOrNull()?.toSession()

        override fun insert(session: Session) {
            s.insert {
                it[s.orgId] = session.orgId
                it[s.id] = session.id
                it.values(session)
            }
        }

        override fun update(session: Session) {
            s.update({ (s.orgId eq session.orgId) and (s.id eq session.id) }) { it.values(session) }
        }
    }

    @Serializable
    private data class SeenRecord(val kind: String, val name: String, val via: String, val at: String)

    private companion object {
        val s = SessionsSessions
        val json = Json { ignoreUnknownKeys = true }
        val seenList = ListSerializer(SeenRecord.serializer())
        val labelsMap = MapSerializer(String.serializer(), String.serializer())

        val COLUMNS = listOf(
            s.orgId, s.id, s.rootId, s.segment, s.resumedFrom, s.previousReceiptId, s.launchId, s.repo, s.realRepo,
            s.cwd, s.agent, s.dev, s.harnessVersion, s.packageDigest, s.model, s.endSource, s.startedAt, s.endedAt,
            s.lastSeenAt, s.receiptId, s.tools, s.costUsd, s.exitCode, s.seen, s.activity, s.activityAt,
            s.activityObservedAt, s.labels,
        )

        // The segments of the organization that [where] keeps, every column read.
        fun selectOf(orgId: Uuid, where: () -> Op<Boolean>): Query =
            s.select(COLUMNS).where { (s.orgId eq orgId) and where() }

        fun filtersOf(orgId: Uuid, query: SessionQuery): Op<Boolean> {
            val open = query.open?.let { if (it) s.endedAt.isNull() else s.endedAt.isNotNull() }
            val labels = query.labels.takeIf { it.isNotEmpty() }
                ?.let { LabelsContain(json.encodeToString(labelsMap, it)) }
            val before = query.before?.let { id ->
                rowBefore(
                    listOf(s.startedAt, s.id),
                    s.select(s.startedAt, s.id).where { (s.orgId eq orgId) and (s.id eq id) },
                )
            }
            return Op.TRUE andIfNotNull query.repo?.let { s.repo eq it } andIfNotNull
                query.dev?.let { s.dev eq it } andIfNotNull open andIfNotNull
                query.launchId?.let { s.launchId eq it } andIfNotNull labels andIfNotNull before
        }

        // Every column but the key, written by an insert and an update alike.
        fun UpdateBuilder<*>.values(session: Session) {
            this[s.rootId] = session.rootId
            this[s.segment] = session.segment
            this[s.resumedFrom] = session.resumedFrom
            this[s.previousReceiptId] = session.previousReceiptId
            this[s.launchId] = session.launchId
            this[s.repo] = session.repo
            this[s.realRepo] = session.realRepo
            this[s.cwd] = session.cwd
            this[s.agent] = session.agent
            this[s.dev] = session.dev
            this[s.harnessVersion] = session.harnessVersion
            this[s.packageDigest] = session.packageDigest
            this[s.model] = session.model
            this[s.endSource] = session.source
            this[s.startedAt] = session.startedAt
            this[s.endedAt] = session.endedAt
            this[s.lastSeenAt] = session.lastSeenAt
            this[s.receiptId] = session.receiptId
            this[s.tools] = session.tools
            this[s.costUsd] = session.costUsd
            this[s.exitCode] = session.exitCode
            this[s.seen] = json.encodeToString(seenList, session.seen.map { it.toRecord() })
            this[s.activity] = session.activity?.wire
            this[s.activityAt] = session.activityAt
            this[s.activityObservedAt] = session.activityObservedAt
            this[s.labels] = json.encodeToString(labelsMap, session.labels)
        }

        fun SeenItem.toRecord() = SeenRecord(kind, name, via, at.toString())

        fun SeenRecord.toItem() = SeenItem(kind, name, via, Instant.parse(at))

        fun ResultRow.toSession() = Session(
            orgId = this[s.orgId],
            id = this[s.id],
            rootId = this[s.rootId],
            segment = this[s.segment],
            resumedFrom = this[s.resumedFrom],
            previousReceiptId = this[s.previousReceiptId],
            launchId = this[s.launchId],
            repo = this[s.repo],
            realRepo = this[s.realRepo],
            cwd = this[s.cwd],
            agent = this[s.agent],
            dev = this[s.dev],
            harnessVersion = this[s.harnessVersion],
            packageDigest = this[s.packageDigest],
            model = this[s.model],
            source = this[s.endSource],
            startedAt = this[s.startedAt],
            endedAt = this[s.endedAt],
            lastSeenAt = this[s.lastSeenAt],
            receiptId = this[s.receiptId],
            tools = this[s.tools],
            costUsd = this[s.costUsd],
            exitCode = this[s.exitCode],
            seen = json.decodeFromString(seenList, this[s.seen]).map { it.toItem() },
            activity = SessionActivityState.stored(this[s.activity]),
            activityAt = this[s.activityAt],
            activityObservedAt = this[s.activityObservedAt],
            labels = json.decodeFromString(labelsMap, this[s.labels]),
        )
    }
}

/** `CAST(labels AS JSONB) @> CAST(? AS JSONB)`: the segment holds every label of [labels] (a JSON object). */
private class LabelsContain(private val labels: String) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("CAST(")
        append(SessionsSessions.labels)
        append(" AS JSONB) @> CAST(")
        registerArgument(TextColumnType(), labels)
        append(" AS JSONB)")
    }
}
