// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(NativeRuntimeApi::class)

package dev.ostrom.server.app

import dev.ostrom.server.app.signals.ostrom_await_signal
import dev.ostrom.server.app.signals.ostrom_install_signal_handlers
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.ServerConfig
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.kernel.database.MigrationsNotAppliedException
import dev.ostrom.server.kernel.database.UnsafeDatabaseRoleException
import dev.ostrom.server.kernel.database.connectPostgres
import dev.ostrom.server.kernel.database.migrateUnderLock
import dev.ostrom.server.kernel.database.requireRowLevelSecurity
import dev.ostrom.server.kernel.http.Readiness
import dev.ostrom.server.kernel.http.SERVER_VERSION
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.kernelModule
import dev.ostrom.server.kernel.module.serverKoin
import dev.ostrom.server.kernel.observability.OstromMetrics
import dev.ostrom.server.kernel.observability.ProcessMetrics
import dev.ostrom.server.kernel.observability.TelemetryLogger
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.system.exitProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import platform.posix.SIGTERM

private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
private const val STOP_GRACE_MILLIS = 5_000L
private const val STOP_TIMEOUT_MILLIS = 15_000L

/**
 * The `ostrom-server` binary (commands in [parseCommand], configuration keys in docs/MODULES.md). Exits with 2 on an
 * invalid command line or configuration and 1 on any other failure (database unreachable, unsafe database role,
 * migrations not applied, unreadable key), after one structured log line; 0 after a graceful stop.
 *
 * @param args The command line.
 */
fun main(args: Array<String>) {
    configureHttpClients()
    val command = try {
        parseCommand(args.toList())
    } catch (error: UsageException) {
        usage(error)
    }
    when (command) {
        Command.Serve -> serve()
        Command.Migrate -> migrate()
        is Command.SeedTestAccount -> seed(command.email)
    }
}

/**
 * Serves until SIGTERM or SIGINT, then drains: `/ready` answers 503 for [ServerConfig.drainDelay] while requests are
 * still served, then the engine stops, letting requests in flight finish.
 */
@OptIn(ExperimentalForeignApi::class)
private fun serve() {
    val (source, config) = loadConfig()
    // Telemetry has its own scope: it is flushed after the workers stop, then cancelled.
    val exporters = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val telemetry = startTelemetry(config, exporters)
    val logger = TelemetryLogger(JsonLogger(config.logLevel), telemetry, config.logLevel)
    val metrics = OstromMetrics(telemetry.meter)
    ProcessMetrics.register(telemetry.meter)
    if (source["OSTROM_DATABASE_MIGRATION_URL"] != null) {
        logger.warn("the serving process received the migration credentials: give them to `migrate` only")
    }
    val modules = serverModules()
    var driver: Driver? = null
    var koin: Koin? = null
    // On Kotlin/Native an uncaught coroutine exception ends the process: a failing background job is logged instead,
    // with its class only (its message may quote a provider error), and the other jobs keep running.
    val failures = CoroutineExceptionHandler { _, error ->
        logger.error("background job failed", mapOf("error" to error::class.simpleName))
    }
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)
    try {
        val database = connectPostgres(config.database).also { driver = it }
        runBlocking { database.requireRowLevelSecurity() }
        val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
        val kernel = kernelModule(logger, database, signer, source, telemetry = telemetry, metrics = metrics)
        val server = serverKoin(kernel, modules).also { koin = it }
        runBlocking {
            val migrations = server.get<MigrationRunner>()
            modules.forEach { migrations.verify(it.name, it.migrations) }
            modules.forEach { it.initialize(server) }
        }
        modules.forEach { it.start(workers, server) }
        logger.info(
            "server starting",
            mapOf(
                "version" to SERVER_VERSION,
                "host" to config.host,
                "port" to config.port,
                "key_id" to signer.keyId,
                // What the collector was left with (the pause is Kotlin/Native's default): the image's smoke test
                // refuses a bounded heap.
                "gc_pause" to GC.pauseOnTargetHeapOverflow,
                "gc_heap_bounded" to (GC.maxHeapBytes != Long.MAX_VALUE),
            ),
        )
        val engine = serverEngine(config.port, config.host) { configureHttp(server, modules) }.start(wait = false)
        // After the start: Ktor's POSIX shutdown hook calls signal() as the application starts and would replace these
        // handlers. From now on SIGTERM and SIGINT only write to the signal pipe; before, they end a start at once.
        check(ostrom_install_signal_handlers() == 0) { "The SIGTERM and SIGINT handlers could not be installed." }
        val signal = ostrom_await_signal()
        val name = if (signal == SIGTERM) "SIGTERM" else "SIGINT"
        logger.info("server draining", mapOf("signal" to name, "delay_s" to config.drainDelay.inWholeSeconds))
        server.get<Readiness>().drain()
        runBlocking { delay(config.drainDelay) }
        engine.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
        logger.info("server stopped")
    } catch (error: Throwable) { // not coroutine-reachable: the process entry point, outside any coroutine.
        failed(logger, "server stopped on a failure", error)
        stopThenFlush(telemetry, exporters) { shutDown(workers, koin, driver) }
        exitProcess(EXIT_FAILURE)
    }
    stopThenFlush(telemetry, exporters) { shutDown(workers, koin, driver) }
}

