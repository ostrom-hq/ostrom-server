// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryScope
import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select

// The reads of the curation's reviews (MemoryCurationStoreImpl).

/** The two entries of a review, [MemoryEntries] twice. */
internal fun reviewEntries() = MemoryEntries.alias("o") to MemoryEntries.alias("n")

/** A review with its two entries' titles and origins. */
internal fun reviewsQuery(older: Alias<MemoryEntries>, newer: Alias<MemoryEntries>): Query {
    val r = MemoryReviews
    return r.join(older, JoinType.INNER, r.olderId, older[MemoryEntries.id])
        .join(newer, JoinType.INNER, r.newerId, newer[MemoryEntries.id])
        .select(
            r.id, r.scope, r.ownerId, r.kind, r.reason, r.status, r.createdAt, r.olderId,
            older[MemoryEntries.title], older[MemoryEntries.origin],
            r.newerId, newer[MemoryEntries.title], newer[MemoryEntries.origin],
        )
}

/** The reviews a caller may act on: their own personal ones, and the organization's for an administrator. */
internal fun reachable(caller: MemoryCaller): Op<Boolean> {
    val r = MemoryReviews
    val own = (r.scope eq MemoryScope.PERSONAL.wire) and (r.ownerId eq caller.memberId)
    val scopes = if (caller.admin) own or ((r.scope eq TEAM) and (r.ownerId eq "")) else own
    return (r.orgId eq caller.orgId) and scopes
}
