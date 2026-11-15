// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // The port's writes and reads, and the builders their plans explain.

package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.database.write
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.workflowruns.domain.repositories.RunAudience
import run.ostrom.server.workflowruns.domain.repositories.RunQuery
import run.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.unionAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WorkflowRunsRepository] on PostgreSQL (the tables of [WorkflowRunsMigrations]), every query in a transaction scoped
 * to one organization by `ostrom.org_id`, which row level security enforces.
 *
 * @param database The serving role's pool.
 */
class PostgresWorkflowRunsRepository(private val database: JdbcDatabase) : WorkflowRunsRepository {
    override suspend fun addCheck(orgId: Uuid, run: CheckRun, attestation: CheckAttestation) {
        database.withOrganization(orgId) { write { insertCheck(orgId, run, attestation) } }
    }

    override suspend fun attestations(orgId: Uuid, sessionId: String): Map<String, CheckAttestation> =
        database.withOrganization(orgId) { attestedChecks(orgId, sessionId).rows().toMap() }

    override suspend fun checks(orgId: Uuid, query: RunQuery): List<CheckRun> = database.withOrganization(orgId) {
        checksPage(orgId, query).rows().map { OstromJson.decodeFromString(CheckRun.serializer(), it) }
    }

    override suspend fun addNotification(orgId: Uuid, notification: Notification, actionKey: String): Notification =
        database.withOrganization(orgId) {
            // One notification per action of a session, a not sent one aside (the partial unique index
            // `workflow_runs_notifications_once`): Exposed's `ON CONFLICT` cannot name its predicate, so the action's
            // lock serializes the look-up and the insert. Two passes of a hook at once: the first one stored stands.
            val session = notification.sessionId?.takeIf { notification.status.value != NOT_SENT }
            if (session != null) {
                lockUntilCommit("workflow-runs:notification:$orgId:$session:${notification.notifyId}:$actionKey")
            }
            val first = session
                ?.let { firstNotification(orgId, it, notification.notifyId, actionKey).firstOrNull() }
            if (first == null) {
                write { insertNotification(orgId, notification, actionKey) }
                notification
            } else {
                decodeNotification(first)
            }
        }

    override suspend fun updateNotification(orgId: Uuid, notification: Notification) {
        database.withOrganization(orgId) {
            N.update({ (N.orgId eq orgId) and (N.id eq notification.id) }) {
                it[status] = notification.status.value
                it[body] = jsonbOf(OstromJson.encodeToString(Notification.serializer(), notification))
            }
        }
    }

    override suspend fun notifications(orgId: Uuid, query: RunQuery): List<Notification> =
        database.withOrganization(orgId) { notificationsPage(orgId, query).rows().map { decodeNotification(it) } }

    override suspend fun hasCheck(orgId: Uuid, id: String, audience: RunAudience?): Boolean =
        database.withOrganization(orgId) { holds(C, orgId, id, audience).any() }

    override suspend fun hasNotification(orgId: Uuid, id: String, audience: RunAudience?): Boolean =
        database.withOrganization(orgId) { holds(N, orgId, id, audience).any() }
}

private val C = WorkflowRunsChecks
private val N = WorkflowRunsNotifications
private val NOT_SENT = NotificationStatus.NOT_SENT.value

private fun decodeNotification(body: String) = OstromJson.decodeFromString(Notification.serializer(), body)

/** Stores a check run with where it comes from. */
internal fun StatementBuilder.insertCheck(orgId: Uuid, run: CheckRun, attestation: CheckAttestation) = C.insert {
    it[C.orgId] = orgId
    it[id] = run.id
    it[at] = Instant.parse(run.at)
    it[sessionId] = run.sessionId
    it[nodeId] = run.nodeId
    it[C.attestation] = attestation.value
    it[body] = jsonbOf(OstromJson.encodeToString(CheckRun.serializer(), run))
}

/** Stores a notification. */
internal fun StatementBuilder.insertNotification(orgId: Uuid, notification: Notification, actionKey: String) =
    N.insert {
        it[N.orgId] = orgId
        it[id] = notification.id
        it[at] = Instant.parse(notification.at)
        it[sessionId] = notification.sessionId
        it[notifyId] = notification.notifyId
        it[status] = notification.status.value
        it[N.actionKey] = actionKey
        it[body] = jsonbOf(OstromJson.encodeToString(Notification.serializer(), notification))
    }

