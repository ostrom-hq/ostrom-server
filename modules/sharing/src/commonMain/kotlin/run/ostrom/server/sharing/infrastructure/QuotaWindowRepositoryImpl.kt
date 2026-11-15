// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

/**
 * [QuotaWindowRepository] on PostgreSQL ([SharingMigrations]) through Exposed, every query confined to the org it
 * names (`inOrg`, S2-01). A window is only written for an account of that org: the transaction reads the account row
 * first, which another org's confinement hides.
 *
 * @param database The serving role's pool.
 */
class QuotaWindowRepositoryImpl(private val database: JdbcDatabase) : QuotaWindowRepository {
    override suspend fun put(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>) {
        database.inOrg(orgId) {
            if (accountOfOrg(orgId, id).empty()) return@inOrg
            val w = SharingWindows
            windows.forEach { window ->
                w.upsert(w.accountId, w.name, onUpdateExclude = listOf(w.orgId)) {
                    it[w.orgId] = orgId
                    it[w.accountId] = id
                    it[w.name] = window.window
                    it[w.utilization] = window.utilization
                    it[w.resetsAt] = window.resetsAt
                    it[w.observedAt] = window.observedAt
                }
            }
        }
    }

    override suspend fun byAccount(orgId: Uuid): Map<Uuid, List<QuotaWindow>> = database.inOrg(orgId) {
        val w = SharingWindows
        w.select(w.accountId, w.name, w.utilization, w.resetsAt, w.observedAt)
            .where { w.orgId eq orgId }
            .limit(MAX_WINDOWS)
            .map { row ->
                row[w.accountId] to QuotaWindow(
                    window = row[w.name],
                    utilization = row[w.utilization],
                    resetsAt = row[w.resetsAt],
                    observedAt = row[w.observedAt],
                )
            }
            .groupBy({ it.first }, { it.second })
    }
}

private const val MAX_WINDOWS = 10_000
