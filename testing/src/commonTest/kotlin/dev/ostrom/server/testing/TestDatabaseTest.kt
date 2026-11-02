// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.database.UnsafeDatabaseRoleException
import dev.ostrom.server.kernel.database.requireRowLevelSecurity
import dev.ostrom.server.kernel.module.ServerModule
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun serverTestFailsWhenKoinCannotStart() {
        if (environment["OSTROM_TEST_DATABASE_URL"] == null) return
        val first = ProbeModule("first", module { single { "first" } })
        val second = ProbeModule("second", module { single { "second" } })
        assertFails { serverTest(listOf(first, second)) {} }
    }
}
