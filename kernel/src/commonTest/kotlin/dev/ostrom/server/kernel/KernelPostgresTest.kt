// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.MigrationsNotAppliedException
import dev.ostrom.server.kernel.database.ROUTER_ROLE
import dev.ostrom.server.kernel.database.ROUTER_ROLE_SQL
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.database.migrateUnderLock
import dev.ostrom.server.kernel.database.routedTableSql
import dev.ostrom.server.kernel.database.routerFunctionsSql
import dev.ostrom.server.kernel.http.DatabaseHealthCheck
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.http.Readiness
import dev.ostrom.server.kernel.logging.JsonLogger
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.module.kernelModule
import dev.ostrom.server.kernel.network.TrustedProxies
import dev.ostrom.server.kernel.module.serverKoin
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
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
            val config = MapConfigSource(mapOf("OSTROM_PROBE" to "on"))
            val koin = serverKoin(kernelModule(logger, database.driver, signer, config), emptyList())
            assertSame(config, koin.get<ConfigSource>())
            assertSame(logger, koin.get<Logger>())
            assertSame(database.driver, koin.get<Driver>())
            assertSame(signer, koin.get<InstanceSigner>())
            assertSame(Clock.System, koin.get<Clock>())
            assertSame(TrustedProxies.NONE, koin.get<TrustedProxies>())
            assertFalse(koin.get<Readiness>().isDraining)
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

    @Test
    fun concurrentMigrationPassesWaitForEachOther() = databaseTest { database, runner ->
        val modules = listOf(
            Probe("probe", first + SchemaMigration(2, "add_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")),
            Probe("other-probe", listOf(SchemaMigration(1, "create_other", "CREATE TABLE other_items (id BIGINT);"))),
        )
        coroutineScope {
            List(3) { async { database.driver.migrateUnderLock(runner, modules) } }.awaitAll()
        }
        assertEquals(2, database.driver.count("_migrations_probe"))
        assertEquals(1, database.driver.count("_migrations_other_probe"))
        modules.forEach { runner.verify(it.name, it.migrations) }
        assertEquals(0, database.driver.advisoryLocks(), "The lock is released")
    }

    @Test
    fun aFailedMigrationReleasesTheLock() = databaseTest { database, runner ->
        val broken = listOf(Probe("probe", listOf(SchemaMigration(1, "broken", "CREATE TABLE ;"))))
        assertFails { database.driver.migrateUnderLock(runner, broken) }
        assertEquals(0, database.driver.advisoryLocks(), "The lock is released")
        database.driver.migrateUnderLock(runner, listOf(Probe("probe", first)))
        assertEquals(1, database.driver.count("_migrations_probe"))
    }

    @Test
    fun aRoutedTableIsConfinedAndOnlyTheRoutersFunctionsCrossOrganizations() = databaseTest { database, runner ->
        // S2-51: a routing table under row-level security; the one cross-organization lookup is a narrow function.
        val routes = SchemaMigration(
            1,
            "create_probe_routes",
            """
            CREATE TABLE probe_routes (id UUID PRIMARY KEY, org_id UUID NOT NULL, slug TEXT UNIQUE);
            $ROUTER_ROLE_SQL
            ${routedTableSql("probe_routes", "SELECT")}
            CREATE FUNCTION probe_route(p_slug TEXT) RETURNS TABLE (id UUID, org_id UUID)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
                AS $$ SELECT r.id, r.org_id FROM probe_routes r WHERE r.slug = p_slug $$;
            ${routerFunctionsSql("probe_route(TEXT)")}
            DO $$ BEGIN CREATE ROLE ostrom_probe_serving NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
            GRANT SELECT, INSERT ON probe_routes TO ostrom_probe_serving;
            INSERT INTO probe_routes VALUES ('$ROUTE_A', '$ORG_A', 'slug-a'), ('$ROUTE_B', '$ORG_B', 'slug-b');
            """.trimIndent(),
        )
        runner.migrate("probe", listOf(routes))
        val driver = database.driver
        val owner = driver.fetchAll("SELECT pg_get_userbyid(proowner) AS o FROM pg_proc WHERE proname = 'probe_route'")
            .getOrThrow().single().get("o").asString()
        assertEquals(ROUTER_ROLE, owner)

        // The CI user is a superuser, which bypasses row-level security: the proof runs as a plain role.
        suspend fun asServing(org: String?, sql: String) = driver.transaction {
            execute("SET LOCAL ROLE ostrom_probe_serving").getOrThrow()
            org?.let { execute("SELECT set_config('ostrom.org_id', '$it', true)").getOrThrow() }
            fetchAll(sql).getOrThrow().map { row -> row.get("org_id").asString() }
        }
        assertEquals(emptyList(), asServing(null, "SELECT org_id FROM probe_routes"), "No organization: no row")
        assertEquals(listOf("$ORG_B"), asServing("$ORG_B", "SELECT org_id FROM probe_routes"), "Its own row only")
        assertEquals(listOf("$ORG_A"), asServing(null, "SELECT org_id FROM probe_route('slug-a')"))
        assertEquals(emptyList(), asServing(null, "SELECT org_id FROM probe_route('slug-c')"), "A wrong key: nothing")
        assertEquals(emptyList(), asServing(null, "SELECT org_id FROM probe_route(NULL)"), "No key: nothing")
        assertFails("Organization B cannot write a row of organization A") {
            asServing("$ORG_B", "INSERT INTO probe_routes VALUES (gen_random_uuid(), '$ORG_A', 'x') RETURNING org_id")
        }
    }

    private companion object {
        const val ORG_A = "0f0e0d0c-0b0a-0908-0706-050403020100"
        const val ORG_B = "1f1e1d1c-1b1a-1918-1716-151413121110"
        const val ROUTE_A = "00010203-0405-0607-0809-0a0b0c0d0e0f"
        const val ROUTE_B = "10111213-1415-1617-1819-1a1b1c1d1e1f"
    }
}
