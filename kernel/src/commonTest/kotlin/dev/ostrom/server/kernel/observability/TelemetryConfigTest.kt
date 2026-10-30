// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.observability

import dev.ostrom.core.telemetry.export.Signal
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.config.ServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TelemetryConfigTest {
    private val database = mapOf(
        "OSTROM_DATABASE_URL" to "postgresql://localhost:5432/ostrom",
        "OSTROM_DATABASE_USER" to "ostrom",
        "OSTROM_DATABASE_PASSWORD" to "secret",
    )

    @Test
    fun nothingConfiguredSendsNothing() {
        val telemetry = ServerConfig.from(MapConfigSource(database)).telemetry

        assertNull(telemetry.otlp)
        assertNull(telemetry.langfuse)
        assertNull(telemetry.postHog)
        assertEquals("ostrom-server", telemetry.serviceName)
    }

    @Test
    fun theStandardOpenTelemetryVariablesAreRead() {
        val telemetry = ServerConfig.from(
            MapConfigSource(
                database + mapOf(
                    "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://collector:4318",
                    "OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment.name=staging",
                    "OSTROM_POSTHOG_API_KEY" to "phc_1",
                ),
            ),
        ).telemetry

        assertEquals("http://collector:4318/v1/traces", telemetry.otlp?.url(Signal.TRACES))
        assertEquals("staging", telemetry.environment)
        assertEquals("phc_1", telemetry.postHog?.projectApiKey)
    }

    @Test
    fun anInvalidTelemetryValueIsAConfigurationErrorThatNeverEchoesTheValue() {
        val error = assertFailsWith<InvalidConfigException> {
            ServerConfig.from(MapConfigSource(database + ("OSTROM_LANGFUSE_HOST" to "https://cloud.langfuse.com")))
        }

        assertEquals(false, error.message.orEmpty().contains("cloud.langfuse.com"))
    }
}
