// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.metrics.MeterImpl
import dev.ostrom.core.telemetry.metrics.MetricData
import dev.ostrom.server.gateway.domain.InferenceProvider
import dev.ostrom.server.gateway.domain.ModelCallObserver
import dev.ostrom.server.gateway.domain.ModelCallRecord
import dev.ostrom.server.gateway.domain.TokenUsage
import dev.ostrom.server.gateway.domain.WireProtocol
import dev.ostrom.server.gateway.infrastructure.MeteredModelCallObserver
import dev.ostrom.server.kernel.observability.OstromMetrics
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class MeteredModelCallObserverTest {
    private val meter = MeterImpl(Clock.System)
    private val traced = mutableListOf<ModelCallRecord>()
    private val observer = MeteredModelCallObserver(OstromMetrics(meter)) { traced += it }

    private fun record(provider: InferenceProvider?, usage: TokenUsage?, costMicros: Long, priced: Boolean) =
        ModelCallRecord(
            Gw.orgA, Gw.orgA, Gw.memberA.memberId, "s-1", WireProtocol.CHAT, provider, null, "m", usage, costMicros,
            priced, 200, "ok", Instant.parse("2026-11-02T10:00:00Z"), 100, 2_500,
        )

    private fun points(name: String) = meter.collect().singleOrNull { it.name == name }

    private fun sums(name: String): Map<Attributes, Double> =
        (points(name) as MetricData.Sum?)?.points?.associate { it.attributes to it.value }.orEmpty()

    @Test
    fun aRelayedCallAddsItsCostTokensAndDurationThenIsTraced() = runTest {
        val usage = TokenUsage(100, 20, cacheRead = 30, cacheWrite = 5)
        val priced = record(InferenceProvider.OPENROUTER, usage, 250_000, true)
        val unpriced = record(InferenceProvider.OPENROUTER, null, 0, false)

        observer.observe(priced)
        observer.observe(unpriced)

        val source = Attributes.of("source" to "gateway", "provider" to "openrouter")
        assertEquals(mapOf(source to 0.25), sums("ostrom.inference.cost_usd"))
        val tokens = sums("ostrom.inference.tokens")
        assertEquals(135.0, tokens[source + Attributes.of("type" to "input")])
        assertEquals(20.0, tokens[source + Attributes.of("type" to "output")])
        val duration = (points("ostrom.inference.duration") as MetricData.Distribution).points.single()
        assertEquals(source, duration.attributes)
        assertEquals(5.0, duration.sum)
        assertEquals(listOf(priced, unpriced), traced)
    }

    @Test
    fun aCallThatReachedNoProviderIsTracedButNotCounted() = runTest {
        val refused = record(null, null, 0, false)

        observer.observe(refused)

        assertEquals(null, points("ostrom.inference.duration"))
        assertEquals(emptyMap(), sums("ostrom.inference.tokens"))
        assertEquals(listOf(refused), traced)
    }
}
