// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.config.LayeredConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.MigrationRunner
import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.database.databaseNotifications
import run.ostrom.server.kernel.database.migrateModules
import run.ostrom.server.kernel.database.migrateUnderLock
import run.ostrom.server.kernel.database.requireRowLevelSecurity
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.module.kernelModule
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import io.ktor.test.dispatcher.runTestWithRealTime
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How long a test may run: Ktor's `testApplication` gives it a minute. */
private val TEST_TIMEOUT = 1.minutes

/** How long a read bench may run, its tables filled: a minute is too short on a CI runner of a few cores. */
private val BENCH_TIMEOUT = 5.minutes

/**
 * Runs a test against a real server: a fresh PostgreSQL database (see [TestDatabase]), the kernel, the given
 * modules with their migrations applied, and their routes. Skipped when no test database is configured.
 *
 * Started as a deployment starts: the owner applies the migrations (`ostrom-server migrate`), then the server connects
 * as the serving role, which must pass the start check (no row-level security bypass), verifies the migrations and
 * initializes the modules. Every request and every repository of the test runs as that role.
 *
 * Every JSON answer of an operation the ostrom-core contract marks `served` is checked against it when CI gives the
 * contract (`OSTROM_CONTRACT_OPENAPI`): the test fails on a drift [KnownDrifts] does not list ([ContractObserver]).
 *
 * @param modules The modules under test, in dependency order.
 * @param overrides Koin definitions replacing real ones (a fake clock, a fake token authenticator).
 * @param migrations What applies and checks the migrations: sqlx4k until every module's tests move to Flyway.
 * @param block The test.
 */
fun serverTest(
    modules: List<ServerModule>,
    overrides: Module? = null,
    migrations: TestMigrations = TestMigrations.SQLX4K,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) = serverTest(modules, overrides, EnvironmentConfigSource(), migrations = migrations, block = block)

/** What a test server applies and checks the migrations with. */
enum class TestMigrations {
    /** sqlx4k's runner and its `_migrations_<module>` histories, the modules' tests until they move to Flyway. */
    SQLX4K,

    /** Flyway and its `flyway_<module>` histories, as `ostrom-server migrate` and the server's start. */
    FLYWAY,
}

/**
 * [serverTest] for a read bench (ReadBench.kt): the same server, within [BENCH_TIMEOUT].
 *
 * @param modules The modules under test, in dependency order.
 * @param overrides Koin definitions replacing real ones.
 * @param block The bench.
 */
fun benchTest(
    modules: List<ServerModule>,
    overrides: Module?,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) = serverTest(modules, overrides, EnvironmentConfigSource(), BENCH_TIMEOUT, block = block)

/**
 * [serverTest] with the test database configuration read from [environment] (the skipped case is tested so), run as
 * Ktor's `testApplication` runs it, within [timeout] (a read bench fills its tables first, ReadBench.kt).
 */
@Suppress("LongParameterList") // The public serverTest's parameters, and what its own tests set: the environment.
internal fun serverTest(
    modules: List<ServerModule>,
    overrides: Module?,
    environment: ConfigSource,
    timeout: Duration = TEST_TIMEOUT,
    migrations: TestMigrations = TestMigrations.SQLX4K,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) = runTestWithRealTime(EmptyCoroutineContext, timeout) {
    runTestApplication { serve(modules, overrides, environment, migrations, block) }
}

private suspend fun ApplicationTestBuilder.serve(
    modules: List<ServerModule>,
    overrides: Module?,
    environment: ConfigSource,
    migrations: TestMigrations,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) {
    val database = TestDatabase.create(environment) ?: return
    try {
        val signer = Ed25519InstanceSigner.generate()
        val logger = JsonLogger(LogLevel.WARN)
        when (migrations) {
            TestMigrations.SQLX4K ->
                database.owner.migrateUnderLock(MigrationRunnerImpl(database.owner, logger), modules)
            TestMigrations.FLYWAY -> migrateModules(database.ownerConfig, logger, modules)
        }
        database.driver.requireRowLevelSecurity()
        // The server's pool records what it sends, for the read benches (ReadBench.kt).
        val driver = RecordingDriver(database.driver)
        // Tests sign people up as an open instance does (ostrom.run): with the admin module, its former variable is
        // imported into the console's settings at start; without it, the settings say so (testKernel). The
        // invite-only default of a self-hosted instance has its own tests, which override the instance's settings.
        val source = LayeredConfigSource(listOf(environment, MapConfigSource(mapOf("OSTROM_SIGNUP" to "open"))))
        // The database's notifications need the pool itself, not its recording wrapper.
        val notifications = databaseNotifications(database.driver)
        // The JDBC pool of the modules on Exposed, connected as the serving role as the server's is.
        val jdbc = JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE)
        try {
            val kernel = testKernel(
                kernelModule(
                    logger,
                    driver,
                    signer,
                    source,
                    notifications = notifications,
                    // The server's start check reads Flyway's histories through the pool; sqlx4k's on the driver.
                    migrationRunner = if (migrations == TestMigrations.FLYWAY) jdbc.migrationRunner(logger) else null,
                    jdbc = jdbc,
                ),
                modules,
            )
            serveWith(serverKoin(kernel, modules, overrides), modules, driver, database, environment, block)
        } finally {
            jdbc.close()
        }
    } finally {
        database.close()
    }
}

/** Verifies the migrations, initializes the modules, starts the application on [koin] and runs [block]. */
@Suppress("LongParameterList") // What the test server runs on: its Koin, modules, pool, database and configuration.
private suspend fun ApplicationTestBuilder.serveWith(
    koin: Koin,
    modules: List<ServerModule>,
    driver: RecordingDriver,
    database: TestDatabase,
    environment: ConfigSource,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) {
    try {
        val migrations = koin.get<MigrationRunner>()
        modules.forEach { migrations.verify(it.name, it.migrations) }
        modules.forEach { it.initialize(koin) }
        // Every answer is checked against the pinned ostrom-core's contract when CI gives it (ContractObserver).
        val observer = ContractObserver(Contract.from(environment))
        application {
            install(observer.plugin)
            configureHttp(koin, modules)
        }
        // Started now, not lazily at the first request: a test that never calls the client would otherwise start
        // the application after the finally below has closed Koin.
        startApplication()
        val client = createClient { install(ContentNegotiation) { json(OstromJson) } }
        block(TestServer(koin, driver, client, database))
        observer.finish()
    } finally {
        koin.close()
    }
}

/**
 * The kernel of a test server: without the admin module, it adds the instance's settings of an open instance
 * ([TestInstanceSettings.openSignUp]), as the admin module stores them on ostrom.run.
 *
 * @param kernel The kernel's definitions.
 * @param modules The modules under test.
 *
 * @return The definitions.
 */
internal fun testKernel(kernel: Module, modules: List<ServerModule>): Module =
    if (modules.any { it.name == ADMIN_MODULE }) {
        kernel
    } else {
        module {
            includes(kernel)
            single<InstanceSettings> { TestInstanceSettings.openSignUp() }
        }
    }

/** The name of the module that stores the instance's settings. */
private const val ADMIN_MODULE = "admin"
