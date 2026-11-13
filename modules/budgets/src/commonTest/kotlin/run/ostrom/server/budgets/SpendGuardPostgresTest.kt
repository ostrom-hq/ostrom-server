// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BudgetDecisionCache
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.domain.LedgerSpendGuard
import run.ostrom.server.budgets.presentation.BudgetDto
import run.ostrom.server.gateway.domain.SpendDecision
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.SpendRequest
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRecorder
import run.ostrom.server.usage.domain.UsageRepository
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The gateway's guard bound by the budgets module, on real PostgreSQL as the serving role (`ostrom_app` in a
 * deployment): with a limit of 10 000 µ$, twenty calls of 3 000 asking at once admit exactly three, then one of 1 000
 * brings the spend exactly to the limit, and every call after it is refused.
 */
class SpendGuardPostgresTest {
    private val clock = TestClock()
    private val overrides = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val modules = listOf(SecretsModule, UsageModule, BudgetsModule)
    private val member = TestCallers.memberA.memberId
    private val now = clock.now()

    private fun request(reserve: Long) = SpendRequest(TestCallers.orgA, member, Uuid.random(), reserve)

    /** The gateway's record of an answered call: it writes the cost and drops the hold. */
    private suspend fun TestServer.answered(call: SpendRequest, costMicros: Long) = koin.get<UsageRecorder>().settle(
        UsageEvent(call.callId, call.orgId, call.memberId, UsageKind.MODEL, "m", costMicros = costMicros, at = now),
    )

    private suspend fun TestServer.atOnce(requests: List<SpendRequest>): List<SpendDecision> {
        val guard = koin.get<SpendGuard>()
        return withContext(Dispatchers.Default) {
            coroutineScope { requests.map { async { guard.check(it) } }.awaitAll() }
        }
    }

    @Test
    fun theLimitHoldsToTheMicroUnderTwentyCallsAtOnce() = serverTest(modules, overrides) { server ->
        assertIs<LedgerSpendGuard>(server.koin.get<SpendGuard>())
        assertEquals(SpendDecision.Allowed, server.koin.get<SpendGuard>().check(request(1_000_000)), "No limit yet")
        val limit = listOf(BudgetLimit(10_000))
        server.koin.get<BudgetsRepository>().replaceLimits(TestCallers.orgA, BudgetTarget.Org, limit, clock.now())
        // As the limit's use cases do: the cached "no limit" is forgotten at once on this replica.
        server.koin.get<BudgetDecisionCache>().invalidate(TestCallers.orgA)

        val calls = List(20) { request(3_000) }
        val decisions = server.atOnce(calls)
        val admitted = calls.zip(decisions).filter { it.second == SpendDecision.Allowed }.map { it.first }
        assertEquals(3, admitted.size, decisions.toString())
        val refused = decisions.filterIsInstance<SpendDecision.Insufficient>()
        assertEquals(17, refused.size)
        assertTrue(refused.all { it.message.startsWith(LedgerSpendGuard.INSUFFICIENT) }, refused.first().message)

        admitted.forEach { server.answered(it, 3_000) }
        assertIs<SpendDecision.Insufficient>(server.koin.get<SpendGuard>().check(request(3_000)))
        val last = request(1_000)
        assertEquals(SpendDecision.Allowed, server.koin.get<SpendGuard>().check(last))
        server.answered(last, 1_000)
        val spent = server.koin.get<UsageRepository>().spentMicros(TestCallers.orgA, null, TestCallers.monthStart)
        assertEquals(10_000, spent, "The spend lands exactly on the limit")

        val after = server.atOnce(List(5) { request(it.toLong()) })
        assertEquals(5, after.filterIsInstance<SpendDecision.Exhausted>().size, after.toString())
        val exhausted = after.first() as SpendDecision.Exhausted
        assertTrue(exhausted.message.startsWith(LedgerSpendGuard.EXHAUSTED), exhausted.message)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), exhausted.resetsAt)
    }

    @Test
    fun aDailyLimitThatStopsRefusesTheCallsOfTheDayUntilMidnightAndOneThatWarnsRefusesNone() =
        serverTest(modules, overrides) { server ->
            suspend fun put(budget: BudgetDto) = assertEquals(
                HttpStatusCode.OK,
                server.client.put("/api/v1/budgets") {
                    bearerAuth("admin-a")
                    contentType(ContentType.Application.Json)
                    setBody(budget)
                }.status,
            )
            put(BudgetDto("member", member.toString(), usdPerDay = 0.01))
            // Yesterday's spend is far past the daily limit: it does not count today.
            val yesterday = now - 1.days
            val old = UsageEvent(
                Uuid.random(),
                TestCallers.orgA,
                member,
                UsageKind.MODEL,
                "m",
                costMicros = 50_000,
                at = yesterday,
            )
            server.koin.get<UsageRecorder>().record(old)
            val guard = server.koin.get<SpendGuard>()

            val short = guard.check(request(20_000))
            assertIs<SpendDecision.Insufficient>(short)
            assertTrue(short.message.startsWith(LedgerSpendGuard.INSUFFICIENT), short.message)
            assertTrue("of your $0.01 daily limit" in short.message, short.message)

            val call = request(4_000)
            assertEquals(SpendDecision.Allowed, guard.check(call))
            server.answered(call, 10_000)
            val after = server.atOnce(List(3) { request(1) })
            assertEquals(3, after.filterIsInstance<SpendDecision.Exhausted>().size, after.toString())
            val exhausted = after.first() as SpendDecision.Exhausted
            assertEquals(
                "Daily budget reached: your limit of $0.01 a day is spent; it resets on 2026-10-31.",
                exhausted.message,
            )
            assertEquals(Instant.parse("2026-10-31T00:00:00Z"), exhausted.resetsAt)

            put(BudgetDto("member", member.toString(), usdPerDay = 0.01, hardStop = false))
            assertEquals(
                SpendDecision.Allowed,
                guard.check(request(20_000)),
                "Past the limit, a budget that warns lets it go",
            )
        }
}
