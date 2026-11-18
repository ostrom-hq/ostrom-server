// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
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

    private suspend fun JdbcDatabase.long(sql: String): Long = query {
        exec(sql, explicitStatementType = StatementType.SELECT) { rows -> rows.next(); rows.getLong(1) }!!
    }

    private suspend fun TestDatabase.count(table: String): Long = ownerJdbc.long("SELECT count(*) FROM $table")

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
            // The second module finds the first one's tables: its history starts at an empty baseline, not refused.
            assertEquals(1, database.count("flyway_other_probe WHERE type = 'BASELINE' AND version = '0'"))
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

    // AUDIT-RLS R8: the serving role reads Flyway's history and never writes it.
    @Test
    fun onlyTheOwnerWritesTheHistory() {
        databaseTest { database ->
            val runner = database.runner()
            runner.migrate("probe", first)
            assertFalse(database.canWrite(database.servingRole, "flyway_probe"), "Serving role writes the history")
            database.superuserJdbc.query { exec("GRANT ALL ON flyway_probe TO ${database.servingRole}") }
            runner.migrate("probe", first)
            assertFalse(database.canWrite(database.servingRole, "flyway_probe"), "Write not revoked again")
            assertTrue(database.canWrite(database.servingRole, "probe_items"), "Module tables keep their grants")
        }
    }

    @Test
    fun aVersionThatFailedIsNotApplied() {
        databaseTest { database ->
            val runner = database.runner()
            runner.migrate("probe", first)
            database.ownerJdbc.query {
                exec(
                    "INSERT INTO flyway_probe (installed_rank, version, description, type, script, checksum, " +
                        "installed_by, execution_time, success) " +
                        "VALUES (100, '2', 'add_name', 'JDBC', 'add_name', 0, current_user, 0, false)",
                )
            }
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
            // A pass releases the lock by closing its connection; the backend drops it from pg_locks a moment later
            // (CI run 43567561020 read it still held once), so the check waits a few seconds for it to go.
            var locks = heldMigrationLocks(database)
            repeat(LOCK_RELEASE_CHECKS) {
                if (locks == 0L) return@repeat
                delay(LOCK_RELEASE_PAUSE)
                locks = heldMigrationLocks(database)
            }
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

    private suspend fun heldMigrationLocks(database: TestDatabase): Long = database.superuserJdbc.long(
        // This test's database only: other tests' passes take the same key on theirs at the same time.
        "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = " +
            (MIGRATION_LOCK_KEY and 0xFFFFFFFFL) +
            " AND database = (SELECT oid FROM pg_database WHERE datname = current_database())",
    )

    private suspend fun TestDatabase.canWrite(role: String, table: String): Boolean = ownerJdbc.query {
        exec(
            "SELECT has_table_privilege(?, ?, 'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')",
            listOf(TextColumnType() to role, TextColumnType() to table),
            StatementType.SELECT,
        ) { rows -> rows.next(); rows.getBoolean(1) }!!
    }

    private fun SchemaMigration.renamed(prefix: String) = copy(sql = sql.replace("probe_items", "${prefix}_items"))

    private class Probe(override val name: String, override val migrations: List<SchemaMigration>) : ServerModule {
        override val koinModule: Module = module {}
    }

    private companion object {
        const val RELEASE_AFTER_MILLIS = 4_000L
        const val LOCK_RELEASE_CHECKS = 50
        val LOCK_RELEASE_PAUSE = 100.milliseconds
    }
}
