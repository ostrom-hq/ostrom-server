// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetDecisionCache
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.domain.LedgerSpendGuard
import dev.ostrom.server.budgets.domain.SharedLimit
import dev.ostrom.server.gateway.domain.SpendDecision
import dev.ostrom.server.gateway.domain.SpendGuard
import dev.ostrom.server.gateway.domain.SpendRequest
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRecorder
import dev.ostrom.server.usage.domain.UsageRepository
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
        server.koin.get<BudgetsRepository>().putSharedLimit(TestCallers.orgA, SharedLimit.ORG, 10_000, clock.now())
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
}
