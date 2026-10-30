// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.database.UnsafeDatabaseRoleException
import dev.ostrom.server.kernel.database.requireRowLevelSecurity
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tenant isolation on PostgreSQL (CONVENTIONS 16.1, S2-01), connected as a serving role like a deployment's: neither
 * superuser nor `BYPASSRLS`, owning nothing.
 */
class TenancyPostgresTest {
    private val alpha = Uuid.random()
    private val beta = Uuid.random()

    /** A tenant table as a module's migration creates it, owned by the test database's owner. */
    private suspend fun createNotes(owner: Driver, role: String) {
        listOf(
            "CREATE TABLE notes (org_id uuid NOT NULL, body text NOT NULL)",
            "ALTER TABLE notes ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE notes FORCE ROW LEVEL SECURITY",
            "CREATE POLICY notes_org ON notes " +
                "USING (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid)) " +
                "WITH CHECK (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid))",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON notes TO $role",
        ).forEach { owner.execute(it).getOrThrow() }
    }

    private fun asServingRole(block: suspend (owner: Driver, serving: Driver) -> Unit) = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            // One connection: every query of the test runs on the connection the previous one used.
            database.withRole(poolSize = 1) { role, serving ->
                createNotes(database.driver, role)
                block(database.driver, serving)
            }
        } finally {
            database.close()
        }
    }

    private suspend fun QueryExecutor.insert(org: Uuid, body: String) {
        execute(
            Statement.create("INSERT INTO notes (org_id, body) VALUES (CAST(:org AS uuid), :body)")
                .bind("org", org.toString())
                .bind("body", body),
        ).getOrThrow()
    }

    private suspend fun QueryExecutor.count(): Long =
        fetchAll("SELECT count(*) AS n FROM notes").getOrThrow().single().get("n").asLong()

    @Test
    fun anOrganizationSeesAndWritesOnlyItsOwnRows() = asServingRole { owner, serving ->
        serving.withOrganization(alpha) { it.insert(alpha, "alpha's") }
        serving.withOrganization(beta) { it.insert(beta, "beta's") }
        assertEquals(1L, serving.withOrganization(alpha) { it.count() })
        assertEquals(1L, serving.withOrganization(beta) { it.count() })
        assertEquals(2L, owner.count(), "The owner, a superuser in tests, sees both")
        assertFailsWith<SQLError>("A row of another organization is refused") {
            serving.withOrganization(alpha) { it.insert(beta, "planted") }
        }
        val moved = serving.withOrganization(alpha) {
            val update = Statement.create("UPDATE notes SET body = 'touched' WHERE org_id = CAST(:org AS uuid)")
            it.execute(update.bind("org", beta.toString())).getOrThrow()
        }
        assertEquals(0L, moved, "Another organization's rows cannot be updated")
    }

    @Test
    fun withoutAnOrganizationNoRowIsVisible() = asServingRole { _, serving ->
        serving.withOrganization(alpha) { it.insert(alpha, "alpha's") }
        assertEquals(0L, serving.count(), "Fail closed: no organization, no row")
        assertFailsWith<SQLError> { serving.insert(alpha, "outside") }
    }

    @Test
    fun theOrganizationNeverOutlivesItsTransaction() = asServingRole { _, serving ->
        serving.withOrganization(alpha) { it.count() }
        val setting = serving.fetchAll("SELECT current_setting('ostrom.org_id', true) AS org").getOrThrow()
            .single().get("org").asStringOrNull()
        assertEquals(null, setting?.ifEmpty { null }, "The pooled connection keeps no organization")
    }

    @Test
    fun aFailureRollsTheTransactionBack() = asServingRole { _, serving ->
        assertFailsWith<IllegalStateException> {
            serving.withOrganization(alpha) {
                it.insert(alpha, "rolled back")
                error("use case failed")
            }
        }
        assertEquals(0L, serving.withOrganization(alpha) { it.count() })
    }

    @Test
    fun theServingRoleCannotTurnIsolationOff() = asServingRole { _, serving ->
        listOf(
            "ALTER TABLE notes DISABLE ROW LEVEL SECURITY",
            "ALTER TABLE notes NO FORCE ROW LEVEL SECURITY",
            "DROP POLICY notes_org ON notes",
            "CREATE OR REPLACE FUNCTION leak() RETURNS int LANGUAGE sql AS 'SELECT 1'",
            "CREATE TABLE planted (id int)",
        ).forEach { sql ->
            val error = assertFailsWith<SQLError>(sql) { serving.execute(sql).getOrThrow() }
            val message = error.message.orEmpty()
            assertTrue("must be owner" in message || "permission denied" in message, "$sql: $message")
        }
    }

    @Test
    fun theServingRolePassesTheStartCheck() = asServingRole { _, serving ->
        serving.requireRowLevelSecurity()
    }

    @Test
    fun aSuperuserOrBypassingRoleIsRefusedAtStart() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            // The test database's own user is a superuser (CI's postgres service).
            assertFailsWith<UnsafeDatabaseRoleException> { database.driver.requireRowLevelSecurity() }
            database.withRole(bypassRowLevelSecurity = true) { _, bypassing ->
                val error = assertFailsWith<UnsafeDatabaseRoleException> { bypassing.requireRowLevelSecurity() }
                assertNull(error.cause)
            }
            database.withRole { role, owning ->
                // A serving role that owns a table could disable its row-level security.
                database.driver.execute("CREATE TABLE owned (id int)").getOrThrow()
                database.driver.execute("ALTER TABLE owned OWNER TO $role").getOrThrow()
                assertFailsWith<UnsafeDatabaseRoleException> { owning.requireRowLevelSecurity() }
            }
        } finally {
            database.close()
        }
    }
}
