// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.budgets.domain.AutomaticDaily
import run.ostrom.server.budgets.domain.PoolSizeRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.lockUntilCommit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [PoolSizeRepository] on PostgreSQL through Exposed ([BudgetsAutomaticDaily]), confined to the org it names
 * ([JdbcDatabase.inOrg]); a store takes the org's advisory lock.
 *
 * @param database The JDBC pool.
 */
class PoolSizeRepositoryImpl(private val database: JdbcDatabase) : PoolSizeRepository {
    override suspend fun read(orgId: Uuid): AutomaticDaily? =
        database.inOrg(orgId) { automaticRead(orgId).singleOrNull() }

    override suspend fun store(orgId: Uuid, at: Instant, next: (AutomaticDaily?) -> Long): AutomaticDaily =
        database.inOrg(orgId) {
            lockUntilCommit("budgets_automatic_daily:$orgId")
            val stored = AutomaticDaily(next(automaticRead(orgId).singleOrNull()), at)
            val t = BudgetsAutomaticDaily
            t.upsert {
                it[t.orgId] = orgId
                it[t.micros] = stored.micros
                it[t.computedAt] = stored.computedAt
            }
            stored
        }
}

/** The org's stored value, by its primary key: read with every read of a call's limits. */
internal fun automaticRead(orgId: Uuid): Read<AutomaticDaily> {
    val t = BudgetsAutomaticDaily
    return Read(t.select(t.micros, t.computedAt).where { t.orgId eq orgId }) {
        AutomaticDaily(it[t.micros], it[t.computedAt])
    }
}
