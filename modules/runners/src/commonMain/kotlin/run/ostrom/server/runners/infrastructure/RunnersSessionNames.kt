// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import run.ostrom.server.kernel.events.SessionNames
import kotlin.uuid.Uuid

/**
 * [SessionNames] on `runners_sessions`: the cloud sessions a page of the journal names, in one statement confined to
 * the organization. An id that is not a cloud session's (not a UUID, or an agent session's) names none.
 *
 * @param driver The database.
 */
internal class RunnersSessionNames(private val driver: Driver) : SessionNames {
    override suspend fun of(orgId: Uuid, ids: Set<String>): Map<String, String> {
        val sessions = ids.mapNotNull(Uuid::parseOrNull)
        if (sessions.isEmpty()) return emptyMap()
        return driver.inTenant(orgId) {
            fetchAll(
                Statement.create(
                    "SELECT id, name FROM runners_sessions WHERE org_id = :org_id AND " +
                        "id = ANY(CAST(string_to_array(CAST(:ids AS TEXT), ',') AS UUID[]))",
                ).bind("org_id", orgId).bind("ids", sessions.joinToString(",")),
            ).getOrThrow().associate { row -> row.get("id").asUuid().toString() to row.get("name").asString() }
        }
    }
}
