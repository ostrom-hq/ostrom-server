// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.infrastructure.RunnerTokensImpl
import run.ostrom.server.runners.infrastructure.RunnersTerminalTickets
import run.ostrom.server.runners.infrastructure.TerminalTicketsImpl
import run.ostrom.server.runners.infrastructure.ticketInsert
import run.ostrom.server.runners.infrastructure.ticketPurge
import run.ostrom.server.runners.infrastructure.ticketRedeem
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The terminal tickets across replicas on real PostgreSQL (TC-REP-2): issued by one, redeemed by another, once, within
 * a minute of the database's clock, inside their organization only.
 */
class TerminalTicketsPostgresTest {
    private val alice = Caller(ORG_A, "alice", admin = true)
    private val session = Uuid.random()

    private fun TestServer.replica() = TerminalTicketsImpl(koin.get<JdbcDatabase>(), RunnerTokensImpl())

    private suspend fun TestServer.count(org: Uuid): Long = koin.get<JdbcDatabase>().inOrg(org) {
        RunnersTerminalTickets.selectAll().count()
    }

    @Test
    fun aTicketIssuedByOneReplicaOpensTheTerminalOnAnotherOnce() =
        serverTest(listOf(RunnersModule), testIdentities) { server ->
            val (a, b) = server.replica() to server.replica()
            val before = Clock.System.now()
            val ticket = a.issue(alice, session)
            assertTrue(ticket.value.startsWith("ostrom_term_${ORG_A.toHexString()}_"))
            assertTrue(ticket.expiresAt in before + 50.seconds..before + 70.seconds, "A minute's life")

            assertEquals(alice, b.redeem(ticket.value, session), "Redeemed on the other replica")
            assertNull(a.redeem(ticket.value, session), "Once")

            val other = a.issue(alice, session)
            assertNull(b.redeem(other.value, Uuid.random()), "Not on another session")
            assertNull(b.redeem(other.value, session), "And spent by the try")
        }

    @Test
    fun anExpiredMalformedOrForeignTicketOpensNothing() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val tickets = server.replica()
        val late = tickets.issue(alice, session)
        val forgotten = tickets.issue(alice, session)
        // A minute later, by the database's clock.
        server.database.superuserOutsideTransaction("UPDATE runners_terminal_tickets SET expires_at = now()")
        assertNull(tickets.redeem(late.value, session))
        val kept = tickets.issue(alice, session)
        assertEquals(1L, server.count(ORG_A), "The tickets past their end are dropped at the next issue")
        assertEquals(alice, tickets.redeem(kept.value, session))
        assertNull(tickets.redeem(forgotten.value, session))

        val secret = tickets.issue(alice, session).value.substringAfterLast('_')
        listOf(
            "ostrom_term_unknown",
            "nope",
            "ostrom_term_${ORG_A.toHexString()}",
            "ostrom_term_${"z".repeat(32)}_$secret",
            "ostrom_term_${ORG_B.toHexString()}_$secret",
        ).forEach { assertNull(tickets.redeem(it, session), it) }
        assertEquals(1L, server.count(ORG_A), "A ticket naming another organization spends nothing of this one")
        assertEquals(0L, server.count(ORG_B), "Org B sees none of A's tickets")
    }

    @Test
    fun theQueriesOfATicketScanNoTable() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        // Fixtures only: the tickets of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database
        listOf(ORG_B to 20_000, ORG_A to 200).forEach { (org, count) ->
            fixtures.superuserOutsideTransaction(
                "INSERT INTO runners_terminal_tickets (token_hash, org_id, session_id, member_id, admin, expires_at) " +
                    "SELECT md5('$org' || i), CAST('$org' AS uuid), gen_random_uuid(), 'm', false, " +
                    "now() + (i % 120) * INTERVAL '1 second' FROM generate_series(1, $count) AS i",
            )
        }
        fixtures.superuserOutsideTransaction("VACUUM ANALYZE runners_terminal_tickets")

        val database = server.koin.get<JdbcDatabase>()
        val alice = Caller(ORG_A, "alice", admin = true)
        mapOf<String, StatementBuilder.() -> Statement<*>>(
            "the expired dropped" to { ticketPurge(ORG_A) },
            "a ticket issued" to { ticketInsert(alice, session, "h", 60.seconds) },
            "a ticket redeemed" to { ticketRedeem(ORG_A, "h") },
        ).forEach { (what, statement) ->
            val plan = database.inOrg(ORG_A) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan" in plan, "$what scans the table:\n$plan")
        }
    }
}
