// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.infrastructure.TransactionalAuditLog
import dev.ostrom.server.workflowruns.domain.models.RunAudit
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.uuid.Uuid

/**
 * Appends a run's audit line (`workflow_run.<event>`, target `workflow_run` and the run's id) to its organization's
 * signed chain, inside the transaction that changes the run: the log never tells of a change that was not made.
 *
 * @param tx The transaction, opened for the organization.
 * @param orgId The organization.
 * @param runId The run.
 * @param line The line.
 */
internal suspend fun TransactionalAuditLog.recordRun(tx: Transaction, orgId: Uuid, runId: String, line: RunAudit) {
    record(tx, AuditEntry(orgId, line.actor, "workflow_run.${line.event}", "workflow_run", runId, line.details))
}
