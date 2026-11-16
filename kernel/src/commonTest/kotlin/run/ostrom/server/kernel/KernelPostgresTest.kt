// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.database.DatabaseNotifications
import run.ostrom.server.kernel.database.InProcessNotifications
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.MigrationRunner
import run.ostrom.server.kernel.database.MigrationsNotAppliedException
import run.ostrom.server.kernel.database.PostgresNotifications
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.http.HealthCheck
import run.ostrom.server.kernel.http.Readiness
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.kernelModule
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.kernel.network.TrustedProxies
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.kernel.signing.InstanceSigner
import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.qualifier.named
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The kernel's Koin module on a real database, as the server starts it: the serving role's pool and its checks. */
class KernelPostgresTest {
    private val logger = JsonLogger(LogLevel.INFO, sink = {})
    private val first = listOf(SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id BIGINT PRIMARY KEY);"))

    // On the real clock: the pool answers `/ready` from its state, and the database really is reached.
    private fun databaseTest(block: suspend (TestDatabase, JdbcDatabase) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val database = TestDatabase.create() ?: return@withContext
            try {
                JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE).use { block(database, it) }
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun bindsThePoolAndTheDefaultsItIsGiven() = databaseTest { _, jdbc ->
        val signer = Ed25519InstanceSigner.generate()
        val config = MapConfigSource(mapOf("OSTROM_PROBE" to "on"))
        val koin = serverKoin(kernelModule(logger, jdbc, signer, config), emptyList())
        try {
            assertSame(config, koin.get<ConfigSource>())
            assertSame(logger, koin.get<Logger>())
            assertSame(jdbc, koin.get<JdbcDatabase>())
            assertSame(signer, koin.get<InstanceSigner>())
            assertSame(Clock.System, koin.get<Clock>())
            assertSame(TrustedProxies.NONE, koin.get<TrustedProxies>())
            assertFalse(koin.get<Readiness>().isDraining)
            assertTrue(koin.get<HealthCheck>(named("database")).isUp())
            assertIs<PostgresNotifications>(koin.get<DatabaseNotifications>())
        } finally {
            koin.close()
        }
    }

    @Test
    fun theMigrationRunnerReadsTheHistoryThroughThePool() = databaseTest { database, jdbc ->
        val koin = serverKoin(
            kernelModule(logger, jdbc, Ed25519InstanceSigner.generate(), MapConfigSource(emptyMap())),
            emptyList(),
        )
        try {
            val runner = koin.get<MigrationRunner>()
            assertFailsWith<MigrationsNotAppliedException> { runner.verify("probe", first) }
            database.migrationRunner(logger).migrate("probe", first)
            runner.verify("probe", first)
        } finally {
            koin.close()
        }
    }

    @Test
    fun takesTheNotificationsAndTheMigrationRunnerItIsGiven() = databaseTest { _, jdbc ->
        val notifications = InProcessNotifications()
        val runner = jdbc.migrationRunner(logger)
        val kernel = kernelModule(
            logger,
            jdbc,
            Ed25519InstanceSigner.generate(),
            MapConfigSource(emptyMap()),
            notifications = notifications,
            migrationRunner = runner,
        )
        val koin = serverKoin(kernel, emptyList())
        try {
            assertSame(notifications, koin.get<DatabaseNotifications>())
            assertSame(runner, koin.get<MigrationRunner>())
        } finally {
            koin.close()
        }
    }
}
