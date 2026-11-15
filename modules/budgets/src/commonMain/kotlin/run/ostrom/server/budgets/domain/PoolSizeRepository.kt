// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The org's automatic daily budget, as the pool size job stores it (TeamCode's `pool_budgets.auto_*`). */
interface PoolSizeRepository {
    /** The value stored last, null before the first estimate. */
    suspend fun read(orgId: Uuid): AutomaticDaily?

    /**
     * Stores the next value under the org's lock, in one transaction, so two replicas never smooth from the same
     * previous value.
     *
     * @param orgId The org.
     * @param at The time of the value.
     * @param next The value to store, from the one stored before.
     *
     * @return What is stored.
     */
    suspend fun store(orgId: Uuid, at: Instant, next: (AutomaticDaily?) -> Long): AutomaticDaily
}