/** Applies every module's migrations under the migration lock, with the owner's connection, then exits. */
private fun migrate() {
    val source = loadSource()
    val logger = JsonLogger(LogLevel.INFO)
    val database = try {
        ServerConfig.migrationDatabase(source)
    } catch (error: InvalidConfigException) {
        invalidConfig(error)
    }
    var driver: Driver? = null
    try {
        val owner = connectPostgres(database).also { driver = it }
        runBlocking { owner.migrateUnderLock(MigrationRunnerImpl(owner, logger), serverModules()) }
        logger.info("migrations done", mapOf("version" to SERVER_VERSION))
    } catch (error: Throwable) { // not coroutine-reachable: the process entry point, outside any coroutine.
        failed(logger, "migration failed", error)
        runBlocking { driver?.close() }
        exitProcess(EXIT_FAILURE)
    }
    runBlocking { driver?.close() }
}

/** Creates a QA account and prints it as one JSON line on stdout. */
private fun seed(email: String) {
    val (source, config) = loadConfig()
    try {
        requireTestAccountsAllowed(source, email)
    } catch (error: UsageException) {
        usage(error)
    }
    val logger = JsonLogger(config.logLevel)
    val modules = serverModules()
    var driver: Driver? = null
    var koin: Koin? = null
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val database = connectPostgres(config.database).also { driver = it }
        val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
        val server = serverKoin(kernelModule(logger, database, signer, source), modules).also { koin = it }
        val account = runBlocking {
            val migrations = server.get<MigrationRunner>()
            modules.forEach { migrations.verify(it.name, it.migrations) }
            modules.forEach { it.initialize(server) }
            seedTestAccount(server, source, email)
        }
        println(account)
    } catch (error: UsageException) {
        shutDown(workers, koin, driver)
        usage(error)
    } catch (error: Throwable) { // not coroutine-reachable: the process entry point, outside any coroutine.
        failed(logger, "test account not created", error)
        shutDown(workers, koin, driver)
        exitProcess(EXIT_FAILURE)
    }
    shutDown(workers, koin, driver)
}

private fun loadSource(): ConfigSource = try {
    ServerConfig.source()
} catch (error: InvalidConfigException) {
    invalidConfig(error)
}

private fun loadConfig(): Pair<ConfigSource, ServerConfig> {
    val source = loadSource()
    return try {
        source to ServerConfig.from(source)
    } catch (error: InvalidConfigException) {
        invalidConfig(error)
    }
}

private fun invalidConfig(error: InvalidConfigException): Nothing {
    JsonLogger(LogLevel.ERROR).error("invalid configuration", mapOf("reason" to error.message))
    exitProcess(EXIT_USAGE)
}

private fun usage(error: UsageException): Nothing {
    JsonLogger(LogLevel.ERROR).error("invalid command", mapOf("reason" to error.message))
    exitProcess(EXIT_USAGE)
}

/**
 * Logs a failure that ends the process. Only the server's own refusals (an unsafe role, migrations not applied) keep
 * their message, which says what to do; any other error is logged by class, since its message may quote a database
 * or provider error.
 */
private fun failed(logger: Logger, message: String, error: Throwable) {
    val reason = when (error) {
        is UnsafeDatabaseRoleException, is MigrationsNotAppliedException -> error.message
        else -> null
    }
    logger.error(message, mapOf("error" to error::class.simpleName, "reason" to reason))
}

private fun shutDown(workers: CoroutineScope, koin: Koin?, driver: Driver?) {
    workers.cancel()
    koin?.close()
    runBlocking { driver?.close() }
}
