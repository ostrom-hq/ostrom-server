// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.infrastructure.TERMINAL_HOST_FIND_SQL
import run.ostrom.server.runners.infrastructure.TERMINAL_HOST_HOLD_SQL
import run.ostrom.server.runners.infrastructure.TERMINAL_HOST_PURGE_SQL
import run.ostrom.server.runners.infrastructure.TERMINAL_HOST_RELEASE_SQL
import run.ostrom.server.runners.infrastructure.TERMINAL_HOST_RENEW_SQL
import run.ostrom.server.runners.infrastructure.TerminalHostsImpl
import run.ostrom.server.runners.infrastructure.inTenant
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
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

    private fun TestServer.replica(url: String) = TerminalHostsImpl(driver, url)

    private suspend fun TestServer.count(org: Uuid): Long = driver.inTenant(org) {
        fetchAll("SELECT count(*) AS n FROM runners_terminal_hosts").getOrThrow().single().get("n").asLong()
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

        fun explain(sql: String) = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", ORG_A)
        fun held(sql: String) = explain(sql).bind("session_id", session).bind("epoch", 1L).bind("url", "http://a")
        mapOf(
            "the expired dropped" to explain(TERMINAL_HOST_PURGE_SQL),
            "a hold taken" to held(TERMINAL_HOST_HOLD_SQL),
            "a hold renewed" to held(TERMINAL_HOST_RENEW_SQL),
            "a hold given back" to held(TERMINAL_HOST_RELEASE_SQL),
            "a host found" to explain(TERMINAL_HOST_FIND_SQL).bind("session_id", session),
        ).forEach { (what, statement) ->
            val plan = server.driver.inTenant(ORG_A) {
                fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
            }
            assertFalse("Seq Scan" in plan, "$what scans the table:\n$plan")
        }
    }
}
