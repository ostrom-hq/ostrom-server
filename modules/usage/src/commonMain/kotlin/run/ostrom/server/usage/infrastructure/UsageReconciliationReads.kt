// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.PendingUsage
import run.ostrom.server.usage.domain.ReconciledUsage
import run.ostrom.server.usage.domain.UsageEvidence
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

// The reads of the usage review (UsageReconciliationRepositoryImpl), one function each so that UsageQueryPlansTest
// explains exactly what the repository sends. Every one filters on the org.

/** The oldest `held_at` still kept, by the database's clock: past it, a hold is retired and counts nowhere. */
private fun retained() = databaseNowPlus(-HoldWindow.RETENTION.inWholeSeconds)

/**
 * The org's holds under review: run out by the database's clock, held within the retention, and resolved by no
 * correction (TeamCode's « UNCERTAIN, or ACTIVE past its lease »).
 */
private fun pendingOf(orgId: Uuid): Op<Boolean> {
    val h = UsageHolds
    val r = UsageReconciliations
    val corrected = r.select(r.callId).where { (r.orgId eq orgId) and (r.callId eq h.callId) }
    return (h.orgId eq orgId) and (h.expiresAt lessEq databaseNow()) and (h.heldAt greaterEq retained()) and
        notExists(corrected)
}

/**
 * A page of the org's unknown usage, newest first by `(held_at, call_id)` from [before] (index
 * `usage_holds_org_held`, or `usage_holds_org_member` for a member), [limit] rows.
 */
internal fun pendingRead(orgId: Uuid, memberId: Uuid?, before: ReviewCursor?, limit: Int): Read<PendingUsage> {
    val h = UsageHolds
    val query = h.selectAll()
        .where {
            pendingOf(orgId) andIfNotNull memberId?.let { h.memberId eq it } andIfNotNull before?.let {
                (h.heldAt lessEq it.at) and rowLess(listOf(h.heldAt, h.callId), it.bounds())
            }
        }
        .orderBy(h.heldAt to SortOrder.DESC, h.callId to SortOrder.DESC)
        .limit(limit)
    return Read(query) { it.toPending() }
}

/** The hold [id] of the org while it is under review. */
internal fun pendingOneRead(orgId: Uuid, id: Uuid): Read<PendingUsage> {
    val h = UsageHolds
    return Read(h.selectAll().where { pendingOf(orgId) and (h.callId eq id) }) { it.toPending() }
}

/**
 * A page of the org's corrections still kept, newest first by `(reconciled_at, call_id)` from [before] (index
 * `usage_reconciliations_org_at`, or `_org_member_at` for a member), [limit] rows.
 */
internal fun historyRead(orgId: Uuid, memberId: Uuid?, before: ReviewCursor?, limit: Int): Read<ReconciledUsage> {
    val r = UsageReconciliations
    val query = r.selectAll()
        .where {
            val kept = (r.orgId eq orgId) and (r.heldAt greaterEq retained())
            kept andIfNotNull memberId?.let { r.memberId eq it } andIfNotNull before?.let {
                (r.reconciledAt lessEq it.at) and rowLess(listOf(r.reconciledAt, r.callId), it.bounds())
            }
        }
        .orderBy(r.reconciledAt to SortOrder.DESC, r.callId to SortOrder.DESC)
        .limit(limit)
    return Read(query) { it.toReconciled() }
}

/** The correction of the hold [id] of the org, while kept. */
internal fun reconciliationRead(orgId: Uuid, id: Uuid): Read<ReconciledUsage> {
    val r = UsageReconciliations
    val query = r.selectAll().where { (r.orgId eq orgId) and (r.callId eq id) and (r.heldAt greaterEq retained()) }
    return Read(query) { it.toReconciled() }
}

private fun ReviewCursor.bounds() =
    listOf(QueryParameter(at, InstantColumnType()), QueryParameter(id, UuidColumnType()))

private fun ResultRow.toPending(): PendingUsage {
    val h = UsageHolds
    return PendingUsage(
        id = this[h.callId],
        memberId = this[h.memberId],
        micros = this[h.micros],
        heldAt = this[h.heldAt],
        leaseExpiredAt = this[h.expiresAt],
        workflowId = this[h.workflowId],
        launchId = this[h.launchId],
    )
}

/** The correction of a row of [UsageReconciliations]. */
internal fun ResultRow.toReconciled(): ReconciledUsage {
    val r = UsageReconciliations
    return ReconciledUsage(
        id = this[r.callId],
        memberId = this[r.memberId],
        workflowId = this[r.workflowId],
        launchId = this[r.launchId],
        providerRequestId = this[r.providerRequestId],
        heldAt = this[r.heldAt],
        previousMicros = this[r.previousMicros],
        micros = this[r.micros],
        source = UsageEvidence.valueOf(this[r.evidence]),
        reference = this[r.reference],
        reason = this[r.reason],
        reconciledBy = this[r.reconciledBy],
        reconciledAt = this[r.reconciledAt],
    )
}
