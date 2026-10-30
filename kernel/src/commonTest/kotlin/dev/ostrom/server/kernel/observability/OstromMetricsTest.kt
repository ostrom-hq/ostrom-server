// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.observability

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.genai.ModelCall
import dev.ostrom.core.telemetry.genai.ModelCallSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class OstromMetricsTest {
    private val telemetry = RecordingTelemetry()
    private val metrics = OstromMetrics(telemetry.meter)

    @Test
    fun gatesAreCountedByKindAndAnswersByChannelWithTheirWait() {
        metrics.gateRequested("merge")
        metrics.gateRequested("merge")
        metrics.gateAnswered("yes", "app", 2.minutes)

        assertEquals(2.0, telemetry.sum("ostrom.gates.requested", Attributes.of("kind" to "merge")))
        assertEquals(1.0, telemetry.sum("ostrom.gates.answered", Attributes.of("answer" to "yes", "channel" to "app")))
        val wait = telemetry.histogram("ostrom.gates.time_to_answer", Attributes.of("answer" to "yes"))
        assertEquals(120.0, wait.sum)
    }

    @Test
    fun sessionsAreCountedAndTheGaugesReadTheirCallbacks() {
        var pending = 4L
        metrics.sessionStarted("claude", "cloud")
        metrics.observeGatesPending { pending }
        metrics.observeSessions({ mapOf("local" to 2L, "cloud" to 1L) }, cloudCapacity = 20)
        pending = 5

        val started = Attributes.of("agent" to "claude", "where" to "cloud")
        assertEquals(1.0, telemetry.sum("ostrom.sessions.started", started))
        assertEquals(5.0, telemetry.gauge("ostrom.gates.pending").single().value)
        val active = telemetry.gauge("ostrom.sessions.active").associate { it.attributes to it.value }
        assertEquals(mapOf(Attributes.of("where" to "local") to 2.0, Attributes.of("where" to "cloud") to 1.0), active)
        assertEquals(20.0, telemetry.gauge("ostrom.sessions.cloud_capacity").single().value)
    }

    @Test
    fun aModelCallAddsItsCostTokensAndDuration() {
        val call = ModelCall(
            ModelCallSource.GATEWAY,
            "openrouter",
            "m",
            inputTokens = 100,
            outputTokens = 20,
            costUsd = 0.25,
        )
        metrics.modelCalled(call, 1_500.milliseconds)
        metrics.modelCalled(call.copy(costUsd = null), 500.milliseconds)
        metrics.modelCalled(call.copy(costUsd = Double.NaN), 500.milliseconds)
        metrics.modelCalled(call.copy(costUsd = -1.0), 500.milliseconds)

        val source = Attributes.of("source" to "gateway", "provider" to "openrouter")
        assertEquals(0.25, telemetry.sum("ostrom.inference.cost_usd", source))
        assertEquals(400.0, telemetry.sum("ostrom.inference.tokens", source + Attributes.of("type" to "input")))
        assertEquals(80.0, telemetry.sum("ostrom.inference.tokens", source + Attributes.of("type" to "output")))
        val duration = telemetry.histogram("ostrom.inference.duration", source)
        assertEquals(4, duration.count)
        assertEquals(3.0, duration.sum)
    }

    @Test
    fun theNamesMatchTheCatalogOfTheDashboards() {
        metrics.requestStarted()
        metrics.requestServed("GET", "/health", 200, 3.milliseconds)
        metrics.gateRequested("deploy")
        val names = telemetry.meter.collect().map { it.name }.toSet()

        assertTrue("http.server.request.duration" in names)
        assertTrue("http.server.active_requests" in names)
        assertTrue("ostrom.gates.requested" in names)
        assertEquals("s", telemetry.metric("http.server.request.duration").unit)
    }
}
