// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A keyset position, newest first: the items strictly older than it.
 *
 * @property at The item's time (`held_at` of a hold, `reconciled_at` of a correction).
 * @property id Its id.
 */
internal data class ReviewCursor(val at: Instant, val id: Uuid)

/** The keyset position of the org's hold [id], whatever its state, or null when it has none. */
internal fun JdbcTransaction.pendingCursor(orgId: Uuid, id: Uuid): ReviewCursor? {
    val h = UsageHolds
    return h.select(h.heldAt).where { (h.orgId eq orgId) and (h.callId eq id) }.singleOrNull()
        ?.let { ReviewCursor(it[h.heldAt], id) }
}

/** The keyset position of the org's correction [id], or null when it has none. */
internal fun JdbcTransaction.historyCursor(orgId: Uuid, id: Uuid): ReviewCursor? {
    val r = UsageReconciliations
    return r.select(r.reconciledAt).where { (r.orgId eq orgId) and (r.callId eq id) }.singleOrNull()
        ?.let { ReviewCursor(it[r.reconciledAt], id) }
}
