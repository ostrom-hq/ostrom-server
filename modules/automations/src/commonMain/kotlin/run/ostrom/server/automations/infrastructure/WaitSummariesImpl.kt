// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.events.WaitSummary
import kotlin.uuid.Uuid

/**
 * [WaitSummaries] on `automations_waits`: the waits a page of the journal names, in one statement confined to the
 * organization. An id that is not a wait's (not a UUID) names none.
 *
 * @param driver The database.
 */
class WaitSummariesImpl(private val driver: Driver) : WaitSummaries {
    override suspend fun of(orgId: Uuid, ids: Set<String>): List<WaitSummary> {
        val waits = ids.mapNotNull(Uuid::parseOrNull)
        return driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT $WAIT_COLUMNS FROM automations_waits WHERE org_id = :org_id AND " +
                    "id = ANY(CAST(string_to_array(CAST(:ids AS TEXT), ',') AS UUID[]))",
            ).bind("org_id", orgId).bind("ids", waits.joinToString(",")),
        ).getOrThrow().map { row ->
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
