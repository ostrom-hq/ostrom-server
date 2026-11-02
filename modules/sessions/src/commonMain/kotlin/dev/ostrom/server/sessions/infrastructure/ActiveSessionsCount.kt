// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.infrastructure

import dev.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCaseImpl
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Clock

/**
 * The sessions running in every organization, for the `ostrom.sessions.active{where="local"}` gauge: open, with a
 * launcher that gave a sign of life within [SweepStaleSessionsUseCaseImpl.STALE_AFTER] (a hook alone is never
 * followed, a silent launcher is about to be swept). A count through `sessions_active_count` (migration 2), which
 * returns no row of any organization.
 *
 * @param driver The database, as the serving role.
 * @param clock The time.
 */
class ActiveSessionsCount(private val driver: Driver, private val clock: Clock) {
    /** @return The running sessions. */
    suspend fun read(): Long = driver.fetchAll(
        Statement.create("SELECT sessions_active_count(:since) AS active")
            .bind("since", clock.now() - SweepStaleSessionsUseCaseImpl.STALE_AFTER),
    ).getOrThrow().single().get("active").asLong()
}