/** The runs of a session the server holds a proof of (the receipt's attestations), by run id. */
internal fun attestedChecks(orgId: Uuid, sessionId: String): Read<Pair<String, CheckAttestation>> = Read(
    C.select(C.id, C.attestation)
        .where { (C.orgId eq orgId) and (C.sessionId eq sessionId) and (C.attestation neq WORKSTATION_DECLARED) },
) { row ->
    val value = row[C.attestation]
    row[C.id] to CheckAttestation.entries.first { it.value == value }
}

/** The notification an action of a session had first: one not sent does not count. */
internal fun firstNotification(orgId: Uuid, sessionId: String, notifyId: String, actionKey: String): Read<String> =
    Read(
        N.select(N.body).where {
            (N.orgId eq orgId) and (N.sessionId eq sessionId) and (N.notifyId eq notifyId) and
                (N.actionKey eq actionKey) and (N.status neq NOT_SENT)
        },
    ) { it[N.body] }

/**
 * A page of check runs (their JSON), newest first.
 *
 * @param orgId The organization.
 * @param query Which filters apply.
 *
 * @return The read.
 */
internal fun checksPage(orgId: Uuid, query: RunQuery): Read<String> = page(
    C,
    orgId,
    query,
    listOfNotNull(query.sessionId?.let { C.sessionId eq it }, query.nodeId?.let { C.nodeId eq it }),
)

/**
 * A page of notifications (their JSON), newest first.
 *
 * @param orgId The organization.
 * @param query Which filters apply.
 *
 * @return The read.
 */
internal fun notificationsPage(orgId: Uuid, query: RunQuery): Read<String> = page(
    N,
    orgId,
    query,
    listOfNotNull(query.sessionId?.let { N.sessionId eq it }, query.status?.let { N.status eq it.value }),
)

/**
 * One row by its key (the table's primary key starts with the organization), among those [audience] reads when set.
 *
 * @param table The checks or the notifications.
 * @param orgId The organization.
 * @param id The row.
 * @param audience Who reads it, or null.
 *
 * @return The read.
 */
internal fun holds(table: RunRows, orgId: Uuid, id: String, audience: RunAudience?): Query =
    table.select(table.id).where {
        val own = (table.orgId eq orgId) and (table.id eq id)
        if (audience == null) own else own and readBy(table, audience)
    }.limit(1)

// Who reads a row (TeamCode #238): its person, and the approvers of its session's gates.
private fun readBy(table: RunRows, audience: RunAudience): Op<Boolean> {
    val approved = table.sessionId eq anyFrom(audience.sessions, TextColumnType())
    val dev = audience.dev
    return if (dev == null) approved else (table.dev eq dev) or approved
}

// A member's page is the newest of two, each through its own index: their own (`dev`), and those of the sessions
// whose gates they approve, without their own so that none is read twice.
private fun page(table: RunRows, orgId: Uuid, query: RunQuery, filters: List<Op<Boolean>>): Read<String> {
    val where = buildList {
        add(table.orgId eq orgId)
        addAll(filters)
        query.before?.let { before ->
            val cursor = table.select(table.at, table.id).where { (table.orgId eq orgId) and (table.id eq before) }
            add(rowBefore(listOf(table.at, table.id), cursor))
        }
    }
    val audience = query.audience
        ?: return Read(branch(table, where, query.limit, listOf(table.body))) { it[table.body] }
    val dev = audience.dev
    val approved = table.sessionId eq anyFrom(audience.sessions, TextColumnType())
    val branches = listOfNotNull(
        dev?.let { table.dev eq it },
        if (dev != null) approved and (table.dev.isNull() or (table.dev neq dev)) else approved,
    ).map { branch(table, where + it, query.limit, listOf(table.at, table.id, table.body)) }
    val union = branches.drop(1).fold<Query, AbstractQuery<*>>(branches.first()) { all, next -> all.unionAll(next) }
    val readable = union.alias("readable")
    val newest = readable.select(readable[table.body])
        .orderBy(readable[table.at] to SortOrder.DESC, readable[table.id] to SortOrder.DESC)
        .limit(query.limit)
    return Read(newest) { it[readable[table.body]] }
}

private fun branch(table: RunRows, where: List<Op<Boolean>>, limit: Int, columns: List<Expression<*>>): Query =
    table.select(columns).where { where.reduce { all, next -> all and next } }
        .orderBy(table.at to SortOrder.DESC, table.id to SortOrder.DESC)
        .limit(limit)

private const val WORKSTATION_DECLARED = "workstation_declared"
