// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.observability

import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.metrics.GaugeReading
import run.ostrom.core.telemetry.metrics.Meter
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.PoolState

/**
 * The database pool's connections, with OpenTelemetry's database client names (audit CRL-04, ControlResell's
 * `HikariTelemetry`): `db.client.connection.count` by state (`used`, `idle`), `db.client.connection.max` and
 * `db.client.connection.pending_requests`, the callers waiting for a connection. A wait that lasts is the first sign
 * of a pool too small for its load or of a stalled database, before `/ready` takes the replica out
 * ([PoolHealthCheck][run.ostrom.server.kernel.database.PoolHealthCheck]). Read only when the metric reader collects,
 * without opening a connection.
 */
object PoolMetrics {
    private const val POOL = "db.client.connection.pool.name"
    private const val STATE = "db.client.connection.state"

    /**
     * Registers the three gauges on [meter].
     *
     * @param meter Where the gauges are created.
     * @param state Reads the pool's state; no reading once the pool is closed.
     */
    fun register(meter: Meter, state: () -> PoolState) {
        meter.gauge("db.client.connection.count", "{connection}", "Database connections of the pool, by state.") {
            state().readings { listOf(it.used to "used", it.idle to "idle") }
        }
        meter.gauge("db.client.connection.max", "{connection}", "The most connections the pool may open.") {
            state().readings { listOf(it.max to null) }
        }
        meter.gauge(
            "db.client.connection.pending_requests",
            "{request}",
            "Callers waiting for a database connection.",
        ) {
            state().readings { listOf(it.waiting to null) }
        }
    }

    private fun PoolState.readings(values: (PoolState) -> List<Pair<Int, String?>>): List<GaugeReading> =
        if (!open) {
            emptyList()
        } else {
            values(this).map { (value, connections) ->
                val attributes = if (connections == null) {
                    Attributes.of(POOL to JdbcDatabase.POOL_NAME)
                } else {
                    Attributes.of(POOL to JdbcDatabase.POOL_NAME, STATE to connections)
                }
                GaugeReading(value.toDouble(), attributes)
            }
        }
}
