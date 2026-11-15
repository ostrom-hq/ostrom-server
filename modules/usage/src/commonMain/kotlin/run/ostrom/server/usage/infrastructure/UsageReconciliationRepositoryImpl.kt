// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.PendingUsage
import run.ostrom.server.usage.domain.ReconcileOutcome
import run.ostrom.server.usage.domain.ReconciledUsage
import run.ostrom.server.usage.domain.ReviewPage
import run.ostrom.server.usage.domain.UsageReconciliationChange
import run.ostrom.server.usage.domain.UsageReconciliationRepository
import run.ostrom.server.usage.domain.UsageReconciliationRules
import run.ostrom.server.usage.domain.UsageReview
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [UsageReconciliationRepository] on PostgreSQL through Exposed ([UsageHolds], [UsageReconciliations]; the reads in
 * `UsageReconciliationReads.kt`). Every call is one transaction confined to the org it names ([JdbcDatabase.inOrg]).
 * A correction takes the lock of the org's admissions ([holdsLockKey]), so that no call is admitted on the charge it
 * replaces, and is stamped by the database's clock once the lock is held. The corrected hold keeps its time, member,
 * workflow and launch: its new amount counts where the old one did (the month and day it was held), nothing today.
 *
 * @param database The JDBC pool.
 */
class UsageReconciliationRepositoryImpl(private val database: JdbcDatabase) : UsageReconciliationRepository {
    override suspend fun pending(orgId: Uuid, memberId: Uuid?, before: Uuid?): ReviewPage<PendingUsage>? =
        database.inOrg(orgId) {
            val cursor = before?.let { id -> pendingCursor(orgId, id) ?: return@inOrg null }
            page(pendingRead(orgId, memberId, cursor, PAGE).rows()) { it.id }
        }

    override suspend fun history(orgId: Uuid, memberId: Uuid?, before: Uuid?): ReviewPage<ReconciledUsage>? =
        database.inOrg(orgId) {
            val cursor = before?.let { id -> historyCursor(orgId, id) ?: return@inOrg null }
            page(historyRead(orgId, memberId, cursor, PAGE).rows()) { it.id }
        }

    override suspend fun review(orgId: Uuid, id: Uuid): UsageReview? = database.inOrg(orgId) {
        reconciliationRead(orgId, id).rows().singleOrNull()?.let { return@inOrg UsageReview(reconciliation = it) }
        pendingOneRead(orgId, id).rows().singleOrNull()?.let { UsageReview(pending = it) }
    }

    override suspend fun reconcile(
        orgId: Uuid,
        id: Uuid,
        actor: Uuid,
        change: UsageReconciliationChange,
    ): ReconcileOutcome = database.inOrg(orgId) {
        // The admissions' lock: no call of the org is admitted while its charge changes, on any replica.
        lockKeyUntilCommit(holdsLockKey(orgId))
        val now = lockedNow()
        val stored = reconciliationRead(orgId, id).rows().singleOrNull()
        val h = UsageHolds
        val hold = if (stored == null) {
            h.selectAll().where { (h.orgId eq orgId) and (h.callId eq id) }.singleOrNull()
        } else {
            null
        }
        when {
            stored != null -> if (UsageReconciliationRules.sameCorrection(stored, change)) {
                ReconcileOutcome.Reconciled(stored, changed = false)
            } else {
                ReconcileOutcome.AlreadyReconciled
            }
            // None, settled since, still within its lease, or retired.
            hold == null || hold[h.expiresAt] > now || hold[h.heldAt] < now - HoldWindow.RETENTION ->
                ReconcileOutcome.NotFound
            // No provider request id is captured yet (TC-USR-3): the reviewer saw none.
            hold[h.micros] != change.expectedMicros || change.expectedProviderRequestId != null ->
                ReconcileOutcome.Stale
            else -> ReconcileOutcome.Reconciled(correct(orgId, hold.toReconciled(actor, now, change), now))
        }
    }

    /** Rewrites the hold's charge, drops the org's corrections past the retention, and records [record]. */
    private fun JdbcTransaction.correct(orgId: Uuid, record: ReconciledUsage, now: Instant): ReconciledUsage {
        val h = UsageHolds
        h.update({ (h.orgId eq orgId) and (h.callId eq record.id) }) { it[micros] = record.micros }
        val r = UsageReconciliations
        r.deleteWhere { (r.orgId eq orgId) and (r.heldAt less now - HoldWindow.RETENTION) }
        r.insert {
            it[callId] = record.id
            it[this.orgId] = orgId
            it[memberId] = record.memberId
            it[workflowId] = record.workflowId
            it[launchId] = record.launchId
            it[providerRequestId] = record.providerRequestId
            it[heldAt] = record.heldAt
            it[previousMicros] = record.previousMicros
            it[micros] = record.micros
            it[evidence] = record.source.name
            it[reference] = record.reference
            it[reason] = record.reason
            it[reconciledBy] = record.reconciledBy
            it[reconciledAt] = record.reconciledAt
        }
        return record
    }

    private fun ResultRow.toReconciled(
        actor: Uuid,
        now: Instant,
        change: UsageReconciliationChange,
    ): ReconciledUsage {
        val h = UsageHolds
        return ReconciledUsage(
            id = this[h.callId],
            memberId = this[h.memberId],
            workflowId = this[h.workflowId],
            launchId = this[h.launchId],
            providerRequestId = null,
            heldAt = this[h.heldAt],
            previousMicros = this[h.micros],
            micros = change.micros,
            source = change.source,
            reference = change.reference,
            reason = change.reason,
            reconciledBy = actor,
            reconciledAt = now,
        )
    }

    /** The first [UsageReconciliationRules.PAGE_SIZE] of [rows], read one more to know whether a page follows. */
    private fun <T> page(rows: List<T>, id: (T) -> Uuid): ReviewPage<T> {
        val size = UsageReconciliationRules.PAGE_SIZE
        return ReviewPage(rows.take(size), if (rows.size > size) id(rows[size - 1]) else null)
    }

    /** The database's time now, not the transaction's start: taken once the lock is held. */
    private fun lockedNow(): Instant {
        val clock = CustomFunction("clock_timestamp", InstantColumnType())
        return Table.Dual.select(clock).single()[clock]
    }

    private companion object {
        const val PAGE = UsageReconciliationRules.PAGE_SIZE + 1
    }
}
