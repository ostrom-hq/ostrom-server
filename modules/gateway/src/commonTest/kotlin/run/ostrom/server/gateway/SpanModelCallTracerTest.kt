// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.core.telemetry.AttributeValue
import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.trace.Span
import run.ostrom.core.telemetry.trace.SpanContext
import run.ostrom.core.telemetry.trace.SpanContextElement
import run.ostrom.core.telemetry.trace.SpanKind
import run.ostrom.core.telemetry.trace.StatusCode
import run.ostrom.core.telemetry.trace.Tracer
import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.ModelCallRecord
import run.ostrom.server.gateway.domain.TokenUsage
import run.ostrom.server.gateway.domain.WireProtocol
import run.ostrom.server.gateway.infrastructure.SpanModelCallTracer
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/** The span of a model call: under the request's span, with what the call cost, never what it said. */
class SpanModelCallTracerTest {
    /** A span that keeps what is set on it. */
    private class RecordedSpan(
        val name: String,
        val kind: SpanKind,
        val parent: SpanContext?,
        val broken: Boolean,
    ) : Span {
        override val context: SpanContext = SpanContext(1, 2, 3, true)
        val attributes = mutableMapOf<String, AttributeValue>()
        var status: Pair<StatusCode, String> = StatusCode.UNSET to ""
        var ends = 0

        override fun setAttribute(key: String, value: AttributeValue) {
            check(!broken) { "exporter down" }
            attributes[key] = value
        }

        override fun setStatus(code: StatusCode, message: String) {
            status = code to message
        }

        override fun updateName(name: String) = error("A model call keeps its name")

        override fun end() {
            ends++
        }
    }

    private val spans = mutableListOf<RecordedSpan>()
    private var broken = false
    private val tracer = object : Tracer {
        override fun startSpan(name: String, kind: SpanKind, parent: SpanContext?, attributes: Attributes): Span =
            RecordedSpan(name, kind, parent, broken).also { spans += it }
    }
    private val traces = SpanModelCallTracer(tracer)
    private val request = SpanContext(7, 8, 9, true)

    private fun record(
        provider: InferenceProvider?,
        usage: TokenUsage? = null,
        status: Int = 200,
        outcome: String = "ok",
    ) = ModelCallRecord(
        Gw.orgB, Gw.orgA, Gw.memberA.memberId, "s-1", WireProtocol.MESSAGES, provider, null, "claude-opus-5",
        usage, 250_000, usage != null, status, outcome, Instant.parse("2026-11-02T10:00:00Z"), 100, 2_500,
    )

    private suspend fun traced(call: ModelCallRecord): RecordedSpan {
        withContext(SpanContextElement(request)) { traces.start() }.end(call)
        return spans.last()
    }

    private fun RecordedSpan.text(key: String) = (attributes[key] as AttributeValue.Text?)?.value

    private fun RecordedSpan.number(key: String) = (attributes[key] as AttributeValue.Integer?)?.value

    @Test
    fun aCallIsAClientSpanOfTheRequestWithItsTokensCostOrgAndSessionButNoContent() = runTest {
        val span = traced(record(InferenceProvider.ANTHROPIC, TokenUsage(100, 20, cacheRead = 30, cacheWrite = 5)))
        assertEquals(Triple("gen_ai.chat", SpanKind.CLIENT, request), Triple(span.name, span.kind, span.parent))
        assertEquals("anthropic", span.text("gen_ai.system"))
        assertEquals("claude-opus-5", span.text("gen_ai.request.model"))
        assertEquals(135L to 20L, span.number("gen_ai.usage.input_tokens") to span.number("gen_ai.usage.output_tokens"))
        assertEquals(AttributeValue.Decimal(0.25), span.attributes["gen_ai.usage.cost"])
        assertEquals(Gw.orgA.toString(), span.text("langfuse.trace.metadata.org_id"))
        assertEquals("s-1", span.text("langfuse.session.id"))
        assertEquals("ok" to 200L, span.text("ostrom.model_call.outcome") to span.number("http.response.status_code"))
        assertEquals(StatusCode.UNSET to "", span.status)
        assertEquals(1, span.ends)
        assertEquals(emptyList(), span.attributes.keys.filter { it.endsWith(".input") || it.endsWith(".output") })
    }

    @Test
    fun aCallRefusedBeforeAnyProviderKeepsItsModelAndOutcome() = runTest {
        val span = traced(record(null, status = 429, outcome = "budget_exhausted").copy(sessionId = null))
        assertEquals(
            setOf("gen_ai.request.model", "ostrom.model_call.outcome", "http.response.status_code"),
            span.attributes.keys,
        )
        assertEquals("budget_exhausted", span.text("ostrom.model_call.outcome"))
        assertEquals(StatusCode.UNSET to "", span.status, "A refusal is an answer, not an error of the gateway")
    }

    @Test
    fun aFailedCallIsAnErrorSpanWithoutACost() = runTest {
        val span = traced(record(InferenceProvider.OPENAI, status = 502, outcome = "upstream_error"))
        assertEquals(StatusCode.ERROR to "upstream_error", span.status)
        assertNull(span.attributes["gen_ai.usage.cost"], "Not priced")
        assertEquals(0L, span.number("gen_ai.usage.input_tokens"))
    }

    @Test
    fun aStreamCutAfterItsFirstBytesIsAnErrorThoughTheAgentSawA200() = runTest {
        val cut = traced(record(InferenceProvider.ANTHROPIC, status = 200, outcome = "upstream_broken"))
        assertEquals(StatusCode.ERROR to "upstream_broken", cut.status)
        val gone = traced(record(InferenceProvider.ANTHROPIC, status = 200, outcome = "agent_gone"))
        assertEquals(StatusCode.UNSET to "", gone.status, "The agent left: not the gateway's error")
    }

    @Test
    fun aCallAbandonedBeforeAnyAnswerHasNeitherAStatusNorABlankModel() = runTest {
        val span = traced(record(null, status = 0, outcome = "cancelled").copy(model = ""))
        assertEquals(setOf("ostrom.model_call.outcome"), span.attributes.keys)
        assertEquals(StatusCode.UNSET to "", span.status)
    }

    @Test
    fun aSpanIsEndedEvenWhenDescribingTheCallFails() = runTest {
        broken = true
        val trace = traces.start()
        assertFailsWith<IllegalStateException> { trace.end(record(InferenceProvider.ANTHROPIC)) }
        assertEquals(1, spans.single().ends, "Never left open")
    }
}
