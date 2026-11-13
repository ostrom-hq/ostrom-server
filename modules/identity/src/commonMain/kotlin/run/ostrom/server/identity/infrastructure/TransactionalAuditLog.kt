// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditLog
import io.github.smyrgeorge.sqlx4k.Transaction

/**
 * Records an organization's action inside the transaction that does it, so that the log never tells of an action that
 * did not happen, nor misses one that did. For another module's repository, whose transaction is opened for the
 * organization ([run.ostrom.server.kernel.database.inOrg]); the domain keeps [AuditLog.record].
 *
 * @param log Signs the entries.
 */
class TransactionalAuditLog(private val log: AuditLog) {
    /**
     * Appends [entry] to its organization's chain inside [transaction].
     *
     * @param transaction The caller's transaction, opened for the entry's organization.
     * @param entry What happened; its organization is required.
     *
     * @return The signed, chained event, stored when the transaction commits.
     */
    suspend fun record(transaction: Transaction, entry: AuditEntry): AuditEvent {
        val orgId = requireNotNull(entry.orgId) { "Only an organization's action is recorded in its transaction." }
        return transaction.appendAudit(orgId.toString(), log.sign(entry))
    }
}
