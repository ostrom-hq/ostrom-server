// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.workflowruns.domain.models.RunAudit
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.uuid.Uuid

/**
 * Appends a run's audit lines (`workflow_run.<event>`, target `workflow_run` and the run's id) to its organization's
 * signed chain, inside the transaction that changes the run: the log never tells of a change that was not made.
 *
 * A transaction block is not `suspend` and signing is, so the lines are signed before the transaction, on the chain's
 * head read in a first one, and appended in the second under the chain's lock, only if the head is still the one they
 * were signed on. Another writer's append in between makes [block]'s `record` return false: the transaction is rolled
 * back, and the lines are signed again on the new head.
 *
 * @param audit The organization's signed audit log.
 * @param orgId The organization, whose transaction runs [block] as the serving role.
 * @param runId The run the lines are about.
 * @param lines The lines; none leaves the chain alone.
 * @param block The change of the run. It calls `record` once, as its last statement, when it changed the run, and
 *   returns right after a false.
 *
 * @return What [block] returned in the transaction that committed.
 */
internal suspend fun <T> JdbcDatabase.withOrganizationRecording(
    audit: TransactionalAuditLog,
    orgId: Uuid,
    runId: String,
    lines: List<RunAudit>,
    block: JdbcTransaction.(record: () -> Boolean) -> T,
): T {
    if (lines.isEmpty()) return withOrganization(orgId) { block { true } }
    repeat(AUDIT_ATTEMPTS) {
        val head = withOrganization(orgId) { audit.head(this, orgId) }
        val events = audit.sign(head, lines.map { it.entry(orgId, runId) })
        var chained = true
        val result = withOrganization(orgId) {
            block {
                audit.append(this, orgId, head, events).also { appended ->
                    if (!appended) {
                        chained = false
                        rollback()
                    }
                }
            }
        }
        if (chained) return result
    }
    error("The organization's audit chain kept moving while the run's lines were signed.")
}

private fun RunAudit.entry(orgId: Uuid, runId: String) =
    AuditEntry(orgId, actor, "workflow_run.$event", "workflow_run", runId, details)

// The signatures of one change on a head another write moved meanwhile, before the change fails loudly.
private const val AUDIT_ATTEMPTS = 8
