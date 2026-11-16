// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts

import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.receipts.infrastructure.ReceiptsMigrations
import run.ostrom.server.testing.TestDatabase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Receipts migration 3 names the session and the person of every receipt signed before it (TeamCode #238): the one
 * UPDATE the append-only trigger lets through, inside the migration, which leaves the trigger and row level security
 * as they were. The sessions table is reduced here to the columns the migration reads, row level security forced as
 * the sessions module's.
 */
class ReceiptsOwnerMigrationPostgresTest {
    private val sessions = """
        CREATE TABLE sessions_sessions (org_id UUID NOT NULL, id TEXT NOT NULL, dev TEXT NOT NULL);
        ALTER TABLE sessions_sessions ENABLE ROW LEVEL SECURITY;
        ALTER TABLE sessions_sessions FORCE ROW LEVEL SECURITY;
        CREATE POLICY sessions_sessions_org ON sessions_sessions
            USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
    """.trimIndent()

    private fun receipt(id: String, kind: String, subject: String, body: String) =
        "INSERT INTO receipts_receipts (org_id, id, subject_kind, subject_id, body, created_at) " +
            "VALUES ('$ORG_A', '$id', '$kind', '$subject', '$body', now())"

    private fun <T> JdbcTransaction.rowsOf(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        exec(sql) { rs -> buildList { while (rs.next()) add(row(rs)) } }.orEmpty()

    @Test
    fun theReceiptsSignedBeforeNameTheirSessionAndItsPerson() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val migrator = database.migrationRunner(JsonLogger(LogLevel.WARN))
            migrator.migrate("receipts", ReceiptsMigrations.take(2))
            database.ownerJdbc.query { exec(sessions) }
            val fixtures = database.superuserJdbc
            fixtures.query {
                exec("INSERT INTO sessions_sessions VALUES ('$ORG_A', 's-1', 'lyes')")
                exec(receipt("rcpt_session", "session", "s-1", "{}"))
                exec(receipt("rcpt_gate", "gate", "g-1", """{"sessionId": "s-1"}"""))
                exec(receipt("rcpt_gone", "gate", "g-2", """{"sessionId": "s-gone"}"""))
                exec(receipt("rcpt_harness", "harness", "h-1", "{}"))
            }

            migrator.migrate("receipts", ReceiptsMigrations)

            val rows = fixtures.query {
                rowsOf("SELECT id, session_id, member FROM receipts_receipts ORDER BY id") { rs ->
                    Triple(rs.getString("id"), rs.getString("session_id"), rs.getString("member"))
                }
            }
            assertEquals(
                listOf(
                    Triple("rcpt_gate", "s-1", "lyes"),
                    Triple("rcpt_gone", "s-gone", null),
                    Triple("rcpt_harness", null, null),
                    Triple("rcpt_session", "s-1", "lyes"),
                ),
                rows,
            )
            // The trigger is back, for every role, and so is row level security, on both tables.
            val refused = assertFailsWith<ExposedSQLException> {
                fixtures.query { exec("UPDATE receipts_receipts SET member = 'mallory'") }
            }
            assertTrue("append-only" in refused.message.orEmpty(), refused.message)
            val forced = fixtures.query {
                rowsOf(
                    "SELECT relname FROM pg_class WHERE relforcerowsecurity " +
                        "AND relname IN ('receipts_receipts', 'sessions_sessions') ORDER BY relname",
                ) { rs -> rs.getString("relname") }
            }
            assertEquals(listOf("receipts_receipts", "sessions_sessions"), forced)
        } finally {
            database.close()
        }
    }

    @Test
    fun aDatabaseWithoutSessionsYetLeavesTheOwnersUnknown() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val migrator = database.migrationRunner(JsonLogger(LogLevel.WARN))
            migrator.migrate("receipts", ReceiptsMigrations.take(2))
            database.superuserJdbc.query { exec(receipt("rcpt_session", "session", "s-1", "{}")) }
            migrator.migrate("receipts", ReceiptsMigrations)
            val row = database.superuserJdbc.query {
                rowsOf("SELECT session_id, member FROM receipts_receipts") { rs ->
                    rs.getString("session_id") to rs.getString("member")
                }
            }.single()
            assertEquals("s-1" to null, row)
        } finally {
            database.close()
        }
    }
}
