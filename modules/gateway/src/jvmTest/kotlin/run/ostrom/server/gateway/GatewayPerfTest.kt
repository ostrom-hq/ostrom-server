// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.InferenceCall
import run.ostrom.server.gateway.domain.InferencePlan
import run.ostrom.server.gateway.domain.InstanceProviderKeys
import run.ostrom.server.gateway.domain.UsageMeter
import run.ostrom.server.gateway.domain.WireProtocol
import kotlinx.coroutines.test.runTest
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The gateway's own cost on a call: planning a 100 KB conversation (scan, policy, key) and metering a 100 KB stream
 * stay under a millisecond at the median. Measured on the JVM (the native test binaries are debug builds), in CPU
 * time of the test thread: the CI runner shares its two cores with the other modules' tests, whose load is not the
 * gateway's.
 */
class GatewayPerfTest {
    private val text = "Explain \\\"this\\\" code: {x: [1, 2]}. ".repeat(20)
    private val turn = """{"role":"user","content":[{"type":"text","text":"$text"}]}"""
    private val request = """{"model":"claude-opus-5","stream":true,"max_tokens":4096,"metadata":{"user_id":"u"},""" +
        """"messages":[${List(100_000 / turn.length + 1) { turn }.joinToString(",")}]}"""
    private val delta = "event: content_block_delta\n" +
        "data: {\"type\":\"content_block_delta\",\"index\":0," +
        "\"delta\":{\"type\":\"text_delta\",\"text\":\"word \"}}\n\n"
    private val answer = (
        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":30000,\"output_tokens\":1}}}\n\n" +
            delta.repeat(100_000 / delta.length + 1) +
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2000}}\n\n"
        ).encodeToByteArray()
    private val chunks = (answer.indices step CHUNK).map { answer.copyOfRange(it, minOf(it + CHUNK, answer.size)) }

    private val threads = ManagementFactory.getThreadMXBean()

    @Test
    fun planAndMeterOfA100KbCallTakeUnderAMillisecondAtTheMedian() = runTest {
        assertTrue(request.length >= 100_000 && answer.size >= 100_000)
        val planner =
            planner(
                InMemorySettings(),
                pool(InMemoryAccounts(), TestClock()),
                keys = InstanceProviderKeys(anthropic = "sk"),
            )
        val call = InferenceCall(WireProtocol.MESSAGES, request, mapOf("anthropic-version" to "2023-06-01"), "s")
        suspend fun once(): Duration {
            val start = threads.currentThreadCpuTime
            assertIs<InferencePlan.Ready>(planner.plan(Gw.memberA, call))
            val meter = UsageMeter(WireProtocol.MESSAGES)
            chunks.forEach { meter.accept(it, it.size) }
            assertNotNull(meter.usage)
            return (threads.currentThreadCpuTime - start).nanoseconds
        }
        repeat(WARMUP) { once() }
        val median = List(RUNS) { once() }.sorted()[RUNS / 2]
        assertTrue(median < 1.milliseconds, "p50 = $median")
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val WARMUP = 1_000
        const val RUNS = 301
    }
}
