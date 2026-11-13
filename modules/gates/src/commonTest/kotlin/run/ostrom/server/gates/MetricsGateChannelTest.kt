// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.metrics.MeterImpl
import run.ostrom.core.telemetry.metrics.MetricData
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.services.GateChannel
import run.ostrom.server.gates.fakes.GateBench
import run.ostrom.server.gates.infrastructure.MetricsGateChannel
import run.ostrom.server.kernel.observability.OstromMetrics
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class MetricsGateChannelTest {
    private val meter = MeterImpl(Clock.System)
    private val channel = MetricsGateChannel(OstromMetrics(meter))

    private fun metric(name: String) = meter.collect().singleOrNull { it.name == name }

    private fun sum(name: String, vararg attributes: Pair<String, String>): Double? =
        (metric(name) as MetricData.Sum?)?.points?.singleOrNull { it.attributes == Attributes.of(*attributes) }?.value

    @Test
    fun anOpenedGateIsCountedByItsKind() = runTest {
        val bench = GateBench()
        val push = bench.open(bench.pushRequest())
        val merge = bench.open(bench.mergeRequest())

        channel.requested(push.gate, push.requests)
        channel.requested(merge.gate, merge.requests)
        channel.requested(push.gate, push.requests)

        assertEquals("metrics", channel.name)
        assertEquals(2.0, sum("ostrom.gates.requested", "kind" to "push_main"))
        assertEquals(1.0, sum("ostrom.gates.requested", "kind" to "merge"))
    }

    @Test
    fun anAnswerIsCountedWithItsChannelAndHowLongItWaited() = runTest {
        val bench = GateBench()
        val yes = bench.open(bench.pushRequest())
        bench.clock.now += 3.minutes
        val approved = bench.answer(yes, "rev-b")
        val no = bench.open(bench.mergeRequest())
        bench.clock.now += 1.minutes
        val refused = bench.answer(no, "rev-b", Answer.NO)

        channel.decided(approved.gate, approved.requests)
        channel.decided(refused.gate, refused.requests)

        assertEquals(1.0, sum("ostrom.gates.answered", "answer" to "yes", "channel" to "mobile"))
        assertEquals(1.0, sum("ostrom.gates.answered", "answer" to "no", "channel" to "mobile"))
        val waits = (metric("ostrom.gates.time_to_answer") as MetricData.Distribution).points
        assertEquals(180.0, waits.single { it.attributes == Attributes.of("answer" to "yes") }.sum)
        assertEquals(60.0, waits.single { it.attributes == Attributes.of("answer" to "no") }.sum)
    }

    @Test
    fun aGateWithoutDecisionOrAnsweredRequestIsNotCounted() = runTest {
        val bench = GateBench()
        val pending = bench.open(bench.pushRequest())
        val approved = bench.answer(pending, "rev-b")

        channel.decided(pending.gate, pending.requests)
        channel.decided(approved.gate, approved.requests.map { it.copy(status = pending.requests.first().status) })

        assertEquals(null, metric("ostrom.gates.answered"))
    }

    @Test
    fun anAnswerWithoutChannelIsCountedAsUnknown() = runTest {
        val bench = GateBench()
        val approved = bench.answer(bench.open(bench.pushRequest()), "rev-b")

        channel.decided(approved.gate, approved.requests.map { it.copy(channel = null) })

        assertEquals(1.0, sum("ostrom.gates.answered", "answer" to "yes", "channel" to "unknown"))
    }

    @Test
    fun theKindIsBoundedWhateverTheOrganizationNamedItsAction() {
        val kinds = listOf("push-main", "push-tag", "merge", "deploy", "migration", "custom-release-train", "rm -rf")
            .map { MetricsGateChannel.kindOf(it) }

        assertEquals(listOf("push_main", "push_tag", "merge", "deploy", "migration", "custom", "other"), kinds)
    }

    @Test
    fun theModuleBindsTheChannelNextToTheOthers() {
        val app = koinApplication {
            modules(module { single { OstromMetrics(meter) } }, GatesModule.koinModule)
        }

        val channels = app.koin.getAll<GateChannel>()

        assertIs<MetricsGateChannel>(app.koin.get<GateChannel>(named("metrics")))
        assertTrue(channels.any { it is MetricsGateChannel })
        app.close()
    }
}
