// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.BudgetCheck
import dev.ostrom.server.gateway.domain.InferenceCall
import dev.ostrom.server.gateway.domain.InferencePlan
import dev.ostrom.server.gateway.domain.InferenceProvider
import dev.ostrom.server.gateway.domain.InstanceProviderKeys
import dev.ostrom.server.gateway.domain.ModelCallRecord
import dev.ostrom.server.gateway.domain.ModelPrice
import dev.ostrom.server.gateway.domain.PriceTable
import dev.ostrom.server.gateway.domain.RefusalKind
import dev.ostrom.server.gateway.domain.RelayInferenceUseCaseImpl
import dev.ostrom.server.gateway.domain.RequestBody
import dev.ostrom.server.gateway.domain.SpendDecision
import dev.ostrom.server.gateway.domain.SpendGuard
import dev.ostrom.server.gateway.domain.SpendRequest
import dev.ostrom.server.gateway.domain.UpstreamAnswer
import dev.ostrom.server.gateway.domain.WireProtocol
import dev.ostrom.server.sharing.domain.AccountProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What a call holds against the budgets before it goes, and how the gateway answers a budget that refuses it. */
class BudgetCheckTest {
    private val clock = TestClock()
    private val settings = InMemorySettings()
    private val accounts = InMemoryAccounts()
    private val check = BudgetCheck(SpendGuard { SpendDecision.Allowed }, PriceTable())

    private fun body(text: String) = assertNotNull(RequestBody.read(text, null))

    @Test
    fun theBodyBoundsItsOutputByTheLargestLimitItNames() {
        assertNull(body("""{"model":"m"}""").maxOutputTokens)
        assertEquals(10, body("""{"model":"m","max_tokens":10}""").maxOutputTokens)
        assertEquals(20, body("""{"model":"m","max_output_tokens":20}""").maxOutputTokens)
        assertEquals(30, body("""{"model":"m","max_tokens":5,"max_completion_tokens":30}""").maxOutputTokens)
        assertNull(body("""{"model":"m","max_tokens":-1}""").maxOutputTokens, "A negative bound is none")
        assertNull(body("""{"model":"m","max_tokens":1.5}""").maxOutputTokens, "Not a whole number")
        assertNull(body("""{"model":"m","max_tokens":"9"}""").maxOutputTokens, "Not a number")
        assertNull(RequestBody.read("""{"model":"m","max_tokens":1,"max_tokens":99}""", null), "Named twice")
    }

    /** Tokens at a price per million, rounded up as the reserve is. */
    private fun micros(tokens: Long, perMillion: Long) = (tokens * perMillion + 999_999) / 1_000_000

    private fun inputOf(text: String) = text.length / 2L + 1

    @Test
    fun theReserveIsTheBoundedOutputPlusTheEstimatedInputAtTheDearerInputPrice() {
        // claude-opus-5: $25 per million output tokens, cache writes at $6.25 dearer than the $5 input.
        val text = """{"model":"claude-opus-5","max_tokens":1000}"""
        assertEquals(
            micros(1_000, 25_000_000) + micros(inputOf(text), 6_250_000),
            check.reserveMicros(InferenceProvider.ANTHROPIC, body(text)),
        )
        val unbounded = """{"model":"claude-opus-5"}"""
        assertEquals(
            micros(BudgetCheck.DEFAULT_MAX_OUTPUT, 25_000_000) + micros(inputOf(unbounded), 6_250_000),
            check.reserveMicros(InferenceProvider.CLAUDE_SHARED, body(unbounded)),
            "Without a bound, the default; a shared account is priced as its API",
        )
        val huge = """{"model":"claude-opus-5","max_tokens":999999999999}"""
        assertEquals(
            micros(BudgetCheck.MAX_OUTPUT, 25_000_000) + micros(inputOf(huge), 6_250_000),
            check.reserveMicros(InferenceProvider.ANTHROPIC, body(huge)),
            "The output held is capped",
        )
        assertEquals(0, check.reserveMicros(InferenceProvider.OPENAI, body("""{"model":"gpt-5"}""")), "Unpriced")
        // claude-haiku-5-5: cache writes at $0.125, dearer than the $0.10 input.
        assertEquals(
            micros(30, 500_000) + micros(inputOf("""{"model":"claude-haiku-5-5"}"""), 125_000),
            BudgetCheck(SpendGuard { SpendDecision.Allowed }, PriceTable(), defaultMaxOutput = 30)
                .reserveMicros(InferenceProvider.ANTHROPIC, body("""{"model":"claude-haiku-5-5"}""")),
        )
        // A price whose input is dearer than its cache writes holds the input price.
        val inputDearer = PriceTable(listOf(ModelPrice("anthropic", "x", 9_000_000, 1, 1, 1_000_000)))
        assertEquals(
            micros(2, 1_000_000) + micros(inputOf("""{"model":"x","max_tokens":2}"""), 9_000_000),
            BudgetCheck(SpendGuard { SpendDecision.Allowed }, inputDearer)
                .reserveMicros(InferenceProvider.ANTHROPIC, body("""{"model":"x","max_tokens":2}""")),
        )
    }

