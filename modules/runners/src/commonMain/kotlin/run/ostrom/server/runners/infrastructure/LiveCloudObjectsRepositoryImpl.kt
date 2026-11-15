// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.runners.domain.LiveCloudObjectsRepository
import run.ostrom.server.runners.domain.LiveObjects
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.SessionState
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.select

/**
 * [LiveCloudObjectsRepository] on PostgreSQL, through `runners_cluster_live_objects` (owned by `ostrom_router`,
 * migration 18): every organization's sessions on the cluster that are not over or whose workspace deletion is not
 * proven, with only what names their objects.
 *
 * @param database The JDBC pool.
 */
class LiveCloudObjectsRepositoryImpl(private val database: JdbcDatabase) : LiveCloudObjectsRepository {
    override suspend fun namespaces(clusterId: String): Set<String> = database.query {
        val live = RunnersClusterLiveObjects.of(clusterId)
        val namespace = ProfileNamespace(RunnersClusterLiveObjects.profile)
        live.select(namespace).withDistinct().mapNotNull { it[namespace] }.toSet()
    }

    override suspend fun liveObjects(clusterId: String): LiveObjects {
        val rows = database.query {
            val l = RunnersClusterLiveObjects
            l.of(clusterId).select(l.sessionId, l.epoch, l.state, l.claimDeleted).toList()
        }
        val l = RunnersClusterLiveObjects
        val claims = mutableSetOf<String>()
        val pods = mutableSetOf<String>()
        for (row in rows) {
            val id = row[l.sessionId]
            val state = SessionState.fromWire(row[l.state])
            if (!row[l.claimDeleted]) claims += RuntimeNames.workspace(id)
            if (state.holdsExecution) pods += RuntimeNames.execution(id, row[l.epoch])
        }
        return LiveObjects(claims, pods)
    }
}

/** `CAST(profile AS JSONB)->>'namespace'`: the namespace a session's profile names, null without one. */
private class ProfileNamespace(private val profile: Expression<*>) : ExpressionWithColumnType<String?>() {
    override val columnType = TextColumnType().apply { nullable = true }

    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("CAST(")
        append(profile)
        append(" AS JSONB)->>'namespace'")
    }
}
