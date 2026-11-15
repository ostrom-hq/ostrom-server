// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.audit

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.module.serverKoin
import run.ostrom.server.testing.TestDatabase
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Until the identity module binds it, a change journaled in its transaction writes nothing and the change goes on. */
class TransactionalOrgAuditTrailPostgresTest {
    @Test
    fun theDefaultTrailWritesNothingAndLeavesTheTransactionAlone() = runTest {
        val trail = serverKoin(module { single<Clock> { Clock.System } }, emptyList()).get<TransactionalOrgAuditTrail>()
        assertIs<DiscardingTransactionalOrgAuditTrail>(trail)
        val database = TestDatabase.create() ?: return@runTest
        try {
            val actor = AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet())
            val record = OrgAuditRecord(Uuid.random(), actor, "cloud.session.paused", "cloud_session", "s")
            val answer = JdbcDatabase(database.servingConfig, JdbcDatabase.MIN_POOL_SIZE).use { jdbc ->
                trail.recording(jdbc, record) { append ->
                    assertEquals(true, append(), "Nothing to chain: the change goes on")
                    one()
                }
            }
            assertEquals(1, answer)
        } finally {
            database.close()
        }
    }

    private fun JdbcTransaction.one(): Int =
        exec("SELECT 1 AS n") { rs -> rs.next(); rs.getInt("n") } ?: error("No row.")
}