    @Test
    fun theGuardIsAskedForTheMemberTheCallAndItsReserve() = runTest {
        val asked = mutableListOf<SpendRequest>()
        val guarded = BudgetCheck(
            SpendGuard {
                asked += it
                SpendDecision.Insufficient("Budget left too low for this call")
            },
            PriceTable(),
        )
        val text = """{"model":"claude-opus-5","max_tokens":10}"""
        val callId = Gw.orgB
        val decision = guarded.check(Gw.memberA, callId, WireProtocol.MESSAGES, body(text))
        assertEquals(SpendDecision.Insufficient("Budget left too low for this call"), decision)
        val reserve = check.reserveMicros(InferenceProvider.ANTHROPIC, body(text))
        assertEquals(listOf(SpendRequest(Gw.orgA, Gw.memberA.memberId, callId, reserve)), asked)
    }

    @Test
    fun aBudgetThatCannotCoverTheCallRefusesItWithA429ThatDoesNotWaitForTheMonth() = runTest {
        val spend = SpendGuard { SpendDecision.Insufficient("Budget left too low for this call: lower max_tokens.") }
        val refused = assertIs<InferencePlan.Refused>(
            planner(settings, pool(accounts, clock), spend = spend).plan(
                Gw.memberA,
                InferenceCall(WireProtocol.MESSAGES, """{"model":"claude-opus-5"}""", emptyMap(), null),
            ),
        )
        assertEquals(RefusalKind.BUDGET_INSUFFICIENT, refused.refusal.kind)
        assertEquals(429, refused.refusal.kind.status)
        assertEquals("Budget left too low for this call: lower max_tokens.", refused.refusal.message)
        assertNull(refused.refusal.retryAt)
        assertEquals("claude-opus-5", refused.model)
    }

    @Test
    fun everyTryOfACallHoldsUnderOneIdThatTheLedgerRecordSettles() = runTest {
        repeat(2) { accounts.share(AccountProvider.CLAUDE, "a$it", clock) }
        val asked = mutableListOf<SpendRequest>()
        val records = mutableListOf<ModelCallRecord>()
        val spend = SpendGuard {
            asked += it
            SpendDecision.Allowed
        }
        val upstream = FakeUpstream(
            UpstreamAnswer(429, emptyMap(), source()),
            UpstreamAnswer(200, mapOf("content-type" to "application/json"), source("{}".encodeToByteArray())),
        )
        val relay = RelayInferenceUseCaseImpl(
            planner(settings, pool(accounts, clock), keys = InstanceProviderKeys(), spend = spend),
            upstream,
            PriceTable(),
            { records += it },
            { },
            clock,
        )
        val body = """{"model":"claude-opus-5","max_tokens":5}"""
        relay(Gw.memberA, InferenceCall(WireProtocol.MESSAGES, body, emptyMap(), null), RecordingResponder())
        relay(Gw.memberA, InferenceCall(WireProtocol.MESSAGES, body, emptyMap(), null), RecordingResponder())
        assertEquals(3, asked.size, "Two tries for the first call, one for the second")
        assertEquals(asked[0].callId, asked[1].callId, "The retry asks again for the same call")
        assertEquals(listOf(asked[0].callId, asked[2].callId), records.map { it.id }, "The record settles its hold")
        assertEquals(2, records.map { it.id }.toSet().size)
    }
}
