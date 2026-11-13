// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage

import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.Admission
import run.ostrom.server.usage.domain.DailySpendLimits
import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.SpendAdmission
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.SpendLimits
import run.ostrom.server.usage.domain.SpendReader
import run.ostrom.server.usage.domain.SpendScope
import run.ostrom.server.usage.domain.UsageRecorder
import run.ostrom.server.usage.domain.UsageRepository
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The budget holds on real PostgreSQL, as the serving role under row-level security: calls asking at once never hold
 * more than a limit leaves, and the spend stops exactly at the limit.
 */
class SpendHoldsPostgresTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val modules = listOf(SecretsModule, UsageModule)
    private val since = TestCallers.epoch - 1.days
    private val orgLimit = SpendLimits(orgMicros = 10_000, memberMicros = null)
    private val member = TestCallers.memberA.memberId

    private fun hold(micros: Long, orgId: Uuid = TestCallers.orgA, memberId: Uuid = member) =
        SpendHold(orgId, memberId, Uuid.random(), micros)

    private suspend fun TestServer.admit(hold: SpendHold, limits: SpendLimits = orgLimit) =
        koin.get<SpendAdmission>().admit(hold, limits, since)

    /** Records the call of [hold] at [costMicros], as the gateway does once it is answered. */
    private suspend fun TestServer.settle(hold: SpendHold, costMicros: Long) = koin.get<UsageRecorder>().settle(
        modelEvent(costMicros, memberId = hold.memberId, orgId = hold.orgId)
            .copy(id = hold.callId, workflowId = hold.workflowId),
    )

    private suspend fun TestServer.holdRows(): Long =
        database.superuser.fetchAll("SELECT COUNT(*) AS n FROM usage_holds").getOrThrow().first().get("n").asLong()

    @Test
    fun aCallThatWaitsInTheAdmissionQueueHoldsNothingUntilItAsksAgain() = serverTest(modules, overrides) { server ->
        val waiting = hold(9_000)
        assertEquals(Admission.Admitted, server.admit(waiting))
        assertEquals(Admission.Insufficient(SpendScope.ORG, 10_000, 1_000), server.admit(hold(3_000)))
        server.koin.get<SpendAdmission>().release(TestCallers.orgA, waiting.callId)
        assertEquals(0, server.holdRows(), "Its hold is dropped while it waits")
        assertEquals(Admission.Admitted, server.admit(hold(3_000)), "Another call takes what it held")
        server.koin.get<SpendAdmission>().release(TestCallers.orgA, Uuid.random())
        assertEquals(1, server.holdRows(), "Releasing a call that holds nothing changes nothing")
    }

    @Test
    fun twentyCallsAtOnceAdmitExactlyWhatTheLimitHolds() = serverTest(modules, overrides) { server ->
        val asked = List(20) { hold(3_000) }
        val answers = withContext(Dispatchers.Default) {
            coroutineScope { asked.map { async { server.admit(it) } }.awaitAll() }
        }
        val admitted = asked.zip(answers).filter { it.second == Admission.Admitted }.map { it.first }
        assertEquals(3, admitted.size, "10 000 µ$ hold three calls of 3 000, however many ask at once")
        assertEquals(
            List(17) { Admission.Insufficient(SpendScope.ORG, 10_000, 1_000) },
            answers.filter { it != Admission.Admitted },
        )
        assertEquals(3, server.holdRows())

        admitted.forEach { server.settle(it, 3_000) }
        assertEquals(0, server.holdRows(), "Each recorded call drops its hold")
        assertEquals(Admission.Insufficient(SpendScope.ORG, 10_000, 1_000), server.admit(hold(3_000)))
        val last = hold(1_000)
        assertEquals(Admission.Admitted, server.admit(last))
        server.settle(last, 1_000)
        assertEquals(10_000, server.koin.get<UsageRepository>().spentMicros(TestCallers.orgA, null, since))

        val after = withContext(Dispatchers.Default) {
            coroutineScope { listOf(1L, 0L, 3_000L).map { async { server.admit(hold(it)) } }.awaitAll() }
        }
        assertEquals(List(3) { Admission.Exhausted(SpendScope.ORG, 10_000) }, after, "Spent to the limit: no more")
        assertEquals(0, server.holdRows())
    }

    @Test
    fun aRetryHoldsOnceAKilledReplicasHoldStaysChargedAndOrgsApart() = serverTest(modules, overrides) { server ->
        val first = hold(6_000)
        assertEquals(Admission.Admitted, server.admit(first))
        assertEquals(Admission.Admitted, server.admit(first.copy(micros = 9_000)), "The same call asks again")
        assertEquals(1, server.holdRows())
        assertEquals(Admission.Insufficient(SpendScope.ORG, 10_000, 1_000), server.admit(hold(2_000)))
        assertEquals(Admission.Admitted, server.admit(hold(9_000, orgId = TestCallers.orgB)), "Org B holds its own")

        // The replica of the first call is killed: its call is never recorded, and what it held stays charged.
        clock.advance(61.minutes)
        assertEquals(Admission.Insufficient(SpendScope.ORG, 10_000, 1_000), server.admit(hold(2_000)), "Uncertain")
        assertEquals(2, server.holdRows(), "Its hold is kept, and org B's waits for org B")
        // Past the 90 days of retention, no month counts it any more: it goes.
        clock.advance(HoldWindow.RETENTION)
        assertEquals(Admission.Admitted, server.admit(hold(9_000)))
        assertEquals(2, server.holdRows(), "Org A's retired hold is dropped; org B's waits for org B")
        val seen = server.driver.fetchInOrg(TestCallers.orgA, Statement.create("SELECT org_id FROM usage_holds"))
            .getOrThrow().map { it.get("org_id").asString() }
        assertEquals(listOf(TestCallers.orgA.toString()), seen, "Under row-level security, org A sees its holds only")
        val none = server.driver.fetchAll("SELECT COUNT(*) AS n FROM usage_holds").getOrThrow().first().get("n")
        assertEquals(0, none.asLong(), "Without an organization, no hold is visible")

        // A member's limit counts the member's spend and holds only.
        val limits = SpendLimits(orgMicros = null, memberMicros = 2_000)
        val one = Uuid.random()
        val two = Uuid.random()
        assertEquals(Admission.Admitted, server.admit(hold(1_500, memberId = one), limits))
        assertEquals(Admission.Admitted, server.admit(hold(2_000, memberId = two), limits), "Not the other's hold")
        assertEquals(
            Admission.Insufficient(SpendScope.MEMBER, 2_000, 500),
            server.admit(hold(600, memberId = one), limits),
        )
    }

    @Test
    fun aCallRecordedAfterItsHoldExpiredIsChargedItsCostOnceAndADayCountsTheUncertain() =
        serverTest(modules, overrides) { server ->
            val late = hold(6_000)
            val lost = hold(3_000)
            assertEquals(Admission.Admitted, server.admit(late))
            assertEquals(Admission.Admitted, server.admit(lost))
            clock.advance(61.minutes)
            // The first call is recorded at last: its cost replaces its hold, as TeamCode settles an expired lease.
            server.settle(late, 1_000)
            assertEquals(1, server.holdRows(), "Only the lost call's hold is left")
            assertEquals(Admission.Insufficient(SpendScope.ORG, 10_000, 6_000), server.admit(hold(7_000)))
            val daily = SpendLimits(null, null, DailySpendLimits(TestCallers.epoch, 5_000, null))
            val left = Admission.Insufficient(SpendScope.ORG, 5_000, 1_000, daily = true)
            assertEquals(left, server.admit(hold(2_000), daily), "The lost call counts on the day it was held")
            val tomorrow = SpendLimits(null, null, DailySpendLimits(TestCallers.epoch + 1.days, 5_000, null))
            assertEquals(Admission.Admitted, server.admit(hold(2_000), tomorrow), "Held yesterday, charged yesterday")
        }

    @Test
    fun aCallThatPlaysAWorkflowWithoutALimitIsAdmittedOnTheOthersAlone() = serverTest(modules, overrides) { server ->
        server.koin.get<UsageRecorder>().record(modelEvent(900, memberId = member).copy(workflowId = "review"))
        assertEquals(900, server.koin.get<SpendReader>().workflowSpentMicros(TestCallers.orgA, "review", since))
        val today = TestCallers.epoch - 1.hours
        assertEquals(
            Admission.Admitted,
            server.admit(hold(100).copy(workflowId = "review"), SpendLimits(1_000, null)),
            "The org's month alone: its 900 and this 100 fit",
        )
        assertEquals(
            Admission.Insufficient(SpendScope.ORG, 1_000, 0, daily = true),
            server.admit(
                hold(1).copy(workflowId = "review"),
                SpendLimits(null, null, DailySpendLimits(today, 1_000, null)),
            ),
            "The org's day alone: the 100 in flight fill it",
        )
    }

    @Test
    fun aWorkflowLimitCountsTheCallsAndHoldsOfItsLaunchesOnly() = serverTest(modules, overrides) { server ->
        val recorder = server.koin.get<UsageRecorder>()
        recorder.record(modelEvent(600, memberId = member).copy(workflowId = "review"))
        recorder.record(modelEvent(5_000, memberId = member).copy(workflowId = "release"))
        recorder.record(modelEvent(5_000, memberId = member))
        recorder.record(modelEvent(9_000, memberId = member, orgId = TestCallers.orgB).copy(workflowId = "review"))
        val repository = server.koin.get<UsageRepository>()
        assertEquals(600, repository.workflowSpentMicros(TestCallers.orgA, "review", since))
        assertEquals(9_000, repository.workflowSpentMicros(TestCallers.orgB, "review", since), "Org B's own")

        val limits = SpendLimits(null, null, workflowMicros = 1_000)
        val first = hold(300).copy(workflowId = "review")
        assertEquals(Admission.Admitted, server.admit(first, limits))
        assertEquals(
            Admission.Insufficient(SpendScope.WORKFLOW, 1_000, 100),
            server.admit(hold(101).copy(workflowId = "review"), limits),
            "Its 600 spent and the 300 in flight leave 100",
        )
        assertEquals(
            Admission.Exhausted(SpendScope.WORKFLOW, 1_000),
            server.admit(hold(1).copy(workflowId = "release"), limits),
            "Another workflow's spend is its own",
        )
        assertEquals(
            Admission.Admitted,
            server.admit(hold(900, orgId = TestCallers.orgB).copy(workflowId = "nightly"), limits),
            "Org B's workflows are apart",
        )
        val read = Statement.create("SELECT workflow_id FROM usage_holds WHERE call_id = :call_id")
            .bind("call_id", first.callId)
        val stored = server.driver.fetchInOrg(TestCallers.orgA, read).getOrThrow().first()
            .get("workflow_id").asStringOrNull()
        assertEquals("review", stored, "The hold names its workflow")

        val today = TestCallers.epoch - 1.hours
        val daily = SpendLimits(null, null, DailySpendLimits(today, null, null, workflowMicros = 900))
        assertEquals(
            Admission.Insufficient(SpendScope.WORKFLOW, 900, 0, daily = true),
            server.admit(hold(1).copy(workflowId = "review"), daily),
            "Today's 600 and the 300 in flight fill the day",
        )
        server.settle(first, 300)
        val settled = server.koin.get<UsageRepository>().workflowSpentMicros(TestCallers.orgA, "review", since)
        assertEquals(900, settled, "The settled call counts under its workflow")
        assertEquals(1, server.holdRows(), "Org B's hold is left; org A's call dropped its own")
    }

    @Test
    fun aDailyLimitCountsTodaysSpendAndTheCallsInFlight() = serverTest(modules, overrides) { server ->
        val today = TestCallers.epoch - 1.hours
        // Spent before today: counts for the month, never for the day.
        server.koin.get<UsageRecorder>().record(modelEvent(5_000, memberId = member, at = today - 1.minutes))
        server.koin.get<UsageRecorder>().record(modelEvent(600, memberId = member))
        val orgDay = SpendLimits(null, null, DailySpendLimits(today, orgMicros = 1_000, memberMicros = null))
        assertEquals(Admission.Admitted, server.admit(hold(300), orgDay))
        assertEquals(
            Admission.Insufficient(SpendScope.ORG, 1_000, 100, daily = true),
            server.admit(hold(101), orgDay),
            "Today's 600 and the 300 in flight leave 100",
        )

        val memberDay = SpendLimits(null, null, DailySpendLimits(today, orgMicros = null, memberMicros = 600))
        assertEquals(Admission.Exhausted(SpendScope.MEMBER, 600, daily = true), server.admit(hold(1), memberDay))
        val other = Uuid.random()
        assertEquals(Admission.Admitted, server.admit(hold(500, memberId = other), memberDay), "Another member's day")

        val both = SpendLimits(10_000, null, DailySpendLimits(today, orgMicros = null, memberMicros = 2_000))
        assertEquals(
            Admission.Insufficient(SpendScope.ORG, 10_000, 3_600),
            server.admit(hold(4_000), both),
            "The month counts the 5 600 spent and the 800 in flight",
        )
    }
}
