// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong

/**
 * The gates waiting for an answer in every organization, for the `ostrom.gates.pending` gauge: a count through
 * `gates_pending_count()` (migration 3), which returns no row of any organization.
 *
 * @param driver The database, as the serving role.
 */
class PendingGatesCount(private val driver: Driver) {
    /** @return The pending gates. */
    suspend fun read(): Long =
        driver.fetchAll("SELECT gates_pending_count() AS pending").getOrThrow().single().get("pending").asLong()
}
