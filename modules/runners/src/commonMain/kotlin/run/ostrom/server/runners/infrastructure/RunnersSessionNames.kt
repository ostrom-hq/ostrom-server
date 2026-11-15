// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.events.SessionNames
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [SessionNames] on `runners_sessions`: the cloud sessions a page of the journal names, in one statement confined to
 * the organization. An id that is not a cloud session's (not a UUID, or an agent session's) names none.
 *
 * @param database The JDBC pool.
 */
internal class RunnersSessionNames(private val database: JdbcDatabase) : SessionNames {
    override suspend fun of(orgId: Uuid, ids: Set<String>): Map<String, String> {
        val sessions = ids.mapNotNull(Uuid::parseOrNull)
        if (sessions.isEmpty()) return emptyMap()
        return database.inOrg(orgId) {
            val s = RunnersSessionsTable
            s.select(s.id, s.name).where { (s.orgId eq orgId) and (s.id eq anyFrom(sessions, UuidColumnType())) }
                .associate { it[s.id].toString() to it[s.name] }
        }
    }
}
