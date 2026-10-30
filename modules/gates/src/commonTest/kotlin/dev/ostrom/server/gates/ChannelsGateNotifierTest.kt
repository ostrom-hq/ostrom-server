// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.gates.fakes.GateBench
import dev.ostrom.server.gates.fakes.RecordingLogger
import dev.ostrom.server.gates.infrastructure.ChannelsGateNotifier
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChannelsGateNotifierTest {
    private class Channel(override val name: String, val failure: Exception? = null) : GateChannel {
        val events = mutableListOf<String>()

        override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
            events += "requested ${requests.size}"
            failure?.let { throw it }
        }

        override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) {
            events += "decided"
            failure?.let { throw it }
        }
    }

    @Test
    fun everyChannelHearsOfTheGateEvenAfterOneFails() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val broken = Channel("slack", IllegalStateException("down"))
        val github = Channel("github")
        val logger = RecordingLogger()
        var reads = 0
        val notifier = ChannelsGateNotifier({ reads++; listOf(broken, github) }, logger)

        notifier.requested(view.gate, view.requests)
        notifier.decided(view.gate, view.requests)

        val heard = listOf("requested ${view.requests.size}", "decided")
        assertEquals(heard, github.events)
        assertEquals(heard, broken.events)
        assertEquals(List(2) { LogLevel.WARN to "gate channel failed" }, logger.lines)
        assertEquals(1, reads, "The channels are read once")
    }

    private class Hung(override val name: String) : GateChannel {
        override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) = awaitCancellation()

        override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) = awaitCancellation()
    }

    @Test
    fun hungChannelsHoldTheGateAtMostOneTimeoutTogether() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val github = Channel("github")
        val logger = RecordingLogger()
        val notifier = ChannelsGateNotifier({ listOf(Hung("slack"), Hung("email"), github) }, logger)

        notifier.requested(view.gate, view.requests)

        assertEquals(ChannelsGateNotifier.CHANNEL_TIMEOUT.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals(listOf("requested ${view.requests.size}"), github.events)
        assertEquals(List(2) { LogLevel.WARN to "gate channel timed out" }, logger.lines)
    }

    @Test
    fun aCancelledChannelIsNotSwallowed() = runTest {
        val bench = GateBench()
        val view = bench.open(bench.pushRequest())
        val cancelled = Channel("push", CancellationException("stop"))
        val notifier = ChannelsGateNotifier({ listOf(cancelled) }, RecordingLogger())
        assertFailsWith<CancellationException> { notifier.requested(view.gate, view.requests) }
    }
}
