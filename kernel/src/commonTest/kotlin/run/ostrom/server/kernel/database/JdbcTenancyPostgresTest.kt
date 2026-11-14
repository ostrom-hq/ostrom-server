// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The Exposed side of tenant isolation (S2-01): [JdbcDatabase.inOrg] on a serving role like a deployment's, and the
 * `TIMESTAMPTZ` column of [instant].
 */
class JdbcTenancyPostgresTest {
    private val alpha = Uuid.random()
    private val beta = Uuid.random()
    private val t0 = Instant.parse("2026-11-14T09:30:00.123456Z")

    /** A tenant table as a module declares it, with an instant. */
    private object Notes : Table("notes") {
        val orgId = uuid("org_id")
        val body = text("body")
        val at = instant("at")
    }

    // On the real clock: the pool's waits are real.
    private fun asServingRole(block: suspend (JdbcDatabase) -> Unit) {
        runTest {
            withContext(Dispatchers.Default) {
                val database = TestDatabase.create() ?: return@withContext
                try {
                    listOf(
                        "CREATE TABLE notes (org_id uuid NOT NULL, body text NOT NULL, at timestamptz NOT NULL)",
                        "ALTER TABLE notes ENABLE ROW LEVEL SECURITY",
                        "ALTER TABLE notes FORCE ROW LEVEL SECURITY",
                        "CREATE POLICY notes_org ON notes " +
                            "USING (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid)) " +
                            "WITH CHECK (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid))",
                        "GRANT SELECT, INSERT, UPDATE, DELETE ON notes TO $TENANT_ROLE",
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

    private fun JdbcTransaction.insert(org: Uuid, text: String) {
        Notes.insert {
            it[orgId] = org
            it[body] = text
            it[at] = t0
        }
    }

    private fun JdbcTransaction.count(): Long = Notes.selectAll().count()

    @Test
    fun anOrganizationSeesAndWritesOnlyItsOwnRows() = asServingRole { jdbc ->
        jdbc.inOrg(alpha) { insert(alpha, "alpha's") }
        jdbc.inOrg(beta) { insert(beta, "beta's") }
        assertEquals(1L, jdbc.inOrg(alpha) { count() })
        assertEquals(listOf("beta's"), jdbc.inOrg(beta) { Notes.select(Notes.body).map { it[Notes.body] } })
        assertFailsWith<ExposedSQLException>("A row of another organization is refused") {
            jdbc.inOrg(alpha) { insert(beta, "planted") }
        }
        val moved = jdbc.inOrg(alpha) { Notes.update({ Notes.orgId eq beta }) { it[body] = "touched" } }
        assertEquals(0, moved, "Another organization's rows cannot be updated")
    }

    @Test
    fun outsideAnOrganizationTheServingRoleSeesNoRow() = asServingRole { jdbc ->
        jdbc.inOrg(alpha) { insert(alpha, "alpha's") }
        assertEquals(0L, jdbc.query { count() }, "Fail closed: no organization, no row")
        assertFailsWith<ExposedSQLException> { jdbc.query { insert(alpha, "outside") } }
    }

    @Test
    fun neitherTheRoleNorTheOrganizationOutliveTheTransaction() = asServingRole { jdbc ->
        // A pool of 2: the next transactions run on the connection inOrg used, or on its one neighbor.
        repeat(2) { jdbc.inOrg(alpha) { count() } }
        repeat(2) {
            val (role, org) = jdbc.query {
                exec("SELECT current_user, current_setting('ostrom.org_id', true)") { result ->
                    result.next()
                    result.getString(1) to result.getString(2)
                }!!
            }
            assertEquals(false, role == TENANT_ROLE, "the role is back to the serving one")
            assertNull(org?.ifEmpty { null }, "no organization is left on the connection")
        }
    }

    @Test
    fun aFailureRollsTheTransactionBack() = asServingRole { jdbc ->
        assertFailsWith<IllegalStateException> {
            jdbc.inOrg(alpha) {
                insert(alpha, "rolled back")
                error("use case failed")
            }
        }
        assertEquals(0L, jdbc.inOrg(alpha) { count() })
    }

    @Test
    fun anInstantReadsBackAsWrittenRoundedToTheMicrosecond() = asServingRole { jdbc ->
        jdbc.inOrg(alpha) { insert(alpha, "timed") }
        assertEquals(t0, jdbc.inOrg(alpha) { Notes.select(Notes.at).single()[Notes.at] })
        val nanos = Instant.parse("2026-11-14T09:30:00.123456789Z")
        jdbc.inOrg(alpha) { Notes.update({ Notes.orgId eq alpha }) { it[at] = nanos } }
        val rounded = Instant.parse("2026-11-14T09:30:00.123457Z")
        assertEquals(rounded, jdbc.inOrg(alpha) { Notes.select(Notes.at).single()[Notes.at] }, "rounded to micros")
    }

    @Test
    fun theColumnTypeConvertsWhatTheDriverGives() {
        val type = InstantColumnType()
        assertEquals("TIMESTAMP WITH TIME ZONE", type.sqlType())
        assertEquals(t0, type.valueFromDB(t0))
        assertEquals(t0, type.valueFromDB(OffsetDateTime.parse("2026-11-14T11:30:00.123456+02:00")))
        assertFailsWith<IllegalStateException> { type.valueFromDB("2026-11-14") }
        assertEquals(OffsetDateTime.of(2026, 11, 14, 9, 30, 0, 123_456_000, ZoneOffset.UTC), type.notNullValueToDB(t0))
        assertEquals("'2026-11-14T09:30:00.123456Z'", type.nonNullValueToString(t0))
    }
}
