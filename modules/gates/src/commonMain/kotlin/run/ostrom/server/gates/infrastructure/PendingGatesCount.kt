// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The gates waiting for an answer in every organization, for the `ostrom.gates.pending` gauge: a count through
 * `gates_pending_count()` (migration 4), which returns no row of any organization.
 *
 * @param database The database, as the serving role.
 */
class PendingGatesCount(private val database: JdbcDatabase) {
    /** @return The pending gates. */
    suspend fun read(): Long {
        val pending = CustomFunction("gates_pending_count", LongColumnType())
        return database.query { Table.Dual.select(pending).single()[pending] }
    }
}
