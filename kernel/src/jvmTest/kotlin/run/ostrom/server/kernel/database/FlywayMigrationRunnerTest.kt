// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.flywaydb.core.api.FlywayException
import org.koin.core.module.Module
import org.koin.dsl.module

class FlywayMigrationRunnerTest {
    private val lines = mutableListOf<String>()
    private val logger = JsonLogger(LogLevel.INFO, sink = { synchronized(lines) { lines += it } })

    private val first = listOf(SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id BIGINT PRIMARY KEY);"))
    private val second = first + SchemaMigration(2, "add_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")
    private val third = second + SchemaMigration(3, "add_note", "ALTER TABLE probe_items ADD COLUMN note TEXT;")

    // On the real clock: the lock tests wait on PostgreSQL's lock_timeout, and a pass waits on its pause.
    private fun databaseTest(block: suspend (TestDatabase) -> Unit) {
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                val database = TestDatabase.create() ?: return@withContext
                try {
                    block(database)
                } finally {
                    database.close()
                }
            }
        }
    }

    private fun TestDatabase.runner(pause: Duration = 1.seconds, attempts: Int = 2) =
        FlywayMigrationRunner(migrationSource(ownerConfig), logger, pause, attempts)

    private suspend fun TestDatabase.count(table: String): Long =
        owner.fetchAll(Statement.create("SELECT count(*) AS n FROM $table")).getOrThrow().single().get("n").asLong()

    private fun applied(): Int = synchronized(lines) { lines.count { "\"msg\":\"migration applied\"" in it } }

    private fun waits(): Int = synchronized(lines) { lines.count { "waited too long for a lock" in it } }

    @Test
    fun appliesEachModulesMigrationsOnceAndTheServingRoleChecksThem() {
        databaseTest { database ->
            val modules = listOf(Probe("probe", second), Probe("other-probe", first.map { it.renamed("other") }))
            migrateModules(database.ownerConfig, logger, modules)
            migrateModules(database.ownerConfig, logger, modules)
            assertEquals(3, applied(), "Applied once, checked twice")
            assertEquals(2, database.count("flyway_probe WHERE success"))
            JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE).use { pool ->
                val serving = pool.migrationRunner(logger)
                modules.forEach { serving.verify(it.name, it.migrations) }
                val missing = assertFailsWith<MigrationsNotAppliedException> { serving.verify("probe", third) }
                assertEquals(
                    "Migrations [3] of module probe are not applied, or differ from the applied ones: run " +
                        "`ostrom-server migrate`.",
                    missing.message,
                )
                val edited = listOf(first.single().copy(sql = "CREATE TABLE probe_items (id TEXT PRIMARY KEY);"))
                assertFailsWith<MigrationsNotAppliedException> { serving.verify("probe", edited) }
                assertFailsWith<MigrationsNotAppliedException> { serving.verify("absent", first) }
                serving.verify("empty", emptyList())
            }
        }
    }

    // AUDIT-RLS R8, as for sqlx4k's history: the serving role reads Flyway's, and the older one, and never writes them.
    @Test
    fun onlyTheOwnerWritesTheHistory() {
        databaseTest { database ->
            val runner = database.runner()
            runner.migrate("probe", first)
            assertFalse(database.canWrite(database.servingRole, "flyway_probe"), "Serving role writes the history")
            database.superuser.execute("GRANT ALL ON flyway_probe TO ${database.servingRole}").getOrThrow()
            runner.migrate("probe", first)
            assertFalse(database.canWrite(database.servingRole, "flyway_probe"), "Write not revoked again")
            assertTrue(database.canWrite(database.servingRole, "probe_items"), "Module tables keep their grants")
            database.owner.execute("CREATE TABLE _migrations_probe (version BIGINT, checksum TEXT)").getOrThrow()
            database.superuser.execute("GRANT ALL ON _migrations_probe TO ${database.servingRole}").getOrThrow()
            runner.migrate("probe", first)
            assertFalse(database.canWrite(database.servingRole, "_migrations_probe"), "The older history is writable")
        }
    }

    @Test
    fun adoptsWhatSqlx4kAppliedWithoutRunningItAgain() {
        databaseTest { database ->
            MigrationRunnerImpl(database.owner, logger).migrate("probe", second)
            val before = applied()
            val runner = database.runner()
            runner.migrate("probe", third)
            assertEquals(1, applied() - before, "Only version 3 runs")
            assertEquals(1, database.count("flyway_probe WHERE type = 'BASELINE' AND version = '2'"))
            runner.verify("probe", third)
            // The old history stays, for a replica of the previous version during a rolling update.
            assertEquals(2, database.count("_migrations_probe"))
            // Adopted once: the next pass reads Flyway's history only.
            runner.migrate("probe", third)
            assertEquals(1, applied() - before)
        }
    }

    @Test
    fun refusesToAdoptAHistoryTheCodeNoLongerMatches() {
        databaseTest { database ->
            MigrationRunnerImpl(database.owner, logger).migrate("probe", second)
            val edited = listOf(first.single().copy(sql = "CREATE TABLE probe_items (id TEXT PRIMARY KEY);")) +
                second.drop(1)
            val runner = database.runner()
            val refused = assertFailsWith<MigrationsNotAppliedException> { runner.migrate("probe", edited) }
            assertTrue("[1]" in refused.message.orEmpty())
            // A history with a gap (a version missing below the last one) is refused too.
            database.owner.execute("DELETE FROM _migrations_probe WHERE version = 1").getOrThrow()
            assertFailsWith<MigrationsNotAppliedException> { runner.migrate("probe", second) }
        }
    }

    @Test
    fun adoptsAnEmptySqlx4kHistoryAtTheStart() {
        databaseTest { database ->
            database.owner.execute("CREATE TABLE _migrations_probe (version BIGINT, checksum TEXT)").getOrThrow()
            database.runner().migrate("probe", first)
            assertEquals(1, database.count("flyway_probe WHERE type = 'BASELINE' AND version = '0'"))
            assertEquals(1, database.count("flyway_probe WHERE version = '1' AND success"))
        }
    }

    @Test
    fun aVersionThatFailedIsNotApplied() {
        databaseTest { database ->
            val runner = database.runner()
            runner.migrate("probe", first)
            database.owner.execute(
                "INSERT INTO flyway_probe (installed_rank, version, description, type, script, checksum, " +
                    "installed_by, execution_time, success) " +
                    "VALUES (100, '2', 'add_name', 'JDBC', 'add_name', 0, current_user, 0, false)",
            ).getOrThrow()
            val missing = assertFailsWith<MigrationsNotAppliedException> { runner.verify("probe", second) }
            assertTrue("[2]" in missing.message.orEmpty())
        }
    }

    @Test
    fun aMigrationThatFailsStopsThePassAtOnce() {
        databaseTest { database ->
            val broken = first + SchemaMigration(2, "broken", "ALTER TABLE missing_table ADD COLUMN x TEXT;")
            assertFailsWith<FlywayException> { database.runner().migrate("probe", broken) }
            assertEquals(0, waits(), "Not a lock: not run again")
            assertFalse(database.canWrite(database.servingRole, "flyway_probe"), "History left writable")
        }
    }

    // CRL-05: a migration behind a long transaction gives up after lock_timeout (3 s) and the pass runs again.
    @Test
    fun aPassThatWaitedTooLongForALockRunsAgain() {
        databaseTest { database ->
            val runner = database.runner(pause = 2.seconds, attempts = 3)
            runner.migrate("probe", first)
            val holder = migrationSource(database.ownerConfig).connection
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE probe_items IN ACCESS EXCLUSIVE MODE") }
            // Released between the first attempt (refused after 3 s) and the second (after a 2 s pause).
            val release = thread {
                Thread.sleep(RELEASE_AFTER_MILLIS)
                holder.rollback()
                holder.close()
            }
            runner.migrate("probe", second)
            release.join()
            assertEquals(1, waits())
            assertEquals(1, database.count("flyway_probe WHERE version = '2' AND success"))
        }
    }

    @Test
    fun aPassThatKeepsWaitingForALockFailsAfterItsAttempts() {
        databaseTest { database ->
            val runner = database.runner(pause = Duration.ZERO, attempts = 2)
            runner.migrate("probe", first)
            migrationSource(database.ownerConfig).connection.use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE probe_items IN ACCESS EXCLUSIVE MODE") }
                assertFailsWith<FlywayException> { runner.migrate("probe", second) }
                holder.rollback()
            }
            assertEquals(1, waits(), "Two attempts, one pause between them")
        }
    }

    @Test
    fun concurrentPassesWaitForEachOther() {
        databaseTest { database ->
            val modules = listOf(Probe("probe", third))
            coroutineScope {
                List(3) { async { migrateModules(database.ownerConfig, logger, modules) } }.awaitAll()
            }
            assertEquals(3, applied(), "Each version applied by one pass only")
            val locks = database.superuser.fetchAll(
                "SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND objid = " +
                    (MIGRATION_LOCK_KEY and 0xFFFFFFFFL),
            ).getOrThrow().single().get("n").asLong()
            assertEquals(0, locks, "The migration lock is released")
        }
    }

    // TeamCode 3da27ca0: two migrations of one module never share a version.
    @Test
    fun refusesVersionsThatRepeatGoBackwardsOrSkipOne() {
        databaseTest { database ->
            val runner = database.runner()
            val repeated = first + first.single().copy(name = "again")
            assertFailsWith<IllegalArgumentException> { runner.migrate("probe", repeated) }
            assertFailsWith<IllegalArgumentException> { runner.verify("probe", second.reversed()) }
            val zero = listOf(first.single().copy(version = 0))
            assertFailsWith<IllegalArgumentException> { runner.migrate("probe", zero) }
            val gap = first + second.last().copy(version = 3)
            assertFailsWith<IllegalArgumentException> { runner.migrate("probe", gap) }
            assertFailsWith<IllegalArgumentException> { runner.migrate("Probe", first) }
            runner.migrate("empty", emptyList())
            assertEquals(0, applied())
        }
    }

    private suspend fun TestDatabase.canWrite(role: String, table: String): Boolean = owner.fetchAll(
        Statement.create(
            "SELECT has_table_privilege(:role, :table, 'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER') AS w",
        ).bind("role", role).bind("table", table),
    ).getOrThrow().single().get("w").asBoolean()

    private fun SchemaMigration.renamed(prefix: String) = copy(sql = sql.replace("probe_items", "${prefix}_items"))

    private class Probe(override val name: String, override val migrations: List<SchemaMigration>) : ServerModule {
        override val koinModule: Module = module {}
    }

    private companion object {
        const val RELEASE_AFTER_MILLIS = 4_000L
    }
}
