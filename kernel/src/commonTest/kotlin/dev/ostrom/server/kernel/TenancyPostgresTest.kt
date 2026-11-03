// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.database.TENANT_ROLE
import dev.ostrom.server.kernel.database.UnsafeDatabaseRoleException
import dev.ostrom.server.kernel.database.inOrg
import dev.ostrom.server.kernel.database.requireRowLevelSecurity
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.testing.RecordingDriver
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

    private fun asServingRole(block: suspend (superuser: Driver, serving: Driver) -> Unit) = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            // One connection: every query of the test runs on the connection the previous one used.
            database.withRole(poolSize = 1) { role, serving ->
                createNotes(database.owner, role)
                block(database.superuser, serving)
            }
        } finally {
            database.close()
        }
    }

    // As asServingRole, with the table granted to the tenant role the serving role switches to in inOrg.
    private fun asTenant(block: suspend (serving: Driver, role: String) -> Unit) = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            // One connection: every transaction of the test reuses the connection the previous one used.
            database.withRole(poolSize = 1) { role, serving ->
                database.superuser.execute("GRANT $TENANT_ROLE TO $role").getOrThrow()
                createNotes(database.owner, TENANT_ROLE)
                block(serving, role)
            }
        } finally {
            database.close()
        }
    }

    // Who the connection is and the organization it carries, outside or inside a transaction.
    private suspend fun QueryExecutor.state(): Pair<String?, String?> {
        val row = fetchAll("SELECT current_user AS who, current_setting('ostrom.org_id', true) AS org").getOrThrow()
            .single()
        return row.get("who").asStringOrNull() to row.get("org").asStringOrNull()?.ifEmpty { null }
    }

    private suspend fun QueryExecutor.bodies(): List<String?> =
        fetchAll("SELECT body FROM notes ORDER BY body").getOrThrow().map { it.get("body").asStringOrNull() }

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
    fun anOrganizationSeesAndWritesOnlyItsOwnRows() = asServingRole { superuser, serving ->
        serving.withOrganization(alpha) { it.insert(alpha, "alpha's") }
        serving.withOrganization(beta) { it.insert(beta, "beta's") }
        assertEquals(1L, serving.withOrganization(alpha) { it.count() })
        assertEquals(1L, serving.withOrganization(beta) { it.count() })
        assertEquals(2L, superuser.count(), "The superuser sees both")
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
    fun inOrgConfinesItsTransactionInOneStatementBeforeItsWork() = asTenant { serving, _ ->
        val recording = RecordingDriver(serving)
        val sent = recording.record { recording.inOrg(alpha) { count() } }
        assertEquals(2, sent.size, sent.joinToString("\n") { it.sql })
        assertEquals("SELECT count(*) AS n FROM notes", sent.last().sql, "Then the work")
    }

    @Test
    fun aReusedConnectionNeverKeepsTheRoleOrTheOrganizationOfAnInOrgTransaction() = asTenant { serving, role ->
        val inside = serving.inOrg(alpha) { insert(alpha, "alpha's"); state() }
        assertEquals(TENANT_ROLE to alpha.toString(), inside, "Inside: current_user is the tenant role, with alpha")
        assertEquals(role to null, serving.state(), "Back to the serving role, without an organization")
        assertEquals(listOf<String?>("beta's"), serving.inOrg(beta) { insert(beta, "beta's"); bodies() })
        assertEquals(role to null, serving.state())
        assertFailsWith<IllegalStateException> {
            serving.inOrg(alpha) {
                insert(alpha, "rolled back")
                error("use case failed")
            }
        }
        assertEquals(role to null, serving.state(), "A failed transaction leaves nothing on the connection either")
        assertEquals(listOf<String?>("beta's"), serving.inOrg(beta) { bodies() }, "Never alpha's rows")
        assertEquals(listOf<String?>("alpha's"), serving.inOrg(alpha) { bodies() }, "Never beta's rows")
        assertFailsWith<SQLError>("Outside inOrg, the serving role is not granted the table") { serving.count() }
        assertFailsWith<SQLError>("A row of another organization is refused") {
            serving.inOrg(alpha) { insert(beta, "planted") }
        }
    }

    @Test
    fun aRoleThatCannotTakeTheTenantRoleIsRefusedNeverServedAsItself() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            // A serving role that is not a member of the tenant role: the switch fails, the transaction with it.
            database.withRole(poolSize = 1) { role, serving ->
                createNotes(database.owner, TENANT_ROLE)
                val refused = assertFailsWith<SQLError> { serving.inOrg(alpha) { insert(alpha, "never") } }
                assertTrue("permission denied" in refused.message.orEmpty(), refused.message)
                assertFailsWith<SQLError>("Nor read as itself") { serving.inOrg(alpha) { count() } }
                assertEquals(role to null, serving.state(), "The connection is its own role, without organization")
                assertEquals(0L, database.superuser.count(), "Nothing written, as the owner or anyone else")
            }
        } finally {
            database.close()
        }
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
            // The configured user is a superuser (CI's postgres service); the owner owns the tables.
            assertFailsWith<UnsafeDatabaseRoleException> { database.superuser.requireRowLevelSecurity() }
            database.owner.execute("CREATE TABLE owned_by_owner (id int)").getOrThrow()
            assertFailsWith<UnsafeDatabaseRoleException> { database.owner.requireRowLevelSecurity() }
            database.driver.requireRowLevelSecurity()
            database.withRole(bypassRowLevelSecurity = true) { _, bypassing ->
                val error = assertFailsWith<UnsafeDatabaseRoleException> { bypassing.requireRowLevelSecurity() }
                assertNull(error.cause)
            }
            database.withRole { role, owning ->
                // A serving role that owns a table could disable its row-level security.
                database.superuser.execute("CREATE TABLE owned (id int)").getOrThrow()
                database.superuser.execute("ALTER TABLE owned OWNER TO $role").getOrThrow()
                assertFailsWith<UnsafeDatabaseRoleException> { owning.requireRowLevelSecurity() }
            }
            database.withRole { role, member ->
                // Without inheriting the owner's rights, a member can still SET ROLE to it.
                val owner = "${role}_owner"
                database.superuser.execute("CREATE ROLE $owner NOLOGIN").getOrThrow()
                try {
                    database.superuser.execute("CREATE TABLE owned_by_group (id int)").getOrThrow()
                    database.superuser.execute("ALTER TABLE owned_by_group OWNER TO $owner").getOrThrow()
                    database.superuser.execute("GRANT $owner TO $role WITH INHERIT FALSE").getOrThrow()
                    assertFailsWith<UnsafeDatabaseRoleException> { member.requireRowLevelSecurity() }
                } finally {
                    database.superuser.execute("DROP TABLE IF EXISTS owned_by_group").getOrThrow()
                    database.superuser.execute("DROP ROLE $owner").getOrThrow()
                }
            }
        } finally {
            database.close()
        }
    }
}
