// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * [withOrganization] (an organization's rows as the serving role) and [lockUntilCommit] (the advisory lock a sqlx4k
 * transaction takes with the same SQL), on a serving role like a deployment's.
 */
class JdbcOrganizationPostgresTest {
    private val alpha = Uuid.random()
    private val beta = Uuid.random()

    /** A tenant table whose policy holds for every role, the tenant role reading it only. */
    private object Notes : Table("notes") {
        val orgId = uuid("org_id")
        val body = text("body")
    }

    // On the real clock: the transactions really wait for each other.
    private fun asServingRole(block: suspend (JdbcDatabase) -> Unit) {
        runTest {
            withContext(Dispatchers.Default) {
                val database = TestDatabase.create() ?: return@withContext
                try {
                    listOf(
                        "CREATE TABLE notes (org_id uuid NOT NULL, body text NOT NULL)",
                        "ALTER TABLE notes ENABLE ROW LEVEL SECURITY",
                        "ALTER TABLE notes FORCE ROW LEVEL SECURITY",
                        "CREATE POLICY notes_org ON notes " +
                            "USING (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid)) " +
                            "WITH CHECK (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid))",
                        "GRANT SELECT ON notes TO $TENANT_ROLE",
                        "GRANT SELECT, INSERT ON notes TO ${database.servingRole}",
                    ).forEach { database.owner.execute(it).getOrThrow() }
                    val jdbc = JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE)
                    try {
                        block(jdbc)
                    } finally {
                        jdbc.close()
                    }
                } finally {
                    database.close()
                }
            }
        }
    }

    @Test
    fun anOrganizationsRowsAreWrittenAsTheServingRoleInsideItOnly() = asServingRole { jdbc ->
        jdbc.withOrganization(alpha) { Notes.insert { it[orgId] = alpha; it[body] = "alpha's" } }
        jdbc.withOrganization(beta) { Notes.insert { it[orgId] = beta; it[body] = "beta's" } }
        val seen = jdbc.withOrganization(alpha) { Notes.select(Notes.body).map { it[Notes.body] } }
        assertEquals(listOf("alpha's"), seen)
        assertFailsWith<ExposedSQLException>("Another organization's row is refused") {
            jdbc.withOrganization(alpha) { Notes.insert { it[orgId] = beta; it[body] = "planted" } }
        }
        assertFailsWith<ExposedSQLException>("The tenant role only reads the table") {
            jdbc.inOrg(alpha) { Notes.insert { it[orgId] = alpha; it[body] = "tenant" } }
        }
        val (role, org) = jdbc.withOrganization(alpha) { session() }
        assertEquals(false, role == TENANT_ROLE, "the role is not switched")
        assertEquals(alpha.toString(), org)
        assertNull(jdbc.query { session() }.second?.ifEmpty { null }, "the organization ends with its transaction")
    }

    @Test
    fun theLockIsHeldUntilTheTransactionEnds() = asServingRole { jdbc ->
        val held = CountDownLatch(1)
        val tried = CountDownLatch(1)
        coroutineScope {
            val holder = async {
                jdbc.query {
                    lockUntilCommit("chain")
                    held.countDown()
                    tried.await(LATCH_SECONDS, TimeUnit.SECONDS)
                }
            }
            held.await(LATCH_SECONDS, TimeUnit.SECONDS)
            assertEquals(false, jdbc.query { tryLock("chain") }, "taken by the other transaction")
            assertEquals(true, jdbc.query { tryLock("another chain") }, "another key is free")
            tried.countDown()
            holder.await()
        }
        assertEquals(true, jdbc.query { tryLock("chain") }, "released at the commit")
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.session(): Pair<String, String?> =
        exec("SELECT current_user, current_setting('ostrom.org_id', true)") { result ->
            result.next()
            result.getString(1) to result.getString(2)
        }!!

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.tryLock(key: String): Boolean =
        exec("SELECT pg_try_advisory_xact_lock(hashtext('$key'))") { result ->
            result.next()
            result.getBoolean(1)
        }!!

    private companion object {
        const val LATCH_SECONDS = 10L
    }
}
