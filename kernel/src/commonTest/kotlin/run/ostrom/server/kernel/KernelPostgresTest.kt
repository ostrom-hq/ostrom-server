// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.MigrationRunner
import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.database.MigrationsNotAppliedException
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.kernel.database.migrateUnderLock
import run.ostrom.server.kernel.http.DatabaseHealthCheck
import run.ostrom.server.kernel.http.HealthCheck
import run.ostrom.server.kernel.http.Readiness
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.module.kernelModule
import run.ostrom.server.kernel.network.TrustedProxies
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.kernel.signing.InstanceSigner
import run.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

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
            val config = MapConfigSource(mapOf("OSTROM_PROBE" to "on"))
            val koin = serverKoin(kernelModule(logger, database.owner, signer, config), emptyList())
            assertSame(config, koin.get<ConfigSource>())
            assertSame(logger, koin.get<Logger>())
            assertSame(database.owner, koin.get<Driver>())
            assertSame(signer, koin.get<InstanceSigner>())
            assertSame(Clock.System, koin.get<Clock>())
            assertSame(TrustedProxies.NONE, koin.get<TrustedProxies>())
            assertFalse(koin.get<Readiness>().isDraining)
            assertTrue(koin.get<HealthCheck>(named("database")).isUp())
            assertNull(koin.getOrNull<JdbcDatabase>(), "no pool is bound when none is given")
            block(database, koin.get())
            koin.close()
        } finally {
            database.close()
        }
    }

    @Test
    fun takesTheDatabaseCheckAndTheMigrationRunnerItIsGiven() {
        runTest {
            val database = TestDatabase.create() ?: return@runTest
            try {
                val check = object : HealthCheck {
                    override val name: String = "database"
                    override suspend fun isUp(): Boolean = false
                }
                val logger = JsonLogger(LogLevel.INFO, sink = { lines += it })
                val runner = MigrationRunnerImpl(database.owner, logger)
                val jdbc = JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE)
                val kernel = kernelModule(
                    logger,
                    database.owner,
                    Ed25519InstanceSigner.generate(),
                    MapConfigSource(emptyMap()),
                    databaseCheck = check,
                    migrationRunner = runner,
                    jdbc = jdbc,
                )
                val koin = serverKoin(kernel, emptyList())
                assertSame(check, koin.get<HealthCheck>(named("database")))
                assertSame(runner, koin.get<MigrationRunner>())
                assertSame(jdbc, koin.get<JdbcDatabase>())
                koin.close()
                jdbc.close()
            } finally {
                database.close()
            }
        }
    }

    private suspend fun Driver.count(table: String): Long =
        fetchAll(Statement.create("SELECT count(*) AS n FROM $table")).getOrThrow().single().get("n").asLong()

    private val first = listOf(SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id BIGINT PRIMARY KEY);"))

    @Test
    fun appliesAModulesMigrationsOnceAndChecksThemAtEveryStart() = databaseTest { database, runner ->
        runner.migrate("probe", first)
        runner.migrate("probe", first)
        assertEquals(1, database.owner.count("_migrations_probe"))
        assertEquals(1, lines.count { "\"msg\":\"migration applied\"" in it }, "Applied once, checked twice")

        val second = first + SchemaMigration(2, "add_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")
        runner.migrate("probe", second)
        assertEquals(2, database.owner.count("_migrations_probe"))
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
        assertEquals(1, database.owner.count("_migrations_probe"))
        assertEquals(1, database.owner.count("_migrations_other_probe"))
    }

    @Test
    fun aModuleWithoutMigrationsCreatesNoTable() = databaseTest { database, runner ->
        runner.migrate("empty", emptyList())
        val tables = database.owner.fetchAll(
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
        assertFalse(DatabaseHealthCheck(database.owner, timeout = Duration.ZERO).isUp())
    }

    @Test
    fun aTransactionInOneOrganizationSeesAndWritesOnlyItsRows() = databaseTest { database, runner ->
        val orgA = Uuid.random()
        val orgB = Uuid.random()
        runner.migrate("probe", tenantTable)
        // Seeded by the owner (the table is not FORCEd); read and written by the serving role, as the server does.
        val driver = database.driver
        for ((org, name) in listOf(orgA to "a", orgB to "b")) {
            database.owner.execute(
                Statement.create("INSERT INTO probe_notes (org_id, name) VALUES (:org, :name)")
                    .bind("org", org).bind("name", name),
            ).getOrThrow()
        }

        val seen = driver.inOrg(orgA) {
            fetchAll("SELECT name FROM probe_notes ORDER BY name").getOrThrow().map { it.get("name").asString() }
        }
        assertEquals(listOf("a"), seen)
        assertFails {
            driver.inOrg(orgA) {
                execute(
                    Statement.create("INSERT INTO probe_notes (org_id, name) VALUES (:org, 'x')")
                        .bind("org", orgB),
                ).getOrThrow()
            }
        }
        assertEquals(2, database.owner.count("probe_notes"), "The refused write left nothing")
    }

    // The role is global to the cluster: its creation tolerates one made by another database or test.
    private val tenantTable = listOf(
        SchemaMigration(
            1,
            "create_notes",
            """
            DO $$ BEGIN CREATE ROLE $TENANT_ROLE NOLOGIN; EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL; END $$;
            DO $$ BEGIN GRANT $TENANT_ROLE TO CURRENT_USER; EXCEPTION WHEN unique_violation THEN NULL; END $$;
            CREATE TABLE probe_notes (org_id UUID NOT NULL, name TEXT NOT NULL);
            ALTER TABLE probe_notes ENABLE ROW LEVEL SECURITY;
            CREATE POLICY probe_notes_tenant ON probe_notes
                USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
                WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
            GRANT SELECT, INSERT ON probe_notes TO $TENANT_ROLE;
            """.trimIndent(),
        ),
    )

    private class Probe(override val name: String, override val migrations: List<SchemaMigration>) : ServerModule {
        override val koinModule: Module = module {}
    }

    private suspend fun Driver.advisoryLocks(): Long = fetchAll(
        "SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND database = " +
            "(SELECT oid FROM pg_database WHERE datname = current_database())",
    ).getOrThrow().single().get("n").asLong()

    @Test
    fun verifyRefusesMissingOrEditedMigrationsAndAcceptsAppliedOnes() = databaseTest { _, runner ->
        val missing = assertFailsWith<MigrationsNotAppliedException> { runner.verify("probe", first) }
        assertEquals(
            "Migrations [1] of module probe are not applied, or differ from the applied ones: run " +
                "`ostrom-server migrate`.",
            missing.message,
        )
        runner.migrate("probe", first)
        runner.verify("probe", first)
        val second = first + SchemaMigration(2, "add_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")
        assertFailsWith<MigrationsNotAppliedException> { runner.verify("probe", second) }
        val edited = listOf(SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id TEXT PRIMARY KEY);"))
        assertFailsWith<MigrationsNotAppliedException> { runner.verify("probe", edited) }
        runner.verify("empty", emptyList())
        assertFailsWith<IllegalArgumentException> { runner.verify("Probe", first) }
    }

    private suspend fun TestDatabase.canWrite(role: String, table: String): Boolean = owner.fetchAll(
        Statement.create(
            "SELECT has_table_privilege(:role, :table, 'INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER') AS w",
        ).bind("role", role).bind("table", table),
    ).getOrThrow().single().get("w").asBoolean()

    // AUDIT-RLS R8: the serving role reads a module's history (verify, at start) and never writes it.
    @Test
    fun onlyTheOwnerWritesTheHistoryAndTheServingRoleReadsIt() = databaseTest { database, runner ->
        runner.migrate("probe", first)
        assertFalse(database.canWrite(database.servingRole, "_migrations_probe"), "Serving role writes the history")
        assertFails { database.driver.execute("DELETE FROM _migrations_probe").getOrThrow() }
        assertFails { database.driver.execute("UPDATE _migrations_probe SET checksum = 'x'").getOrThrow() }
        MigrationRunnerImpl(database.driver, JsonLogger(LogLevel.INFO, sink = {})).verify("probe", first)
        assertTrue(database.canWrite(database.ownerRole, "_migrations_probe"), "The owner writes it")

        // A grant made since (by hand, or before this check existed) is revoked at the next pass.
        database.superuser.execute("GRANT ALL ON _migrations_probe TO PUBLIC, ${database.servingRole}").getOrThrow()
        runner.migrate("probe", first)
        assertFalse(database.canWrite(database.servingRole, "_migrations_probe"), "Write not revoked again")
        // The module's own tables keep the default privileges.
        assertTrue(database.canWrite(database.servingRole, "probe_items"), "Module tables keep their grants")
    }

    @Test
    fun aColumnGrantAndAHyphenatedModuleAreCoveredAndAFailedPassStillRevokes() = databaseTest { database, runner ->
        val history = "_migrations_other_probe"
        val broken = listOf(SchemaMigration(1, "broken", "CREATE TABLE ;"))
        assertFails { runner.migrate("other-probe", broken) }
        if (database.owner.exists(history)) {
            assertFalse(database.canWrite(database.servingRole, history), "Writable after a failed pass")
        }
        val fine = listOf(SchemaMigration(1, "create_other", "CREATE TABLE other_items (id BIGINT);"))
        database.superuser.execute("DROP TABLE IF EXISTS $history").getOrThrow()
        runner.migrate("other-probe", fine)
        database.superuser.execute("GRANT UPDATE (checksum) ON $history TO ${database.servingRole}").getOrThrow()
        runner.migrate("other-probe", fine)
        assertFalse(database.columnWrite(database.servingRole, history), "Column UPDATE not revoked")
        assertFalse(database.canWrite(database.servingRole, history))
        MigrationRunnerImpl(database.driver, JsonLogger(LogLevel.INFO, sink = {})).verify("other-probe", fine)
    }

    private suspend fun Driver.exists(table: String): Boolean = fetchAll(
        Statement.create("SELECT to_regclass(:table) IS NOT NULL AS e").bind("table", table),
    ).getOrThrow().single().get("e").asBoolean()

    private suspend fun TestDatabase.columnWrite(role: String, table: String): Boolean = owner.fetchAll(
        Statement.create("SELECT has_any_column_privilege(:role, :table, 'INSERT, UPDATE, REFERENCES') AS w")
            .bind("role", role).bind("table", table),
    ).getOrThrow().single().get("w").asBoolean()

    @Test
    fun concurrentMigrationPassesWaitForEachOther() = databaseTest { database, runner ->
        val modules = listOf(
            Probe("probe", first + SchemaMigration(2, "add_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")),
            Probe("other-probe", listOf(SchemaMigration(1, "create_other", "CREATE TABLE other_items (id BIGINT);"))),
        )
        coroutineScope {
            List(3) { async { database.owner.migrateUnderLock(runner, modules) } }.awaitAll()
        }
        assertEquals(2, database.owner.count("_migrations_probe"))
        assertEquals(1, database.owner.count("_migrations_other_probe"))
        modules.forEach { runner.verify(it.name, it.migrations) }
        assertEquals(0, database.owner.advisoryLocks(), "The lock is released")
    }

    @Test
    fun aFailedMigrationReleasesTheLock() = databaseTest { database, runner ->
        val broken = listOf(Probe("probe", listOf(SchemaMigration(1, "broken", "CREATE TABLE ;"))))
        assertFails { database.owner.migrateUnderLock(runner, broken) }
        assertEquals(0, database.owner.advisoryLocks(), "The lock is released")
        database.owner.migrateUnderLock(runner, listOf(Probe("probe", first)))
        assertEquals(1, database.owner.count("_migrations_probe"))
    }
}
