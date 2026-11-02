// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage

import dev.ostrom.server.gateway.domain.InferenceProvider
import dev.ostrom.server.gateway.domain.ModelCallRecord
import dev.ostrom.server.gateway.domain.TokenUsage
import dev.ostrom.server.gateway.domain.WireProtocol
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRecorder
import dev.ostrom.server.usage.infrastructure.LedgerModelUsageSink
import dev.ostrom.server.usage.infrastructure.LedgerSessionCosts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The gateway's model calls reach the ledger, and a session's receipt costs what its launch spent meanwhile. */
class GatewayLedgerTest {
    private val recorded = mutableListOf<UsageEvent>()
    private val sink = LedgerModelUsageSink(
        object : UsageRecorder {
            override suspend fun record(event: UsageEvent) {
                recorded += event
            }
        },
    )

    private fun call(usage: TokenUsage?, provider: InferenceProvider?) = ModelCallRecord(
        id = Uuid.random(),
        orgId = TestCallers.orgA,
        memberId = TestCallers.memberA.memberId,
        sessionId = "lch_1",
        protocol = WireProtocol.MESSAGES,
        provider = provider,
        accountId = Uuid.random(),
        model = "claude-opus-5",
        usage = usage,
        costMicros = 30_000_000,
        priced = true,
        status = 200,
        outcome = "ok",
        startedAt = TestCallers.epoch,
        firstByteMillis = 12,
        durationMillis = 900,
        launchId = "lch_1",
    )

    @Test
    fun aModelCallIsAModelEventWithItsTokensCostAndLaunch() = runTest {
        val call =
            call(TokenUsage(input = 1200, output = 42, cacheRead = 300, cacheWrite = 7), InferenceProvider.ANTHROPIC)
        sink.record(call)

        val event = recorded.single()
        assertEquals(
            UsageEvent(
                id = call.id,
                orgId = TestCallers.orgA,
                memberId = TestCallers.memberA.memberId,
                kind = UsageKind.MODEL,
                name = "claude-opus-5",
                provider = "anthropic",
                accountId = call.accountId,
                sessionId = "lch_1",
                launchId = "lch_1",
                inputTokens = 1200,
                outputTokens = 42,
                cacheReadTokens = 300,
                cacheWriteTokens = 7,
                costMicros = 30_000_000,
                durationMillis = 900,
                outcome = "ok",
                at = TestCallers.epoch,
            ),
            event,
        )
    }

    @Test
    fun aCallThatReachedNoProviderIsRecordedWithoutTokens() = runTest {
        sink.record(call(usage = null, provider = null).copy(costMicros = 0, outcome = "no_credential"))

        val event = recorded.single()
        assertNull(event.provider)
        assertEquals(
            listOf(0L, 0L, 0L, 0L),
            with(event) {
                listOf(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens)
            },
        )
        assertEquals("no_credential", event.outcome)
    }

    @Test
    fun aSessionCostsWhatItsLaunchSpentWhileItRan() = runTest {
        val repository = InMemoryUsageRepository()
        val costs = LedgerSessionCosts(repository)
        val from = TestCallers.epoch
        val to = from + 1.hours
        repository.insert(
            TestCallers.orgA,
            listOf(
                modelEvent(1_000_000, at = from).copy(launchId = "lch_1"),
                modelEvent(500_000, at = to - 1.milliseconds).copy(launchId = "lch_1"),
                modelEvent(9_000_000, at = to).copy(launchId = "lch_1"),
                modelEvent(0, at = from).copy(launchId = "lch_4"),
                modelEvent(9_000_000, at = from + 1.minutes).copy(launchId = "lch_2"),
                modelEvent(9_000_000, at = to + 1.minutes).copy(launchId = "lch_1"),
                modelEvent(9_000_000, at = from + 1.minutes),
            ),
        )

        assertEquals(1.5, costs.costOf(TestCallers.orgA, "lch_1", from, to))
        assertNull(costs.costOf(TestCallers.orgA, "lch_3", from, to), "Nothing measured: no cost, never zero")
        assertNull(costs.costOf(TestCallers.orgA, "lch_4", from, to), "Only unpriced calls: no cost, never zero")
        assertNull(costs.costOf(TestCallers.orgA, null, from, to), "Without a launch, the gateway saw nothing of it")
    }
}
