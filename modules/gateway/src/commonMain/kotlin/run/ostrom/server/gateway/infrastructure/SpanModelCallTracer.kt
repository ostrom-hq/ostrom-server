// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.infrastructure

import run.ostrom.core.telemetry.genai.ContentCapture
import run.ostrom.core.telemetry.genai.ModelCall
import run.ostrom.core.telemetry.trace.Span
import run.ostrom.core.telemetry.trace.SpanKind
import run.ostrom.core.telemetry.trace.StatusCode
import run.ostrom.core.telemetry.trace.Tracer
import run.ostrom.core.telemetry.trace.currentSpanContext
import run.ostrom.core.telemetry.trace.setAttribute
import run.ostrom.core.telemetry.trace.setAttributes
import run.ostrom.server.gateway.domain.ModelCallRecord
import run.ostrom.server.gateway.domain.ModelCallTrace
import run.ostrom.server.gateway.domain.ModelCallTracer

private const val SERVER_ERROR = 500

/** Outcomes of a call the gateway or its provider failed, whatever status the agent saw (a stream cut after 200). */
private val FAILED = setOf("upstream_broken", "upstream_unreachable", "gateway_error")

/**
 * Traces each relayed call as a CLIENT span `gen_ai.chat` ([ModelCall.SPAN_NAME]), child of the request's span: it
 * covers every try, from before planning to the call's record. It carries the model, the provider, the tokens, the
 * cost, the organization and the session ([ModelCall.toAttributes], content never captured: no prompt, no answer),
 * the outcome and the HTTP status the agent got (none when it got none). A call the gateway or its provider failed is
 * an error span; a refusal (a budget, a policy, a provider's 4xx) and an agent leaving are not. A call refused before
 * any provider was chosen keeps the model asked for and its outcome only.
 *
 * @param tracer The server's tracer.
 */
class SpanModelCallTracer(private val tracer: Tracer) : ModelCallTracer {
    override suspend fun start(): ModelCallTrace {
        val span = tracer.startSpan(ModelCall.SPAN_NAME, SpanKind.CLIENT, currentSpanContext())
        return ModelCallTrace { call ->
            // Ended on every path: a span left open is never exported.
            try {
                span.describe(call)
            } finally {
                span.end()
            }
        }
    }

    private fun Span.describe(call: ModelCallRecord) {
        val provider = call.provider
        if (provider != null) {
            val described = call.toModelCall(provider).copy(orgId = call.orgId.toString(), sessionId = call.sessionId)
            setAttributes(described.toAttributes(ContentCapture.NONE))
        } else if (call.model.isNotBlank()) {
            setAttribute("gen_ai.request.model", call.model)
        }
        setAttribute("ostrom.model_call.outcome", call.outcome)
        if (call.status > 0) setAttribute("http.response.status_code", call.status.toLong())
        if (call.status >= SERVER_ERROR || call.outcome in FAILED) setStatus(StatusCode.ERROR, call.outcome)
    }
}
