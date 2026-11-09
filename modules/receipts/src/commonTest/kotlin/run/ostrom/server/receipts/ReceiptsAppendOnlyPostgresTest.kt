// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts

import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * A signed receipt is never changed nor erased (AUDIT-SECU-GATES-2 G3): the serving role holds UPDATE and DELETE on
 * every table (default privileges) and sets `ostrom.org_id` itself, so the organization policy alone let it rewrite a
 * receipt or erase a refusal without a trace. One statement-level trigger refuses them, as on the audit log.
 */
class ReceiptsAppendOnlyPostgresTest {
    private fun receiptsTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(InstanceModule, ReceiptsModule)) { server -> block(server) }

    private suspend fun <T> TestServer.asServingRole(org: Uuid?, block: suspend Transaction.() -> T): T =
        driver.transaction {
            if (org != null) {
                execute(Statement.create("SELECT set_config('ostrom.org_id', :org, true)").bind("org", org.toString()))
                    .getOrThrow()
            }
            block()
        }

    private suspend fun refusedAsAppendOnly(what: String, block: suspend () -> Unit) {
        val error = assertFailsWith<SQLError>(what) { block() }
        assertTrue("append-only" in error.message.orEmpty(), "$what: ${error.message}")
    }

    private suspend fun TestServer.bodies(org: Uuid): List<String> = asServingRole(org) {
        fetchAll("SELECT body FROM receipts_receipts ORDER BY id").getOrThrow().map { it.get("body").asString() }
    }

    @Test
    fun aSignedReceiptIsNeverChangedNorErased() = receiptsTest { server ->
        server.koin.get<SignReceiptUseCase>()(ORG_A, draft("gate-1"))
        val signed = server.bodies(ORG_A)
        val rewrite = "UPDATE receipts_receipts SET body = '{}'"
        val erase = "DELETE FROM receipts_receipts"
        refusedAsAppendOnly("serving, A, rewrite") { server.asServingRole(ORG_A) { execute(rewrite).getOrThrow() } }
        refusedAsAppendOnly("serving, A, erase") { server.asServingRole(ORG_A) { execute(erase).getOrThrow() } }
        // A statement that reaches no row is refused all the same: never zero rows in silence.
        refusedAsAppendOnly("serving, B, erase") { server.asServingRole(ORG_B) { execute(erase).getOrThrow() } }
        val truncate = "TRUNCATE receipts_receipts"
        refusedAsAppendOnly("owner, truncate") { server.database.owner.execute(truncate).getOrThrow() }
        refusedAsAppendOnly("superuser, replica, erase") {
            server.database.superuser.transaction {
                execute("SET LOCAL session_replication_role = replica").getOrThrow()
                execute(erase).getOrThrow()
            }
        }
        assertEquals(signed, server.bodies(ORG_A))
        // Signing goes on.
        server.koin.get<SignReceiptUseCase>()(ORG_A, draft("gate-2"))
        assertEquals(2, server.bodies(ORG_A).size)
    }
}
