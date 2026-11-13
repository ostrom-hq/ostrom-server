// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AdmissionEntry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.QueueCounts
import run.ostrom.server.gateway.domain.QueueRefusedException
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.gateway.infrastructure.ADMISSION_CANDIDATES_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_COUNTS_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_GIVE_BACK_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_LOCK_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_POLL_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_PURGE_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_RELEASE_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_RENEW_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_REQUEUE_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_RUNNING_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_RUN_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_START_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_TURNS_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_TURN_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_WAITED_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_WAITING_SQL
import run.ostrom.server.gateway.infrastructure.ADMISSION_WAIT_SQL
import run.ostrom.server.gateway.infrastructure.AdmissionQueueRepositoryImpl
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The admission queue on real PostgreSQL, as the serving role under row-level security, with two repositories over
 * one database as two replicas: turns, classes, a member's limit and the bounds hold whichever replica a call polls,
 * and a replica that stops polling never holds the turn.
 */
class AdmissionQueuePostgresTest {
    private val modules = listOf(SecretsModule, SharingModule, GatewayModule)
    private val overrides = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }
    private val ada = Uuid.random()
    private val bob = Uuid.random()

    /** A call of [member]; Ada and Bob have a limit (turns are taken) unless [limited] is empty. */
    private fun call(
        member: Uuid,
        workload: Workload = Workload.INTERACTIVE,
        limit: Int? = null,
        org: Uuid = Gw.orgA,
        limited: Set<String> = setOf(ada.toString(), bob.toString()),
    ) = AdmissionRequest(Uuid.random(), org, member, "claude", workload, limit, limited)

    private fun TestServer.replicas(): List<AdmissionQueueRepository> =
        listOf(koin.get<AdmissionQueueRepository>(), AdmissionQueueRepositoryImpl(driver))

    @Test
    fun membersTakeTurnsAndInteractiveCallsGoFirstOnEitherReplica() = serverTest(modules, overrides) { server ->
        val (one, two) = server.replicas()
        val adaBackground = call(ada, Workload.BACKGROUND)
        assertEquals(AdmissionEntry.Admitted, one.enter(adaBackground, first = true), "An empty queue admits at once")
        one.requeue(Gw.orgA, adaBackground.id)

        val bobInteractive = call(bob)
        assertEquals(AdmissionEntry.Admitted, two.enter(bobInteractive, first = true), "Interactive goes first")
        two.requeue(Gw.orgA, bobInteractive.id)
        assertEquals(AdmissionEntry.Waiting(blocked = false), one.enter(adaBackground, first = false))
        assertEquals(AdmissionEntry.Admitted, two.enter(bobInteractive, first = false))

        val bobBackground = call(bob, Workload.BACKGROUND)
        assertEquals(
            AdmissionEntry.Waiting(blocked = false),
            two.enter(bobBackground, first = true),
            "Bob was served last: Ada's waiting call has the turn",
        )
        assertEquals(AdmissionEntry.Admitted, one.enter(adaBackground, first = false))
        assertEquals(QueueCounts(1, 1), one.counts(Gw.orgA, bob))
        assertEquals(QueueCounts(1, 0), two.counts(Gw.orgA, ada))
        assertEquals(QueueCounts(0, 0), two.counts(Gw.orgB, ada), "Another org sees nothing")
        listOf(adaBackground, bobInteractive, bobBackground).forEach { one.release(Gw.orgA, it.id) }
        assertEquals(QueueCounts(0, 0), one.counts(Gw.orgA, bob))
    }

    @Test
    fun aMemberAtTheirLimitWaitsOnEitherReplicaAndAtMostEightOfTheirCallsWait() = serverTest(modules, overrides) {
        val (one, two) = it.replicas()
        val running = call(ada, limit = 1)
        assertEquals(AdmissionEntry.Admitted, one.enter(running, first = true))
        val next = call(ada, limit = 1)
        assertEquals(AdmissionEntry.Waiting(blocked = true), two.enter(next, first = true))
        assertEquals(AdmissionEntry.Admitted, one.enter(call(bob), first = true), "Another member is not held back")
        assertEquals(AdmissionEntry.Waiting(blocked = true), two.enter(next, first = false))
        one.release(Gw.orgA, running.id)
        assertEquals(AdmissionEntry.Admitted, two.enter(next, first = false), "Its member's call ended")

        repeat(AdmissionQueueRepository.MEMBER_BOUND) {
            assertEquals(AdmissionEntry.Waiting(blocked = true), one.enter(call(ada, limit = 1), first = true))
        }
        val full = assertFailsWith<QueueRefusedException> { two.enter(call(ada, limit = 1), first = true) }
        assertEquals(RefusalKind.QUEUE_FULL, full.kind)
        val expired = assertFailsWith<QueueRefusedException> { one.enter(call(ada), first = false) }
        assertEquals(RefusalKind.QUEUE_EXPIRED, expired.kind, "A place never written, or gone, is lost")
    }

    @Test
    fun withoutALimitAmongTheWaitingCallsEveryArrivalRunsButTheBoundsHold() = serverTest(modules, overrides) { server ->
        // TeamCode #87: with 0 (unlimited) the default, taking turns let one waiting call through per poll.
        val (one, two) = server.replicas()
        val bobs = List(3) { call(bob, limited = emptySet()) }
        for (waiting in bobs) {
            assertEquals(AdmissionEntry.Admitted, one.enter(waiting, first = true))
            one.requeue(Gw.orgA, waiting.id)
        }
        assertEquals(AdmissionEntry.Admitted, two.enter(call(ada, limited = emptySet()), first = true))
        assertTrue(bobs.all { two.enter(it, first = false) == AdmissionEntry.Admitted }, "Every waiting call runs")

        server.database.superuser.execute(waiting(Gw.orgA, count = 128, polledSecondsAgo = 0)).getOrThrow()
        val full = assertFailsWith<QueueRefusedException> { one.enter(call(ada, limited = emptySet()), first = true) }
        assertEquals(RefusalKind.QUEUE_FULL, full.kind, "An arrival is bounded even when it would run")
    }

    @Test
    fun aCallPutBackToWaitGivesItsTurnBack() = serverTest(modules, overrides) { server ->
        val (one, two) = server.replicas()
        for (served in listOf(call(ada), call(bob))) {
            assertEquals(AdmissionEntry.Admitted, one.enter(served, first = true))
            one.release(Gw.orgA, served.id)
        }
        // Ada served first, then Bob. Ada's next call takes a turn, finds the accounts busy and goes back to wait.
        val busy = call(ada)
        assertEquals(AdmissionEntry.Admitted, two.enter(busy, first = true))
        two.requeue(Gw.orgA, busy.id)
        val bobs = call(bob)
        assertEquals(
            AdmissionEntry.Waiting(blocked = false),
            one.enter(bobs, first = true),
            "Ada was served longest ago again: her waiting call goes first",
        )
        assertEquals(AdmissionEntry.Admitted, two.enter(busy, first = false))
        // A later turn of the same member is never undone.
        one.release(Gw.orgA, bobs.id)
        assertEquals(AdmissionEntry.Admitted, one.enter(call(ada), first = true))
        two.requeue(Gw.orgA, busy.id)
        val turn = server.database.superuser.fetchAll(
            "SELECT turn FROM gateway_admission_turns WHERE member_id = CAST('$ada' AS uuid)",
        ).getOrThrow().single().get("turn").asString().toLong()
        assertEquals(4L, turn)
    }

    @Test
    fun twoReplicasNeverAdmitPastAMembersLimitTogether() = serverTest(modules, overrides) { server ->
        val replicas = server.replicas()
        val entries = withContext(Dispatchers.Default) {
            coroutineScope {
                List(10) { index -> async { replicas[index % 2].enter(call(ada, limit = 3), first = true) } }.awaitAll()
            }
        }
        assertEquals(3, entries.count { it == AdmissionEntry.Admitted })
        assertEquals(7, entries.count { it == AdmissionEntry.Waiting(blocked = true) })
    }

    @Test
    fun theOrgQueueHoldsAHundredAndTwentyEightCallsAndADeadReplicasCallNeverHoldsTheTurn() =
        serverTest(modules, overrides) { server ->
            val (one, _) = server.replicas()
            val fixtures = server.database.superuser
            // A call whose replica stopped polling ten seconds ago: older, interactive, still within its lease.
            fixtures.execute(waiting(Gw.orgA, count = 1, polledSecondsAgo = 10)).getOrThrow()
            assertEquals(AdmissionEntry.Admitted, one.enter(call(bob), first = true))

            fixtures.execute(waiting(Gw.orgA, count = 127, polledSecondsAgo = 0)).getOrThrow()
            val full = assertFailsWith<QueueRefusedException> { one.enter(call(ada), first = true) }
            assertEquals(RefusalKind.QUEUE_FULL, full.kind)

            // Expired places are dropped as the next call enters.
            fixtures.execute("UPDATE gateway_admissions SET expires_at = now() WHERE NOT running").getOrThrow()
            assertEquals(AdmissionEntry.Admitted, one.enter(call(ada), first = true))
            assertEquals(QueueCounts(0, 0), one.counts(Gw.orgA, ada))
        }

    @Test
    fun aRunningCallsLeaseIsRenewedForFifteenMinutes() = serverTest(modules, overrides) { server ->
        val (one, _) = server.replicas()
        val running = call(ada)
        one.enter(running, first = true)
        server.database.superuser.execute("UPDATE gateway_admissions SET expires_at = now() + INTERVAL '1 minute'")
            .getOrThrow()
        one.renew(Gw.orgA, running.id)
        val minutes = server.database.superuser.fetchAll(
            "SELECT CAST(EXTRACT(EPOCH FROM (expires_at - now())) / 60 AS BIGINT) AS m FROM gateway_admissions",
        ).getOrThrow().first().get("m").asString().toLong()
        assertTrue(minutes in 14..15, "Renewed for 15 minutes: $minutes")
    }

    /** [count] waiting interactive calls of [org], arrived a minute ago, last polled [polledSecondsAgo] ago. */
    private fun waiting(org: Uuid, count: Int, polledSecondsAgo: Int) =
        "INSERT INTO gateway_admissions (id, org_id, member_id, scope, workload, running, created_at, ready_at, " +
            "polled_at, expires_at) SELECT gen_random_uuid(), CAST('$org' AS uuid), gen_random_uuid(), 'claude', " +
            "'INTERACTIVE', FALSE, now() - INTERVAL '1 minute', now() - INTERVAL '1 minute', " +
            "now() - INTERVAL '$polledSecondsAgo seconds', now() + INTERVAL '10 seconds' " +
            "FROM generate_series(1, $count) AS i"

    @Test
    fun theQueriesOfAnAdmissionScanNoTable() = serverTest(modules, overrides) { server ->
        // Fixtures only: thousands of calls and turns of another org, and the statistics.
        val fixtures = server.database.superuser
        fixtures.execute(waiting(Gw.orgB, count = 5_000, polledSecondsAgo = 0)).getOrThrow()
        fixtures.execute(
            "INSERT INTO gateway_admission_turns (org_id, scope, member_id, turn) " +
                "SELECT org_id, scope, member_id, 1 FROM gateway_admissions",
        ).getOrThrow()
        listOf("gateway_admissions", "gateway_admission_turns").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }
        mapOf(
            "the org locked" to ADMISSION_LOCK_SQL,
            "the expired dropped" to ADMISSION_PURGE_SQL,
            "how long it waited" to ADMISSION_WAITED_SQL,
            "the member's running calls" to ADMISSION_RUNNING_SQL,
            "the other waiting calls" to ADMISSION_CANDIDATES_SQL,
            "the turns" to ADMISSION_TURNS_SQL,
            "a call run at once" to ADMISSION_RUN_SQL,
            "a waiting call run" to ADMISSION_START_SQL,
            "the turn taken" to ADMISSION_TURN_SQL,
            "the bounds" to ADMISSION_WAITING_SQL,
            "a call that waits" to ADMISSION_WAIT_SQL,
            "a poll" to ADMISSION_POLL_SQL,
            "a turn given back" to ADMISSION_GIVE_BACK_SQL,
            "a call back to wait" to ADMISSION_REQUEUE_SQL,
            "a lease renewed" to ADMISSION_RENEW_SQL,
            "a call released" to ADMISSION_RELEASE_SQL,
            "the counts" to ADMISSION_COUNTS_SQL,
        ).forEach { (what, sql) ->
            val plan = plan(server, sql)
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }

    private suspend fun plan(server: TestServer, sql: String): String {
        val values = mapOf(
            "org_id" to Gw.orgA,
            "key" to "gateway_admissions:${Gw.orgA}",
            "member_id" to ada,
            "scope" to "claude",
            "workload" to "INTERACTIVE",
            "turn" to 1L,
            "before" to 0L,
            "ready_in" to 0L,
            "id" to Uuid.random(),
        )
        val explained = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql")
        val statement = values.entries.fold(explained) { bound, (name, value) ->
            if (":$name" in sql) bound.bind(name, value) else bound
        }
        return server.driver.inTenant(Gw.orgA) {
            fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
    }
}
