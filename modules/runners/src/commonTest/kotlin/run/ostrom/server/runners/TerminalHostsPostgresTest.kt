// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.infrastructure.RunnersTerminalHosts
import run.ostrom.server.runners.infrastructure.TerminalHostsImpl
import run.ostrom.server.runners.infrastructure.terminalHostFind
import run.ostrom.server.runners.infrastructure.terminalHostHold
import run.ostrom.server.runners.infrastructure.terminalHostPurge
import run.ostrom.server.runners.infrastructure.terminalHostRelease
import run.ostrom.server.runners.infrastructure.terminalHostRenew
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Which replica holds each runner's socket, on real PostgreSQL (TC-REP-1): the newest execution wins, a replica only
 * renews or gives back its own hold, a hold not renewed ends with its lease by the database's clock, and an
 * organization sees only its own.
 */
class TerminalHostsPostgresTest {
    private val session = Uuid.random()

    private fun TestServer.replica(url: String) = TerminalHostsImpl(koin.get<JdbcDatabase>(), url)

    private suspend fun TestServer.count(org: Uuid): Long = koin.get<JdbcDatabase>().inOrg(org) {
        RunnersTerminalHosts.selectAll().count()
    }

    @Test
    fun theNewestExecutionsReplicaHoldsTheSession() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val (a, b) = server.replica("http://a") to server.replica("http://b")
        assertNull(a.host(ORG_A, session))

        a.hold(ORG_A, session, 2)
        assertEquals("http://a", b.host(ORG_A, session))
        b.hold(ORG_A, session, 1)
        assertEquals("http://a", b.host(ORG_A, session), "An older execution takes nothing over")
        b.hold(ORG_A, session, 2)
        assertEquals("http://b", a.host(ORG_A, session), "The runner came back to another replica")

        a.renew(ORG_A, session, 2)
        a.release(ORG_A, session, 2)
        assertEquals("http://b", a.host(ORG_A, session), "A replica renews and gives back only its own hold")
        b.release(ORG_A, session, 1)
        assertEquals("http://b", a.host(ORG_A, session), "Nor another execution's")
        assertNull(a.host(ORG_B, session), "Another organization sees no hold")

        b.release(ORG_A, session, 2)
        assertNull(a.host(ORG_A, session))
    }

    @Test
    fun aLostHoldIsWrittenAgainAtTheNextRenewal() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val (a, b) = server.replica("http://a") to server.replica("http://b")
        a.renew(ORG_A, session, 1)
        assertEquals("http://a", b.host(ORG_A, session), "The first hold failed: the renewal writes it")
        b.renew(ORG_A, session, 1)
        assertEquals("http://a", b.host(ORG_A, session), "Never over another replica's live hold of that execution")
        b.renew(ORG_A, session, 2)
        assertEquals("http://b", a.host(ORG_A, session), "A newer execution's is taken")
        a.renew(ORG_A, session, 1)
        assertEquals("http://b", a.host(ORG_A, session), "An older one's never")

        server.database.superuser.execute("UPDATE runners_terminal_hosts SET expires_at = now()").getOrThrow()
        a.renew(ORG_A, session, 1)
        assertEquals("http://a", b.host(ORG_A, session), "A hold past its lease is anyone's")
    }

    @Test
    fun aHoldNotRenewedEndsWithItsLease() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val a = server.replica("http://a")
        val other = Uuid.random()
        a.hold(ORG_A, session, 1)
        a.hold(ORG_A, other, 1)
        // The lease past, by the database's clock: the replica died.
        server.database.superuser.execute("UPDATE runners_terminal_hosts SET expires_at = now()").getOrThrow()
        assertNull(a.host(ORG_A, session))
        a.renew(ORG_A, other, 1)
        assertEquals("http://a", a.host(ORG_A, other), "A renewal extends the lease")

        server.database.superuser.execute("UPDATE runners_terminal_hosts SET expires_at = now()").getOrThrow()
        server.replica("http://b").hold(ORG_A, Uuid.random(), 1)
        assertEquals(1L, server.count(ORG_A), "The holds past their lease are dropped at the next hold")
    }

    @Test
    fun theQueriesOfAHoldScanNoTable() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        // Fixtures only: the holds of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        listOf(ORG_B to 20_000, ORG_A to 200).forEach { (org, count) ->
            fixtures.execute(
                "INSERT INTO runners_terminal_hosts (session_id, org_id, epoch, replica_url, expires_at) " +
                    "SELECT gen_random_uuid(), CAST('$org' AS uuid), 1, 'http://r' || (i % 3), " +
                    "now() + (i % 90) * INTERVAL '1 second' FROM generate_series(1, $count) AS i",
            ).getOrThrow()
        }
        fixtures.execute("VACUUM ANALYZE runners_terminal_hosts").getOrThrow()

        val database = server.koin.get<JdbcDatabase>()
        mapOf<String, StatementBuilder.() -> Statement<*>>(
            "the expired dropped" to { terminalHostPurge(ORG_A) },
            "a hold taken" to { terminalHostHold(ORG_A, session, 1L, "http://a") },
            "a hold renewed" to { terminalHostRenew(ORG_A, session, 1L, "http://a") },
            "a hold given back" to { terminalHostRelease(ORG_A, session, 1L, "http://a") },
            "a host found" to { terminalHostFind(ORG_A, session) },
        ).forEach { (what, statement) ->
            val plan = database.inOrg(ORG_A) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan" in plan, "$what scans the table:\n$plan")
        }
    }
}
