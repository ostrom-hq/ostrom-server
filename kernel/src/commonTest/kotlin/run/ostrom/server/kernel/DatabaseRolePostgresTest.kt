// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.database.UnsafeDatabaseRoleException
import run.ostrom.server.kernel.database.requireRowLevelSecurity
import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The start check (CONVENTIONS 16.1, S2-01) on real roles: the server refuses to start as a role that row-level
 * security does not bind, and accepts the serving role of a deployment (neither superuser nor `BYPASSRLS`, owning
 * nothing).
 */
class DatabaseRolePostgresTest {
    private fun databaseTest(block: suspend (TestDatabase) -> Unit) = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun theServingRolePassesTheStartCheck() = databaseTest { database ->
        database.withRole { _, serving -> serving.requireRowLevelSecurity() }
    }

    @Test
    fun aSuperuserTheOwnerOrABypassingRoleIsRefusedAtStart() = databaseTest { database ->
        // The configured user is a superuser (CI's postgres service); the owner owns the tables.
        assertFailsWith<UnsafeDatabaseRoleException> { database.superuserJdbc.requireRowLevelSecurity() }
        database.ownerJdbc.query { exec("CREATE TABLE owned_by_owner (id int)") }
        assertFailsWith<UnsafeDatabaseRoleException> { database.ownerJdbc.requireRowLevelSecurity() }
        database.withRole(bypassRowLevelSecurity = true) { _, bypassing ->
            val error = assertFailsWith<UnsafeDatabaseRoleException> { bypassing.requireRowLevelSecurity() }
            assertNull(error.cause)
        }
    }

    @Test
    fun aRoleThatOwnsATableIsRefused() = databaseTest { database ->
        database.withRole { role, owning ->
            // A serving role that owns a table could disable its row-level security.
            database.superuserJdbc.query {
                exec("CREATE TABLE owned (id int)")
                exec("ALTER TABLE owned OWNER TO $role")
            }
            assertFailsWith<UnsafeDatabaseRoleException> { owning.requireRowLevelSecurity() }
        }
    }

    @Test
    fun aMemberOfATableOwnersRoleIsRefusedEvenWithoutInheritingIt() = databaseTest { database ->
        database.withRole { role, member ->
            // Without inheriting the owner's rights, a member can still SET ROLE to it.
            val owner = "${role}_owner"
            database.superuserJdbc.query { exec("CREATE ROLE $owner NOLOGIN") }
            try {
                database.superuserJdbc.query {
                    exec("CREATE TABLE owned_by_group (id int)")
                    exec("ALTER TABLE owned_by_group OWNER TO $owner")
                    exec("GRANT $owner TO $role WITH INHERIT FALSE")
                }
                assertFailsWith<UnsafeDatabaseRoleException> { member.requireRowLevelSecurity() }
            } finally {
                database.superuserJdbc.query {
                    exec("DROP TABLE IF EXISTS owned_by_group")
                    exec("DROP ROLE $owner")
                }
            }
        }
    }
}
