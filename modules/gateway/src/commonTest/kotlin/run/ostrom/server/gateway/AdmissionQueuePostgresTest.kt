// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AdmissionEntry
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.QueueCounts
import run.ostrom.server.gateway.domain.QueueRefusedException
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.gateway.infrastructure.AdmissionQueueRepositoryImpl
import run.ostrom.server.gateway.infrastructure.GatewayAdmissionTurns
import run.ostrom.server.gateway.infrastructure.admissionCandidates
import run.ostrom.server.gateway.infrastructure.admissionCounts
import run.ostrom.server.gateway.infrastructure.admissionGiveBack
import run.ostrom.server.gateway.infrastructure.admissionLockKey
import run.ostrom.server.gateway.infrastructure.admissionPoll
import run.ostrom.server.gateway.infrastructure.admissionPurge
import run.ostrom.server.gateway.infrastructure.admissionRelease
import run.ostrom.server.gateway.infrastructure.admissionRenew
import run.ostrom.server.gateway.infrastructure.admissionRequeue
import run.ostrom.server.gateway.infrastructure.admissionRun
import run.ostrom.server.gateway.infrastructure.admissionRunning
import run.ostrom.server.gateway.infrastructure.admissionStart
import run.ostrom.server.gateway.infrastructure.admissionTurn
import run.ostrom.server.gateway.infrastructure.admissionTurns
import run.ostrom.server.gateway.infrastructure.admissionWait
import run.ostrom.server.gateway.infrastructure.admissionWaited
import run.ostrom.server.gateway.infrastructure.admissionWaiting
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.advisoryLock
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.select
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
        listOf(koin.get<AdmissionQueueRepository>(), AdmissionQueueRepositoryImpl(koin.get<JdbcDatabase>()))

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
    fun aLaunchAtItsLimitWaitsOnEitherReplicaWhateverItsMemberRunsElsewhere() = serverTest(modules, overrides) {
        val (one, two) = it.replicas()
        fun launched(member: Uuid, launch: String, limit: Int? = 1) = AdmissionRequest(
            Uuid.random(), Gw.orgA, member, "claude", Workload.INTERACTIVE, null, emptySet(), launch, limit,
        )
        val first = launched(ada, "lch_1")
        assertEquals(AdmissionEntry.Admitted, one.enter(first, first = true))
        val second = launched(ada, "lch_1")
        assertEquals(AdmissionEntry.Waiting(blocked = true), two.enter(second, first = true), "The launch is full")
        assertEquals(
            AdmissionEntry.Admitted,
            two.enter(launched(ada, "lch_2"), first = true),
            "Another launch of the same member is not held back",
        )
        val uncapped = launched(ada, "lch_1", limit = null)
        assertEquals(AdmissionEntry.Admitted, one.enter(uncapped, first = true), "A call without a cap runs")
        one.release(Gw.orgA, uncapped.id)
        assertEquals(AdmissionEntry.Waiting(blocked = true), one.enter(second, first = false))
        one.release(Gw.orgA, first.id)
        assertEquals(AdmissionEntry.Admitted, two.enter(second, first = false), "The launch's call ended")

        assertEquals(AdmissionEntry.Admitted, one.enter(launched(bob, "lch_3"), first = true))
        repeat(AdmissionQueueRepository.MEMBER_BOUND) {
            assertEquals(AdmissionEntry.Waiting(blocked = true), one.enter(launched(bob, "lch_3"), first = true))
        }
        val full = assertFailsWith<QueueRefusedException> { two.enter(launched(bob, "lch_3"), first = true) }
        assertEquals(RefusalKind.QUEUE_FULL, full.kind, "A member whose launch is capped has the member's bound")
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

        server.waiting(Gw.orgA, count = 128, polledSecondsAgo = 0)
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
        val turn = server.asSuperuser {
            GatewayAdmissionTurns.select(GatewayAdmissionTurns.turn).where { GatewayAdmissionTurns.memberId eq ada }
                .single()[GatewayAdmissionTurns.turn]
        }
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
            // A call whose replica stopped polling ten seconds ago: older, interactive, still within its lease.
            server.waiting(Gw.orgA, count = 1, polledSecondsAgo = 10)
            assertEquals(AdmissionEntry.Admitted, one.enter(call(bob), first = true))

            server.waiting(Gw.orgA, count = 127, polledSecondsAgo = 0)
            val full = assertFailsWith<QueueRefusedException> { one.enter(call(ada), first = true) }
            assertEquals(RefusalKind.QUEUE_FULL, full.kind)

            // Expired places are dropped as the next call enters.
            server.superuserSql("UPDATE gateway_admissions SET expires_at = now() WHERE NOT running")
            assertEquals(AdmissionEntry.Admitted, one.enter(call(ada), first = true))
            assertEquals(QueueCounts(0, 0), one.counts(Gw.orgA, ada))
        }

    @Test
    fun aRunningCallsLeaseIsRenewedForFifteenMinutes() = serverTest(modules, overrides) { server ->
        val (one, _) = server.replicas()
        val running = call(ada)
        one.enter(running, first = true)
        server.superuserSql("UPDATE gateway_admissions SET expires_at = now() + INTERVAL '1 minute'")
        one.renew(Gw.orgA, running.id)
        val minutes = server.asSuperuser {
            texts("SELECT CAST(EXTRACT(EPOCH FROM (expires_at - now())) / 60 AS BIGINT) FROM gateway_admissions")
        }.first().toLong()
        assertTrue(minutes in 14..15, "Renewed for 15 minutes: $minutes")
    }

    /** [count] waiting interactive calls of [org], arrived a minute ago, last polled [polledSecondsAgo] ago. */
    private suspend fun TestServer.waiting(org: Uuid, count: Int, polledSecondsAgo: Int) = superuserSql(
        "INSERT INTO gateway_admissions (id, org_id, member_id, scope, workload, running, created_at, ready_at, " +
            "polled_at, expires_at) SELECT gen_random_uuid(), ?, gen_random_uuid(), 'claude', " +
            "'INTERACTIVE', FALSE, now() - INTERVAL '1 minute', now() - INTERVAL '1 minute', " +
            "now() - make_interval(secs => ?), now() + INTERVAL '10 seconds' " +
            "FROM generate_series(1, ?) AS i",
        listOf(UuidColumnType() to org, IntegerColumnType() to polledSecondsAgo, IntegerColumnType() to count),
    )

    @Test
    fun theQueriesOfAnAdmissionScanNoTable() = serverTest(modules, overrides) { server ->
        // Fixtures only: thousands of calls and turns of another org, and the statistics.
        server.waiting(Gw.orgB, count = 5_000, polledSecondsAgo = 0)
        server.superuserSql(
            "INSERT INTO gateway_admission_turns (org_id, scope, member_id, turn) " +
                "SELECT org_id, scope, member_id, 1 FROM gateway_admissions",
        )
        server.vacuumAnalyze("gateway_admissions", "gateway_admission_turns")
        val request = call(ada, limit = 1)
        val id = Uuid.random()
        val statements: Map<String, StatementBuilder.() -> Statement<*>> = mapOf(
            "the org locked" to { advisoryLock(admissionLockKey(Gw.orgA)) },
            "the expired dropped" to { admissionPurge(Gw.orgA) },
            "how long it waited" to { admissionWaited(Gw.orgA, id).query },
            "the member's running calls" to { admissionRunning(Gw.orgA, ada).query },
            "the other waiting calls" to { admissionCandidates(Gw.orgA, "claude", id).query },
            "the turns" to { admissionTurns(Gw.orgA, "claude", ada).query },
            "a call run at once" to { admissionRun(request, 1, 0) },
            "a waiting call run" to { admissionStart(request, 1, 0) },
            "the turn taken" to { admissionTurn(request, 1) },
            "the bounds" to { admissionWaiting(Gw.orgA, ada).query },
            "a call that waits" to { admissionWait(call(ada), 0) },
            "a poll" to { admissionPoll(Gw.orgA, id, 0) },
            "a turn given back" to { admissionGiveBack(Gw.orgA, request.id) },
            "a call back to wait" to { admissionRequeue(Gw.orgA, id) },
            "a lease renewed" to { admissionRenew(Gw.orgA, id) },
            "a call released" to { admissionRelease(Gw.orgA, id) },
            "the counts" to { admissionCounts(Gw.orgA, ada).query },
        )
        statements.forEach { (what, statement) ->
            val plan = server.koin.get<JdbcDatabase>().inOrg(Gw.orgA) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan" in plan, "$what scans a table:\n$plan")
        }
    }
}
