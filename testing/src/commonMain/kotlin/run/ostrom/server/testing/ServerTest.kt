// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.testing

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.config.LayeredConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.database.MigrationRunner
import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.database.databaseNotifications
import run.ostrom.server.kernel.database.migrateUnderLock
import run.ostrom.server.kernel.database.requireRowLevelSecurity
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.module.kernelModule
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import io.ktor.test.dispatcher.runTestWithRealTime
import org.koin.core.module.Module
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
 * @param block The test.
 */
fun serverTest(
    modules: List<ServerModule>,
    overrides: Module? = null,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) = serverTest(modules, overrides, EnvironmentConfigSource(), block = block)

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
) = serverTest(modules, overrides, EnvironmentConfigSource(), BENCH_TIMEOUT, block)

/**
 * [serverTest] with the test database configuration read from [environment] (the skipped case is tested so), run as
 * Ktor's `testApplication` runs it, within [timeout] (a read bench fills its tables first, ReadBench.kt).
 */
internal fun serverTest(
    modules: List<ServerModule>,
    overrides: Module?,
    environment: ConfigSource,
    timeout: Duration = TEST_TIMEOUT,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) = runTestWithRealTime(EmptyCoroutineContext, timeout) {
    runTestApplication { serve(modules, overrides, environment, block) }
}

private suspend fun ApplicationTestBuilder.serve(
    modules: List<ServerModule>,
    overrides: Module?,
    environment: ConfigSource,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) {
    val database = TestDatabase.create(environment) ?: return
    try {
        val signer = Ed25519InstanceSigner.generate()
        val logger = JsonLogger(LogLevel.WARN)
        database.owner.migrateUnderLock(MigrationRunnerImpl(database.owner, logger), modules)
        database.driver.requireRowLevelSecurity()
        // The server's pool records what it sends, for the read benches (ReadBench.kt).
        val driver = RecordingDriver(database.driver)
        // Tests sign people up as an open instance does (`OSTROM_SIGNUP` of ostrom.run); the invite-only default of a
        // self-hosted instance has its own tests, which override the identity configuration.
        val source = LayeredConfigSource(listOf(environment, MapConfigSource(mapOf("OSTROM_SIGNUP" to "open"))))
        // The database's notifications need the pool itself, not its recording wrapper.
        val notifications = databaseNotifications(database.driver)
        val kernel = kernelModule(logger, driver, signer, source, notifications = notifications)
        val koin = serverKoin(kernel, modules, overrides)
        try {
            val migrations = koin.get<MigrationRunner>()
            modules.forEach { migrations.verify(it.name, it.migrations) }
            modules.forEach { it.initialize(koin) }
            // Every answer is checked against ostrom-core main's contract when CI gives it (ContractObserver).
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
    } finally {
        database.close()
    }
}
