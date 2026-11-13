// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.config.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServerTelemetryTest {
    private val database = mapOf("OSTROM_DATABASE_URL" to "postgresql://ostrom:ostrom@localhost:5432/ostrom")

    @Test
    fun theTelemetryIsFlushedAfterAStopThatFailed() {
        for (pod in listOf(null, "ostrom-server-0")) {
            val values = database + listOfNotNull(pod?.let { "OSTROM_POD_NAME" to it })
            val config = ServerConfig.from(MapConfigSource(values))
            val exporters = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val telemetry = startTelemetry(config, exporters)
            val error = assertFailsWith<IllegalStateException> {
                stopThenFlush(telemetry, exporters) { error("the pool did not close") }
            }
            assertEquals("the pool did not close", error.message)
            assertFalse(exporters.isActive, "the exporters are cancelled after the last flush")
        }
    }

    @Test
    fun theExportersUseTheCioClient() {
        telemetryHttpClient().use { assertEquals("CIOEngine", it.engine::class.simpleName) }
    }
}
