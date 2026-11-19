// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.observability

import run.ostrom.core.telemetry.Attributes
import run.ostrom.server.kernel.database.PoolState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pool's connections and waiting callers are exported at each collection (audit CRL-04). */
class PoolMetricsTest {
    private val pool = "db.client.connection.pool.name" to "ostrom-server"

    @Test
    fun theGaugesReadThePoolAtEachCollection() {
        val telemetry = RecordingTelemetry()
        var state = PoolState(open = true, waiting = 0, used = 3, idle = 7, max = 10)
        PoolMetrics.register(telemetry.meter) { state }

        val count = telemetry.gauge("db.client.connection.count").associate { it.attributes to it.value }
        assertEquals(
            mapOf(
                Attributes.of(pool, "db.client.connection.state" to "used") to 3.0,
                Attributes.of(pool, "db.client.connection.state" to "idle") to 7.0,
            ),
            count,
        )
        assertEquals(listOf(10.0), telemetry.gauge("db.client.connection.max").map { it.value })
        assertEquals(listOf(0.0), telemetry.gauge("db.client.connection.pending_requests").map { it.value })

        state = state.copy(waiting = 4, used = 10, idle = 0)
        val pending = telemetry.gauge("db.client.connection.pending_requests").single()
        assertEquals(4.0, pending.value)
        assertEquals(Attributes.of(pool), pending.attributes)
    }

    @Test
    fun aClosedPoolGivesNoReading() {
        val telemetry = RecordingTelemetry()
        PoolMetrics.register(telemetry.meter) { PoolState(open = false, waiting = 0) }

        val names = setOf(
            "db.client.connection.count",
            "db.client.connection.max",
            "db.client.connection.pending_requests",
        )
        val collected = telemetry.meter.collect().map { it.name }
        assertTrue(collected.none { it in names }, "$collected")
    }
}
