// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.core.telemetry.Telemetry
import run.ostrom.core.telemetry.otlp.Resource
import run.ostrom.server.kernel.SERVER_VERSION
import run.ostrom.server.kernel.config.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** How long the last spans, logs and metrics may take to leave when the server stops. */
private val TELEMETRY_FLUSH_TIMEOUT = 5.seconds

/**
 * The server's telemetry: traces, metrics, logs and product events, sent nowhere unless configured.
 *
 * @param config The server configuration, with its telemetry settings.
 * @param exporters The scope of the exporters, cancelled after the last flush.
 */
internal fun startTelemetry(config: ServerConfig, exporters: CoroutineScope): Telemetry = Telemetry.start(
    settings = config.telemetry,
    resource = Resource(
        serviceName = config.telemetry.serviceName,
        serviceVersion = SERVER_VERSION,
        environment = config.telemetry.environment,
        instanceId = config.instanceId ?: Uuid.random().toString(),
    ),
    scope = exporters,
    httpClient = ::telemetryHttpClient,
)

/** The exporters' HTTP client: CIO, with the JDK's TLS. */
internal fun telemetryHttpClient(): HttpClient = HttpClient(CIO)

/**
 * Stops the server with [stop], then flushes the telemetry whatever failed in it: the last spans and logs, the one
 * that says why the server stopped among them, leave.
 */
internal fun stopThenFlush(telemetry: Telemetry, exporters: CoroutineScope, stop: () -> Unit) {
    try {
        stop()
    } finally {
        runBlocking { telemetry.shutdown(TELEMETRY_FLUSH_TIMEOUT) }
        exporters.cancel()
    }
}
