// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.module.kernelModule
import dev.ostrom.server.kernel.module.serverKoin
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.github.smyrgeorge.sqlx4k.Driver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.core.Koin
import org.koin.core.module.Module

/**
 * What a server test can use.
 *
 * @property koin The test server's Koin, to reach a repository or use case directly.
 * @property driver The test database.
 * @property client An HTTP client to the test server that reads and writes JSON.
 */
class TestServer(
    val koin: Koin,
    val driver: Driver,
    val client: HttpClient,
)

/**
 * Runs a test against a real server: a fresh PostgreSQL database (see [TestDatabase]), the kernel, the given
 * modules with their migrations applied, and their routes. Skipped when no test database is configured.
 *
 * @param modules The modules under test, in dependency order.
 * @param overrides Koin definitions replacing real ones (a fake clock, a fake token authenticator).
 * @param block The test.
 */
fun serverTest(
    modules: List<ServerModule>,
    overrides: Module? = null,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) = serverTest(modules, overrides, EnvironmentConfigSource(), block)

/**
 * [serverTest] with the test database configuration read from [environment] (the skipped case is tested so).
 */
internal fun serverTest(
    modules: List<ServerModule>,
    overrides: Module?,
    environment: ConfigSource,
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) = testApplication {
    val database = TestDatabase.create(environment) ?: return@testApplication
    try {
        val signer = Ed25519InstanceSigner.generate()
        val kernel = kernelModule(JsonLogger(LogLevel.WARN), database.driver, signer, environment)
        val koin = serverKoin(kernel, modules, overrides)
        try {
            val migrations = koin.get<MigrationRunner>()
            modules.forEach { migrations.migrate(it.name, it.migrations) }
            modules.forEach { it.initialize(koin) }
            application { configureHttp(koin, modules) }
            // Started now, not lazily at the first request: a test that never calls the client would otherwise start
            // the application after the finally below has closed Koin.
            startApplication()
            val client = createClient { install(ContentNegotiation) { json(OstromJson) } }
            block(TestServer(koin, database.driver, client))
        } finally {
            koin.close()
        }
    } finally {
        database.close()
    }
}
