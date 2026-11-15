// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.events.WaitSummary
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [WaitSummaries] on `automations_waits`: the waits a page of the journal names, in one statement confined to the
 * organization. An id that is not a wait's (not a UUID) names none.
 *
 * @param database The serving role's pool.
 */
class WaitSummariesImpl(private val database: JdbcDatabase) : WaitSummaries {
    override suspend fun of(orgId: Uuid, ids: Set<String>): List<WaitSummary> {
        val waits = ids.mapNotNull(Uuid::parseOrNull)
        return database.inOrg(orgId) {
            val w = AutomationsWaits
            w.select(WAIT_COLUMNS).where { (w.orgId eq orgId) and (w.id inList waits) }.map { row ->
                val wait = row.toWait()
                WaitSummary(
                    id = wait.id.toString(),
                    assigneeId = wait.assignee,
                    sessionId = wait.sessionId,
                    kind = wait.kind,
                    resolver = wait.resolver.wire,
                    open = wait.status == WaitStatus.OPEN,
                    title = wait.question.title,
                )
            }
        }
    }
}
