// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.workflows.SessionRunIds
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [SessionRunIds] on PostgreSQL: the run of a cloud session is the run of its latest visit (`workflow_runs_visits`, on
 * its index of sessions), and the run of a launch the run of the cloud session it ran in. One query per call, in the
 * organization's transaction (row level security); nothing asked, no query.
 *
 * @param database The serving role's pool.
 */
class PostgresSessionRunIds(private val database: JdbcDatabase) : SessionRunIds {
    override suspend fun runsOf(orgId: Uuid, sessionIds: Collection<Uuid>): Map<Uuid, String> {
        if (sessionIds.isEmpty()) return emptyMap()
        return database.withOrganization(orgId) { runsOfSessions(orgId, sessionIds).rows().toMap() }
    }

    override suspend fun runsOfLaunches(orgId: Uuid, launchIds: Collection<String>): Map<String, String> {
        if (launchIds.isEmpty()) return emptyMap()
        return database.withOrganization(orgId) { runsOfLaunchIds(orgId, launchIds).rows().toMap() }
    }
}

/** The run of each of these cloud sessions, its latest visit's: the index on the visits' sessions. */
internal fun runsOfSessions(orgId: Uuid, sessionIds: Collection<Uuid>): Read<Pair<Uuid, String>> = Read(
    S.select(S.sessionId, S.runId)
        .withDistinctOn(S.sessionId)
        .where { (S.orgId eq orgId) and (S.sessionId inList sessionIds.distinct()) }
        .orderBy(S.sessionId to SortOrder.ASC, S.seq to SortOrder.DESC),
) { it[S.sessionId] to it[S.runId] }

/**
 * The run of the cloud session each of these launches ran in: the launches by their key, then the index on the
 * visits' sessions.
 */
internal fun runsOfLaunchIds(orgId: Uuid, launchIds: Collection<String>): Read<Pair<String, String>> = Read(
    L.join(V, JoinType.INNER, additionalConstraint = { (V.orgId eq L.orgId) and (V.sessionId eq L.cloudSessionId) })
        .select(L.id, V.runId)
        .withDistinctOn(L.id)
        .where { (L.orgId eq orgId) and (L.id inList launchIds.distinct()) }
        .orderBy(L.id to SortOrder.ASC, V.seq to SortOrder.DESC),
) { it[L.id] to it[V.runId] }

/** The visits of agent nodes, read by their session: the rows a session names have one. */
private object SessionVisits : Table("workflow_runs_visits") {
    val orgId = uuid("org_id")
    val runId = text("run_id")
    val seq = integer("seq")
    val sessionId = uuid("session_id")
}

private val S = SessionVisits
private val V = WorkflowRunsVisits
private val L = RunLaunchesTable
