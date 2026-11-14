// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.kernel.SERVER_VERSION
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.ServerConfig
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.MigrationRunner
import run.ostrom.server.kernel.database.MigrationsNotAppliedException
import run.ostrom.server.kernel.database.PoolHealthCheck
import run.ostrom.server.kernel.database.UnsafeDatabaseRoleException
import run.ostrom.server.kernel.database.connectPostgres
import run.ostrom.server.kernel.database.migrateModules
import run.ostrom.server.kernel.database.requireRowLevelSecurity
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.kernelModule
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.kernel.observability.OstromMetrics
import run.ostrom.server.kernel.observability.ProcessMetrics
import run.ostrom.server.kernel.observability.TelemetryLogger
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.github.smyrgeorge.sqlx4k.Driver
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin

private const val EXIT_SUCCESS = 0
private const val EXIT_FAILURE = 1
private const val EXIT_USAGE = 2
private const val STOP_GRACE_MILLIS = 5_000L
private const val STOP_TIMEOUT_MILLIS = 15_000L

/**
 * The `ostrom-server` process on the JVM (docs/decisions/0001; commands in [parseCommand], configuration keys in
 * docs/MODULES.md), started by the image's `/usr/local/bin/ostrom-server`. Exits with 2 on an invalid command line or
 * configuration and 1 on any other failure (database unreachable, unsafe database role, migrations not applied,
 * unreadable key), after one structured log line; 0 after a graceful stop or a command done.
 *
 * @param args The command line.
 */
fun main(args: Array<String>) {
    val command = try {
        parseCommand(args.toList())
    } catch (error: UsageException) {
        usage(error)
    }
    when (command) {
        Command.Serve -> serve()
        Command.Migrate -> migrate()
        // A QA account, as one JSON line.
        is Command.SeedTestAccount -> oneShot(
            "test account not created",
            allowed = { requireTestAccountsAllowed(it, command.email) },
        ) { koin, source -> seedTestAccount(koin, source, command.email) }
        // The first owner's one-time setup link.
        Command.Bootstrap -> oneShot("setup link not made") { koin, _ -> bootstrapInstance(koin) }
    }
    // Every resource is closed by now: a thread a library left running must not keep the process alive.
    exitProcess(EXIT_SUCCESS)
}

/**
 * Serves until SIGTERM or SIGINT, then drains (audit SD-09): `/ready` answers 503 for [ServerConfig.drainDelay]
 * while requests are still served, then the server waits up to [ServerConfig.drainGrace] for the calls in flight (a
 * model's stream, a gate's wait) to end, then the engine stops and cuts the live streams their clients reopen.
 */
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
    var jdbc: JdbcDatabase? = null
    // A failing background job is logged with its class only (its message may quote a provider error), and the other
    // jobs keep running.
    val failures = CoroutineExceptionHandler { _, error ->
        logger.error("background job failed", mapOf("error" to error::class.simpleName))
    }
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default + failures)
    try {
        val database = connectPostgres(config.database).also { driver = it }
        runBlocking { database.requireRowLevelSecurity() }
        // The JVM pool (docs/decisions/0001, step 2): `/ready` reads its state, and the modules on Exposed query it.
        val pool = JdbcDatabase(config.database, JdbcDatabase.MIN_POOL_SIZE).also { jdbc = it }
        val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
        val kernel = kernelModule(
            logger,
            database,
            signer,
            source,
            telemetry = telemetry,
            metrics = metrics,
            databaseCheck = PoolHealthCheck(pool::state),
            migrationRunner = pool.migrationRunner(logger),
            jdbc = pool,
        )
        val server = serverKoin(kernel, modules).also { koin = it }
        runBlocking {
            val migrations = server.get<MigrationRunner>()
            modules.forEach { migrations.verify(it.name, it.migrations) }
            modules.forEach { it.initialize(server) }
        }
        modules.forEach { it.start(workers, server) }
        logStart(logger, config, signer.keyId)
        val engine = serverEngine(config.port, config.host) { configureHttp(server, modules) }.start(wait = false)
        // From now on SIGTERM and SIGINT start the drain; before, they end a start at once (the JVM's own handling).
        drain(awaitStopSignal(), config, server, logger)
        engine.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
        logger.info("server stopped")
    } catch (error: Throwable) { // not coroutine-reachable: the process entry point, outside any coroutine.
        failed(logger, "server stopped on a failure", error)
        stopThenFlush(telemetry, exporters) { shutDown(workers, koin, driver, jdbc) }
        exitProcess(EXIT_FAILURE)
    }
    stopThenFlush(telemetry, exporters) { shutDown(workers, koin, driver, jdbc) }
}

/** Applies every module's migrations under the migration lock, with the owner's credentials (Flyway), then exits. */
private fun migrate() {
    val source = loadSource()
    val logger = JsonLogger(LogLevel.INFO)
    val database = try {
        ServerConfig.migrationDatabase(source)
    } catch (error: InvalidConfigException) {
        invalidConfig(error)
    }
    try {
        runBlocking { migrateModules(database, logger, serverModules()) }
        logger.info("migrations done", mapOf("version" to SERVER_VERSION))
    } catch (error: Throwable) { // not coroutine-reachable: the process entry point, outside any coroutine.
        failed(logger, "migration failed", error)
        exitProcess(EXIT_FAILURE)
    }
}

/**
 * Runs one command against the database and prints what it returns on stdout (never in a log): a refusal exits 2 with
 * its usage, a failure 1.
 *
 * @param failure What the log says when it fails.
 * @param allowed Refuses the command (a [UsageException]) before anything is touched.
 * @param work The command, given the server's Koin and configuration once every module's migrations are checked.
 */
private fun oneShot(
    failure: String,
    allowed: (ConfigSource) -> Unit = {},
    work: suspend (Koin, ConfigSource) -> String,
) {
    val (source, config) = loadConfig()
    try {
        allowed(source)
    } catch (error: UsageException) {
        usage(error)
    }
    val logger = JsonLogger(config.logLevel)
    val modules = serverModules()
    var driver: Driver? = null
    var koin: Koin? = null
    var jdbc: JdbcDatabase? = null
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val database = connectPostgres(config.database).also { driver = it }
        val pool = JdbcDatabase(config.database, JdbcDatabase.MIN_POOL_SIZE).also { jdbc = it }
        val signer = runBlocking { Ed25519InstanceSigner.loadOrCreate(config.signingKeyFile) }
        val kernel =
            kernelModule(logger, database, signer, source, migrationRunner = pool.migrationRunner(logger), jdbc = pool)
        val server = serverKoin(kernel, modules).also { koin = it }
        val text = runBlocking {
            val migrations = server.get<MigrationRunner>()
            modules.forEach { migrations.verify(it.name, it.migrations) }
            modules.forEach { it.initialize(server) }
            work(server, source)
        }
        println(text)
    } catch (error: UsageException) {
        shutDown(workers, koin, driver, jdbc)
        usage(error)
    } catch (error: Throwable) { // not coroutine-reachable: the process entry point, outside any coroutine.
        failed(logger, failure, error)
        shutDown(workers, koin, driver, jdbc)
        exitProcess(EXIT_FAILURE)
    }
    shutDown(workers, koin, driver, jdbc)
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

// The JDBC pool is closed even when a module's stop fails, so that its threads never hold the JVM up.
private fun shutDown(workers: CoroutineScope, koin: Koin?, driver: Driver?, jdbc: JdbcDatabase?) {
    try {
        workers.cancel()
        koin?.close()
        runBlocking { driver?.close() }
    } finally {
        jdbc?.close()
    }
}
