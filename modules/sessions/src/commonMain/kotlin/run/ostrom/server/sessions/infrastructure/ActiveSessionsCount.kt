// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCaseImpl
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Clock

/**
 * The sessions running in every organization, for the `ostrom.sessions.active{where="local"}` gauge: open, with a
 * launcher that gave a sign of life within [SweepStaleSessionsUseCaseImpl.STALE_AFTER] (a hook alone is never
 * followed, a silent launcher is about to be swept). A count through `sessions_active_count` (migration 4), which
 * returns no row of any organization.
 *
 * @param database The database, as the serving role.
 * @param clock The time.
 */
class ActiveSessionsCount(private val database: JdbcDatabase, private val clock: Clock) {
    /** @return The running sessions. */
    suspend fun read(): Long {
        val since = QueryParameter(clock.now() - SweepStaleSessionsUseCaseImpl.STALE_AFTER, InstantColumnType())
        val active = CustomFunction("sessions_active_count", LongColumnType(), since)
        return database.query { Table.Dual.select(active).single()[active] }
    }
}
