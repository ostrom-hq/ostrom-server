package dev.ostrom.server.testing

import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.kernel.http.configureHttp
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.module.kernelModule
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import io.github.smyrgeorge.sqlx4k.Driver
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication

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
) = testApplication {
    val database = TestDatabase.create() ?: return@testApplication
    val signer = Ed25519InstanceSigner.generate()
    val koin = koinApplication {
        allowOverride(true)
        modules(
            listOf(kernelModule(JsonLogger(LogLevel.WARN), database.driver, signer)) +
                modules.map { it.koinModule } + listOfNotNull(overrides),
        )
    }.koin
    try {
        val migrations = koin.get<MigrationRunner>()
        modules.forEach { migrations.migrate(it.name, it.migrations) }
        modules.forEach { it.initialize(koin) }
        application { configureHttp(koin, modules) }
        val client = createClient { install(ContentNegotiation) { json(OstromJson) } }
        block(TestServer(koin, database.driver, client))
    } finally {
        koin.close()
        database.close()
    }
}
