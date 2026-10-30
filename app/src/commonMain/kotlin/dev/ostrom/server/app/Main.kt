package dev.ostrom.server.app

import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.ServerConfig
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.connectPostgres
import dev.ostrom.server.kernel.http.SERVER_VERSION
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.module.kernelModule
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import kotlin.system.exitProcess

/** Starts the server; the configuration keys are documented in docs/MODULES.md. */
fun main() {
    val config = try {
        ServerConfig.load()
    } catch (error: InvalidConfigException) {
        JsonLogger(LogLevel.ERROR).error("invalid configuration", mapOf("reason" to error.message))
        exitProcess(2)
    }
    val logger = JsonLogger(config.logLevel)
    val modules = serverModules()
    val driver = connectPostgres(config.database)
    val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
    val koin = koinApplication {
        allowOverride(true)
        modules(listOf(kernelModule(logger, driver, signer)) + modules.map { it.koinModule })
    }.koin
    runBlocking {
        val migrations = koin.get<MigrationRunner>()
        modules.forEach { migrations.migrate(it.name, it.migrations) }
        modules.forEach { it.initialize(koin) }
    }
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    modules.forEach { it.start(workers, koin) }
    logger.info(
        "server starting",
        mapOf("version" to SERVER_VERSION, "host" to config.host, "port" to config.port, "key_id" to signer.keyId),
    )
    try {
        embeddedServer(CIO, port = config.port, host = config.host) { configureHttp(koin, modules) }.start(wait = true)
    } finally {
        workers.cancel()
        koin.close()
        runBlocking { driver.close() }
    }
}
