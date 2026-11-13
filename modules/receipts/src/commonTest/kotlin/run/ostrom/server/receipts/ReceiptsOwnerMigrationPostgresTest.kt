// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts

import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.receipts.infrastructure.ReceiptsMigrations
import run.ostrom.server.testing.TestDatabase
import io.github.smyrgeorge.sqlx4k.SQLError
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

    @Test
    fun theReceiptsSignedBeforeNameTheirSessionAndItsPerson() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val migrator = MigrationRunnerImpl(database.owner, JsonLogger(LogLevel.WARN))
            migrator.migrate("receipts", ReceiptsMigrations.take(2))
            database.owner.execute(sessions).getOrThrow()
            val fixtures = database.superuser
            fixtures.execute("INSERT INTO sessions_sessions VALUES ('$ORG_A', 's-1', 'lyes')").getOrThrow()
            fixtures.execute(receipt("rcpt_session", "session", "s-1", "{}")).getOrThrow()
            fixtures.execute(receipt("rcpt_gate", "gate", "g-1", """{"sessionId": "s-1"}""")).getOrThrow()
            fixtures.execute(receipt("rcpt_gone", "gate", "g-2", """{"sessionId": "s-gone"}""")).getOrThrow()
            fixtures.execute(receipt("rcpt_harness", "harness", "h-1", "{}")).getOrThrow()

            migrator.migrate("receipts", ReceiptsMigrations)

            val rows = fixtures.fetchAll("SELECT id, session_id, member FROM receipts_receipts ORDER BY id")
                .getOrThrow().map {
                    val member = it.get("member").asStringOrNull()
                    Triple(it.get("id").asString(), it.get("session_id").asStringOrNull(), member)
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
            val refused = assertFailsWith<SQLError> {
                fixtures.execute("UPDATE receipts_receipts SET member = 'mallory'").getOrThrow()
            }
            assertTrue("append-only" in refused.message.orEmpty(), refused.message)
            val forced = fixtures.fetchAll(
                "SELECT relname FROM pg_class WHERE relforcerowsecurity " +
                    "AND relname IN ('receipts_receipts', 'sessions_sessions') ORDER BY relname",
            ).getOrThrow().map { it.get("relname").asString() }
            assertEquals(listOf("receipts_receipts", "sessions_sessions"), forced)
        } finally {
            database.close()
        }
    }

    @Test
    fun aDatabaseWithoutSessionsYetLeavesTheOwnersUnknown() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val migrator = MigrationRunnerImpl(database.owner, JsonLogger(LogLevel.WARN))
            migrator.migrate("receipts", ReceiptsMigrations.take(2))
            database.superuser.execute(receipt("rcpt_session", "session", "s-1", "{}")).getOrThrow()
            migrator.migrate("receipts", ReceiptsMigrations)
            val row = database.superuser.fetchAll("SELECT session_id, member FROM receipts_receipts")
                .getOrThrow().single()
            assertEquals("s-1" to null, row.get("session_id").asStringOrNull() to row.get("member").asStringOrNull())
        } finally {
            database.close()
        }
    }
}
