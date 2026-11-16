// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.sharing.PoolSpend
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [PoolSpend] on the ledger: the cost of the calls served from each pool, by the pool each event recorded when it was
 * served (version 14), read in one query confined to the organization ([JdbcDatabase.inOrg]).
 *
 * @param database The serving role's pool.
 */
class LedgerPoolSpend(private val database: JdbcDatabase) : PoolSpend {
    override suspend fun spentMicros(orgId: Uuid, poolIds: Collection<String>, since: Instant): Map<String, Long> {
        if (poolIds.isEmpty()) return emptyMap()
        return database.inOrg(orgId) { poolsSpentRead(orgId, poolIds, since).rows().toMap() }
    }
}
