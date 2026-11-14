// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.UnsafeDatabaseRoleException
import run.ostrom.server.kernel.database.requireRowLevelSecurity
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.module.ServerModule
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class TestDatabaseTest {
    private class ProbeModule(override val name: String, override val koinModule: Module) : ServerModule

    private val environment = EnvironmentConfigSource()

    @Test
    fun isSkippedWhenNoDatabaseIsConfigured() = runTest {
        assertNull(TestDatabase.create(MapConfigSource(emptyMap())))
    }

    @Test
    fun failsWhenADatabaseIsRequiredButMissing() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            TestDatabase.create(MapConfigSource(mapOf("OSTROM_TEST_DATABASE_REQUIRED" to "true")))
        }
        assertEquals("OSTROM_TEST_DATABASE_REQUIRED is true but OSTROM_TEST_DATABASE_URL is not set.", error.message)
    }

    @Test
    fun createsAnEmptyDatabaseAndDropsIt() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val tables = database.driver.fetchAll(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            )
            assertEquals(0, tables.getOrThrow().size, "A fresh database has no table")
        } finally {
            database.close()
        }
    }

    private suspend fun Driver.text(sql: String, role: String? = null): String {
        val statement = Statement.create(sql).let { if (role == null) it else it.bind("role", role) }
        return fetchAll(statement).getOrThrow().single().get("value").asString()
    }

    private val bypasses = "SELECT CAST(rolbypassrls AS text) AS value FROM pg_roles WHERE rolname = current_user"
    private val roles = "SELECT CAST(count(*) AS text) AS value FROM pg_roles WHERE rolname = :role"

    @Test
    fun aRoleIsCreatedForTheBlockAndDroppedAfter() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val plain = database.withRole { role, driver ->
                assertEquals(role, driver.text("SELECT CAST(current_user AS text) AS value"))
                assertEquals("false", driver.text(bypasses))
                role
            }
            val bypassing = database.withRole(bypassRowLevelSecurity = true) { role, driver ->
                assertEquals("true", driver.text(bypasses))
                role
            }
            assertEquals("0", database.driver.text(roles, plain), "The role is dropped")
            assertEquals("0", database.driver.text(roles, bypassing), "The role is dropped")
        } finally {
            database.close()
        }
    }

    private fun member(role: String, privilege: String = "MEMBER") =
        "SELECT CAST(pg_has_role(current_user, '$role', '$privilege') AS text) AS value"

    @Test
    fun theTestsRunAsTheRolesOfADeployment() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        val owner = database.ownerRole
        val serving = database.servingRole
        try {
            assertEquals(serving, database.driver.text("SELECT CAST(current_user AS text) AS value"))
            assertEquals(owner, database.owner.text("SELECT CAST(current_user AS text) AS value"))
            val databaseOwner = "SELECT CAST(pg_get_userbyid(datdba) AS text) AS value FROM pg_database " +
                "WHERE datname = current_database()"
            assertEquals(owner, database.driver.text(databaseOwner))
            database.driver.requireRowLevelSecurity()
            assertFailsWith<UnsafeDatabaseRoleException> { database.superuser.requireRowLevelSecurity() }
            database.owner.execute("CREATE TABLE probe (id int)").getOrThrow()
            assertFailsWith<UnsafeDatabaseRoleException> { database.owner.requireRowLevelSecurity() }
            database.driver.requireRowLevelSecurity()
            database.driver.execute("INSERT INTO probe VALUES (1)").getOrThrow()
            assertEquals("true", database.driver.text(member("ostrom_tenant")))
            assertEquals("false", database.driver.text(member("ostrom_router")), "Never granted to the serving role")
            assertEquals("true", database.owner.text(member("ostrom_tenant", "MEMBER WITH ADMIN OPTION")))
            assertEquals("true", database.owner.text(member("ostrom_router", "MEMBER WITH ADMIN OPTION")))
            assertEquals("false", database.driver.text(member("ostrom_member_search")), "Never the serving role's")
            assertEquals("true", database.owner.text(member("ostrom_member_search", "MEMBER WITH ADMIN OPTION")))
        } finally {
            database.close()
        }
        val other = TestDatabase.create() ?: return@runTest
        try {
            assertEquals("0", other.driver.text(roles, owner), "The owner role is dropped")
            assertEquals("0", other.driver.text(roles, serving), "The serving role is dropped")
        } finally {
            other.close()
        }
    }

    @Test
    fun aRoleIsDroppedWhenTheBlockFails() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            var created = ""
            assertFailsWith<IllegalStateException> {
                database.withRole { role, _ ->
                    created = role
                    error("test failure")
                }
            }
            assertEquals("0", database.driver.text(roles, created))
        } finally {
            database.close()
        }
    }

    // The lock of TestDatabase.exclusively held in pg_locks, by any test process (a bigint key: classid 0, objsubid 1).
    private val exclusiveLocksHeld =
        "SELECT CAST(count(*) AS text) AS value FROM pg_locks WHERE locktype = 'advisory' AND classid = 0 " +
            "AND objid = ${TestDatabase.EXCLUSIVE_LOCK} AND objsubid = 1 AND granted"

    @Test
    fun largePoolsTakeTurnsOnTheServerAndSmallOnesNeverWait() = runTest(timeout = BENCHES_AHEAD) {
        val first = TestDatabase.create() ?: return@runTest
        val second = checkNotNull(TestDatabase.create())
        try {
            val holding = CompletableDeferred<String>()
            val release = CompletableDeferred<Unit>()
            val holder = async {
                first.withRole(poolSize = LARGE_POOL) { _, _ ->
                    // Taken again at once within, and by a child coroutine: a bench's large pool inside its timing.
                    coroutineScope {
                        async { first.exclusively { holding.complete(first.superuser.text(exclusiveLocksHeld)) } }
                            .await()
                    }
                    release.await()
                }
            }
            assertEquals("1", holding.await(), "A single holder of the lock on the server")
            // Another test database, as another Gradle test process has: its exclusive phase waits for the first one.
            val refused = CompletableDeferred<Unit>()
            val waiter = async { second.exclusively(onWait = { refused.complete(Unit) }) { "entered" } }
            refused.await()
            assertEquals("small", second.withRole { _, _ -> "small" }, "A small pool never waits")
            assertFalse(waiter.isCompleted, "Refused while the first one holds the lock, it has not entered")
            release.complete(Unit)
            holder.await()
            assertEquals("entered", waiter.await(), "Then it enters")
        } finally {
            second.close()
            first.close()
        }
    }

    @Test
    fun aPoolLargerThanTheLargestIsRefusedBeforeAnyConnection() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val refused = assertFailsWith<IllegalArgumentException> {
                database.withRole(poolSize = TestDatabase.LARGEST_POOL + 1) { _, _ -> error("never opened") }
            }
            assertEquals("A pool of 21: at most 20 beside the other tests' pools.", refused.message)
        } finally {
            database.close()
        }
    }

    @Test
    fun aDatabaseThatCannotBeCreatedReleasesItsConnection() = runTest {
        val url = environment["OSTROM_TEST_DATABASE_URL"] ?: return@runTest
        // A database name that PostgreSQL refuses to create: the creation fails, nothing is left open.
        val config = MapConfigSource(
            mapOf(
                "OSTROM_TEST_DATABASE_URL" to url,
                "OSTROM_TEST_DATABASE_USER" to "no_such_role_for_ostrom_tests",
                "OSTROM_TEST_DATABASE_PASSWORD" to "wrong",
            ),
        )
        assertFails { TestDatabase.create(config) }
    }

    @Test
    fun serverTestIsSkippedWithoutADatabase() {
        var ran = false
        serverTest(emptyList(), null, MapConfigSource(emptyMap())) { ran = true }
        assertFalse(ran, "No database: the test body does not run")
    }

    @Test
    fun serverTestServesTheModulesRoutes() = serverTest(emptyList()) { server ->
        assertEquals(HttpStatusCode.OK, server.client.get("/health").status)
    }

    @Test
    fun serverTestMigratesWithFlyway() = serverTest(listOf(Migrated)) { server ->
        val history = server.database.owner.fetchAll("SELECT count(*) AS n FROM flyway_probe WHERE success")
            .getOrThrow().single().get("n").asLong()
        assertEquals(1L, history, "the probe's migration is in its Flyway history")
        assertEquals(HttpStatusCode.OK, server.client.get("/health").status)
    }

    @Test
    fun aTestMigratesAModuleWithFlywayAsTheOwner() = serverTest(emptyList()) { server ->
        val second = Migrated.migrations +
            SchemaMigration(2, "add_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")
        val runner = server.database.migrationRunner(JsonLogger(LogLevel.WARN))
        runner.migrate(Migrated.name, Migrated.migrations)
        runner.migrate(Migrated.name, second)
        val history = server.database.owner.fetchAll("SELECT count(*) AS n FROM flyway_probe WHERE success")
            .getOrThrow().single().get("n").asLong()
        assertEquals(2L, history, "both versions, applied one pass after the other")
    }

    /** A module with one migration. */
    private object Migrated : ServerModule {
        override val name: String = "probe"
        override val koinModule: Module = module {}
        override val migrations: List<SchemaMigration> =
            listOf(SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id BIGINT PRIMARY KEY);"))
    }

    @Test
    fun serverTestFailsWhenKoinCannotStart() {
        if (environment["OSTROM_TEST_DATABASE_URL"] == null) return
        val first = ProbeModule("first", module { single { "first" } })
        val second = ProbeModule("second", module { single { "second" } })
        assertFails { serverTest(listOf(first, second)) {} }
    }

    private companion object {
        // Larger than withRole's default pool of 2: a pool that takes turns.
        const val LARGE_POOL = 3
        // The other test processes' benches may hold the lock first, each for its timings: a bench's own timeout.
        val BENCHES_AHEAD = 5.minutes
    }
}
