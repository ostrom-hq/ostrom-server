package dev.ostrom.server.app

import dev.ostrom.core.telemetry.Telemetry
import dev.ostrom.core.telemetry.otlp.Resource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.ServerConfig
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.connectPostgres
import dev.ostrom.server.kernel.http.SERVER_VERSION
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.module.kernelModule
import dev.ostrom.server.kernel.module.serverKoin
import dev.ostrom.server.kernel.observability.OstromMetrics
import dev.ostrom.server.kernel.observability.ProcessMetrics
import dev.ostrom.server.kernel.observability.TelemetryLogger
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.github.smyrgeorge.sqlx4k.Driver
import io.ktor.client.HttpClient
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Starts the server; the configuration keys are documented in docs/MODULES.md. Exits with 2 on an invalid
 * configuration and 1 on any other start failure (database unreachable, unreadable key, failed migration), after
 * one structured log line.
 */
fun main() {
    val config = try {
        ServerConfig.load()
    } catch (error: InvalidConfigException) {
        JsonLogger(LogLevel.ERROR).error("invalid configuration", mapOf("reason" to error.message))
        exitProcess(2)
    }
    // Telemetry has its own scope: it is flushed after the workers stop, then cancelled.
    val exporters = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val telemetry = Telemetry.start(
        settings = config.telemetry,
        resource = Resource(
            serviceName = config.telemetry.serviceName,
            serviceVersion = SERVER_VERSION,
            environment = config.telemetry.environment,
            instanceId = EnvironmentConfigSource()["OSTROM_POD_NAME"] ?: Uuid.random().toString(),
        ),
        scope = exporters,
        httpClient = { HttpClient() },
    )
    val logger = TelemetryLogger(JsonLogger(config.logLevel), telemetry, config.logLevel)
    val metrics = OstromMetrics(telemetry.meter)
    ProcessMetrics.register(telemetry.meter)
    val modules = serverModules()
    var driver: Driver? = null
    var koin: Koin? = null
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val database = connectPostgres(config.database).also { driver = it }
        val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
        val kernel = kernelModule(logger, database, signer, telemetry = telemetry, metrics = metrics)
        val server = serverKoin(kernel, modules).also { koin = it }
        runBlocking {
            val migrations = server.get<MigrationRunner>()
            modules.forEach { migrations.migrate(it.name, it.migrations) }
            modules.forEach { it.initialize(server) }
        }
        modules.forEach { it.start(workers, server) }
        logger.info(
            "server starting",
            mapOf("version" to SERVER_VERSION, "host" to config.host, "port" to config.port, "key_id" to signer.keyId),
        )
        embeddedServer(CIO, port = config.port, host = config.host) { configureHttp(server, modules) }.start(wait = true)
    } catch (error: Throwable) {
        logger.error("server stopped on a failure", error = error)
        shutDown(workers, koin, driver, telemetry, exporters)
        exitProcess(1)
    }
    shutDown(workers, koin, driver, telemetry, exporters)
}

private fun shutDown(
    workers: CoroutineScope,
    koin: Koin?,
    driver: Driver?,
    telemetry: Telemetry,
    exporters: CoroutineScope,
) {
    workers.cancel()
    koin?.close()
    runBlocking {
        driver?.close()
        telemetry.shutdown(TELEMETRY_FLUSH_TIMEOUT)
    }
    exporters.cancel()
}

/** How long the last spans, logs and metrics may take to leave when the server stops. */
private val TELEMETRY_FLUSH_TIMEOUT = 5.seconds
