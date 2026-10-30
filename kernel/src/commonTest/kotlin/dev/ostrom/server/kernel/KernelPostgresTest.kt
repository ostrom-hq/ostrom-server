package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.http.DatabaseHealthCheck
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.kernelModule
import dev.ostrom.server.kernel.module.serverKoin
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.qualifier.named
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

class KernelPostgresTest {
    private val lines = mutableListOf<String>()

    // On the real clock: runTest's virtual time would fire the health check's timeout before the database answers.
    private fun databaseTest(block: suspend (TestDatabase, MigrationRunner) -> Unit) = runTest {
        withContext(Dispatchers.Default) { runDatabaseTest(block) }
    }

    private suspend fun runDatabaseTest(block: suspend (TestDatabase, MigrationRunner) -> Unit) {
        val database = TestDatabase.create() ?: return
        try {
            val logger = JsonLogger(LogLevel.INFO, sink = { lines += it })
            val signer = Ed25519InstanceSigner.generate()
            val koin = serverKoin(kernelModule(logger, database.driver, signer), emptyList())
            assertSame(logger, koin.get<Logger>())
            assertSame(database.driver, koin.get<Driver>())
            assertSame(signer, koin.get<InstanceSigner>())
            assertSame(Clock.System, koin.get<Clock>())
            assertTrue(koin.get<HealthCheck>(named("database")).isUp())
            block(database, koin.get())
            koin.close()
        } finally {
            database.close()
        }
    }

    private suspend fun Driver.count(table: String): Long =
        fetchAll(Statement.create("SELECT count(*) AS n FROM $table")).getOrThrow().single().get("n").asLong()

    private val first = listOf(SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id BIGINT PRIMARY KEY);"))

    @Test
    fun appliesAModulesMigrationsOnceAndChecksThemAtEveryStart() = databaseTest { database, runner ->
        runner.migrate("probe", first)
        runner.migrate("probe", first)
        assertEquals(1, database.driver.count("_migrations_probe"))
        assertEquals(1, lines.count { "\"msg\":\"migration applied\"" in it }, "Applied once, checked twice")

        val second = first + SchemaMigration(2, "add_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")
        runner.migrate("probe", second)
        assertEquals(2, database.driver.count("_migrations_probe"))
    }

    @Test
    fun anEditedMigrationFailsTheStart() = databaseTest { _, runner ->
        runner.migrate("probe", first)
        val edited = listOf(SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id TEXT PRIMARY KEY);"))
        assertFails { runner.migrate("probe", edited) }
    }

    @Test
    fun eachModuleHasItsOwnHistory() = databaseTest { database, runner ->
        runner.migrate("probe", first)
        runner.migrate(
            "other-probe",
            listOf(SchemaMigration(1, "create_other", "CREATE TABLE other_probe_items (id BIGINT PRIMARY KEY);")),
        )
        assertEquals(1, database.driver.count("_migrations_probe"))
        assertEquals(1, database.driver.count("_migrations_other_probe"))
    }

    @Test
    fun aModuleWithoutMigrationsCreatesNoTable() = databaseTest { database, runner ->
        runner.migrate("empty", emptyList())
        val tables = database.driver.fetchAll(
            Statement.create("SELECT count(*) AS n FROM information_schema.tables WHERE table_name = :name")
                .bind("name", "_migrations_empty"),
        ).getOrThrow().single().get("n").asLong()
        assertEquals(0, tables)
    }

    @Test
    fun rejectsAModuleNameThatCouldCollide() = databaseTest { _, runner ->
        assertFailsWith<IllegalArgumentException> { runner.migrate("other_probe", first) }
        assertFailsWith<IllegalArgumentException> { runner.migrate("Probe", first) }
    }

    @Test
    fun aDatabaseThatDoesNotAnswerInTimeIsDown() = databaseTest { database, _ ->
        assertFalse(DatabaseHealthCheck(database.driver, timeout = Duration.ZERO).isUp())
    }
}
