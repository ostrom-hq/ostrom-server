// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.sharing.SharedAccountSpend
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SharedAccountSpend] on the ledger: the cost of the calls each shared account served, read in one query confined to
 * the organization ([JdbcDatabase.inOrg]).
 *
 * @param database The serving role's pool.
 */
class LedgerSharedAccountSpend(private val database: JdbcDatabase) : SharedAccountSpend {
    override suspend fun spentMicros(orgId: Uuid, accountIds: Collection<Uuid>, since: Instant): Map<Uuid, Long> {
        if (accountIds.isEmpty()) return emptyMap()
        return database.inOrg(orgId) { accountsSpentRead(orgId, accountIds, since).rows().toMap() }
    }
}
