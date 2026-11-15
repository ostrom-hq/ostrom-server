// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.LiveCloudObjectsRepository
import run.ostrom.server.runners.domain.LiveObjects
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.SessionState
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid

/**
 * [LiveCloudObjectsRepository] on PostgreSQL, through `runners_cluster_live_objects` (owned by `ostrom_router`,
 * migration 18): every organization's sessions on the cluster that are not over or whose workspace deletion is not
 * proven, with only what names their objects.
 *
 * @param driver The database.
 */
class LiveCloudObjectsRepositoryImpl(private val driver: Driver) : LiveCloudObjectsRepository {
    override suspend fun namespaces(clusterId: String): Set<String> =
        driver.fetchAll(
            Statement.create(
                "SELECT DISTINCT CAST(profile AS JSONB)->>'namespace' AS namespace " +
                    "FROM runners_cluster_live_objects(CAST(:cluster AS TEXT))",
            ).bind("cluster", clusterId),
        ).getOrThrow().map { it.get("namespace").asString() }.toSet()

    override suspend fun liveObjects(clusterId: String): LiveObjects {
        val rows = driver.fetchAll(
            Statement.create(
                "SELECT session_id, epoch, state, claim_deleted " +
                    "FROM runners_cluster_live_objects(CAST(:cluster AS TEXT))",
            ).bind("cluster", clusterId),
        ).getOrThrow()
        val claims = mutableSetOf<String>()
        val pods = mutableSetOf<String>()
        for (row in rows) {
            val id = row.get("session_id").asUuid()
            val state = SessionState.fromWire(row.get("state").asString())
            if (!row.get("claim_deleted").asBoolean()) claims += RuntimeNames.workspace(id)
            if (state.holdsExecution) pods += RuntimeNames.execution(id, row.get("epoch").asLong())
        }
        return LiveObjects(claims, pods)
    }
}
