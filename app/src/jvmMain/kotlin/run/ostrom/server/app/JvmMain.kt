// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app

import run.ostrom.core.telemetry.Telemetry
import run.ostrom.core.telemetry.otlp.Resource
import run.ostrom.server.kernel.SERVER_VERSION
import run.ostrom.server.kernel.config.ServerConfig
import run.ostrom.server.kernel.database.MigrationRunner
import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.database.connectPostgres
import run.ostrom.server.kernel.database.migrateUnderLock
import run.ostrom.server.kernel.database.requireRowLevelSecurity
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.module.kernelModule
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.kernel.observability.OstromMetrics
import run.ostrom.server.kernel.observability.ProcessMetrics
import run.ostrom.server.kernel.observability.TelemetryLogger
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

// BENCH ONLY (chore/bench-native-vs-jvm, never merged): the same start as nativeMain/Main.kt on the JVM, without the
// native signal pipe, thread names and Curl settings, to measure the same application on both runtimes.
fun main(args: Array<String>) {
    val source = ServerConfig.source()
    val config = ServerConfig.from(source)
    when (args.firstOrNull()) {
        "migrate" -> runBlocking {
            val owner = connectPostgres(ServerConfig.migrationDatabase(source))
            owner.migrateUnderLock(MigrationRunnerImpl(owner, JsonLogger(LogLevel.INFO)), serverModules())
            owner.close()
            println("migrations done")
        }
        "seed-test-account" -> {
            val database = connectPostgres(config.database)
            val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
            val modules = serverModules()
            val server = serverKoin(kernelModule(JsonLogger(config.logLevel), database, signer, source), modules)
            println(
                runBlocking {
                    modules.forEach { it.initialize(server) }
                    seedTestAccount(server, source, args[1])
                },
            )
            server.close()
            runBlocking { database.close() }
        }
        else -> serve(source, config)
    }
}

private fun serve(source: run.ostrom.server.kernel.config.ConfigSource, config: ServerConfig) {
    val exporters = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val telemetry = Telemetry.start(
        settings = config.telemetry,
        resource = Resource(
            serviceName = config.telemetry.serviceName,
            serviceVersion = SERVER_VERSION,
            environment = config.telemetry.environment,
            instanceId = config.instanceId ?: Uuid.random().toString(),
        ),
        scope = exporters,
        httpClient = { HttpClient(ClientCIO) },
    )
    val logger = TelemetryLogger(JsonLogger(config.logLevel), telemetry, config.logLevel)
    val metrics = OstromMetrics(telemetry.meter)
    ProcessMetrics.register(telemetry.meter)
    val modules = serverModules()
    val failures = CoroutineExceptionHandler { _, error ->
        logger.error("background job failed", mapOf("error" to error::class.simpleName))
    }
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)
    val database = connectPostgres(config.database)
    runBlocking { database.requireRowLevelSecurity() }
    val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
    val kernel = kernelModule(logger, database, signer, source, telemetry = telemetry, metrics = metrics)
    val server = serverKoin(kernel, modules)
    runBlocking {
        val migrations = server.get<MigrationRunner>()
        modules.forEach { migrations.verify(it.name, it.migrations) }
        modules.forEach { it.initialize(server) }
    }
    modules.forEach { it.start(workers, server) }
    logger.info("server starting", mapOf("version" to SERVER_VERSION, "runtime" to "jvm", "port" to config.port))
    embeddedServer(
        CIO,
        configure = {
            connector {
                port = config.port
                host = config.host
            }
            reuseAddress = true
        },
        module = { configureHttp(server, modules) },
    ).start(wait = true)
}
