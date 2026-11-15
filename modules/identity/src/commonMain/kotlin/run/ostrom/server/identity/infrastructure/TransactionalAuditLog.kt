// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.kernel.database.lockUntilCommit
import io.github.smyrgeorge.sqlx4k.Transaction
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.uuid.Uuid

/**
 * The last event of a chain: what the next one is chained to.
 *
 * @property sequence Its sequence.
 * @property hash Its hash, which covers its sequence.
 */
class AuditHead(val sequence: Long, val hash: String)

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

    /**
     * The head of [orgId]'s chain, read in the caller's Exposed transaction, to sign events on before it opens the
     * one that writes them ([sign], [append]): a transaction block is not `suspend`, so it never signs.
     *
     * @param transaction The caller's transaction.
     * @param orgId The organization.
     *
     * @return The head, null while the chain is empty.
     */
    fun head(transaction: JdbcTransaction, orgId: Uuid): AuditHead? =
        transaction.headOf(orgId.toString(), orgId)

    /**
     * Signs [entries] one after the other on [head], each chained to the one before.
     *
     * @param head The chain's head ([head]).
     * @param entries What happened; their organization is required.
     *
     * @return The signed events, in order, not stored yet ([append]).
     */
    suspend fun sign(head: AuditHead?, entries: List<AuditEntry>): List<AuditEvent> {
        var previous = head?.hash
        var sequence = (head?.sequence ?: 0L) + 1
        return entries.map { entry ->
            requireNotNull(entry.orgId) { "Only an organization's action is recorded in its transaction." }
            log.sign(entry)(previous, sequence).also {
                previous = it.hash
                sequence += 1
            }
        }
    }

    /**
     * Appends [events], signed on [head], to the chain of [orgId] inside [transaction], under the chain's advisory
     * lock: kept if and only if the transaction commits. Nothing is written when another writer appended since [head]
     * was read; the caller then rolls its transaction back and signs again on the new head.
     *
     * @param transaction The caller's transaction, opened for [orgId].
     * @param orgId The organization.
     * @param head The head the events were signed on.
     * @param events The signed events ([sign]).
     *
     * @return Whether they were appended.
     */
    fun append(transaction: JdbcTransaction, orgId: Uuid, head: AuditHead?, events: List<AuditEvent>): Boolean {
        val chain = orgId.toString()
        transaction.lockUntilCommit(chain)
        if (transaction.headOf(chain, orgId)?.hash != head?.hash) return false
        events.forEach { transaction.insertEvent(chain, it) }
        return true
    }
}
