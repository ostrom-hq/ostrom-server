// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.metrics.MeterImpl
import run.ostrom.core.telemetry.metrics.MetricData
import run.ostrom.server.kernel.observability.OstromMetrics
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.MeteredCreateCloudSession
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Clock

class MeteredCreateCloudSessionTest {
    private val meter = MeterImpl(Clock.System)
    private val caller = Caller(ORG_A, "alice")
    private val payload = CreateCloudSessionPayload(name = null, agent = "codex", repository = null)

    private fun creating(block: () -> CloudSession) = object : CreateCloudSessionUseCase {
        override suspend fun invoke(caller: Caller, payload: CreateCloudSessionPayload): CloudSession = block()
    }

    private fun started(): Map<Attributes, Double> =
        (meter.collect().singleOrNull { it.name == "ostrom.sessions.started" } as MetricData.Sum?)
            ?.points?.associate { it.attributes to it.value }.orEmpty()

    @Test
    fun aCreatedSessionIsCountedInTheCloudUnderItsAgent() = runTest {
        val created = session().copy(agent = AgentKind.CODEX)
        val metered = MeteredCreateCloudSession(creating { created }, OstromMetrics(meter))

        assertSame(created, metered(caller, payload))
        assertSame(created, metered(caller, payload))

        assertEquals(mapOf(Attributes.of("agent" to "codex", "where" to "cloud") to 2.0), started())
    }

    @Test
    fun aRefusedSessionIsNotCounted() = runTest {
        val refusing = creating { throw IllegalStateException("over the cap") }
        val metered = MeteredCreateCloudSession(refusing, OstromMetrics(meter))

        assertFailsWith<IllegalStateException> { metered(caller, payload) }

        assertEquals(emptyMap(), started())
    }
}
