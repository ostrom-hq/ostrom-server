// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.sharing

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the shared accounts of named pools served, read from the usage ledger by the pool each call was served from
 * (TeamCode's `DailyScope.LENT`, keyed by the reservation's source pool): an account moved to another pool leaves its
 * past spend where it was. The usage module binds it; the sharing module reads it to know what a shared overflow pool
 * lent today, and keeps its own default when it is unbound.
 */
fun interface PoolSpend {
    /**
     * What the model calls served from [poolIds] cost since [since], in one read.
     *
     * @param orgId The organization.
     * @param poolIds The named pools.
     * @param since The first instant counted.
     *
     * @return USD millionths by pool; a pool with nothing priced since then is absent.
     */
    suspend fun spentMicros(orgId: Uuid, poolIds: Collection<String>, since: Instant): Map<String, Long>
}
