// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.infrastructure

import dev.ostrom.core.telemetry.genai.ContentCapture
import dev.ostrom.core.telemetry.genai.ModelCall
import dev.ostrom.core.telemetry.trace.Span
import dev.ostrom.core.telemetry.trace.SpanKind
import dev.ostrom.core.telemetry.trace.StatusCode
import dev.ostrom.core.telemetry.trace.Tracer
import dev.ostrom.core.telemetry.trace.currentSpanContext
import dev.ostrom.core.telemetry.trace.setAttribute
import dev.ostrom.core.telemetry.trace.setAttributes
import dev.ostrom.server.gateway.domain.ModelCallRecord
import dev.ostrom.server.gateway.domain.ModelCallTrace
import dev.ostrom.server.gateway.domain.ModelCallTracer

private const val SERVER_ERROR = 500

/**
 * Traces each relayed call as a CLIENT span `gen_ai.chat` ([ModelCall.SPAN_NAME]), child of the request's span: it
 * covers every try, from before planning to the call's record. It carries the model, the provider, the tokens, the
 * cost, the organization and the session ([ModelCall.toAttributes], content never captured: no prompt, no answer),
 * the outcome and the status; a status of 500 or more marks it as an error. A call refused before any provider was
 * chosen (a budget, a policy) keeps the model asked for and its outcome only.
 *
 * @param tracer The server's tracer.
 */
class SpanModelCallTracer(private val tracer: Tracer) : ModelCallTracer {
    override suspend fun start(): ModelCallTrace {
        val span = tracer.startSpan(ModelCall.SPAN_NAME, SpanKind.CLIENT, currentSpanContext())
        return ModelCallTrace { call -> span.finish(call) }
    }

    private fun Span.finish(call: ModelCallRecord) {
        val provider = call.provider
        if (provider == null) {
            setAttribute("gen_ai.request.model", call.model)
        } else {
            val described = call.toModelCall(provider).copy(orgId = call.orgId.toString(), sessionId = call.sessionId)
            setAttributes(described.toAttributes(ContentCapture.NONE))
        }
        setAttribute("ostrom.model_call.outcome", call.outcome)
        setAttribute("http.response.status_code", call.status.toLong())
        if (call.status >= SERVER_ERROR) setStatus(StatusCode.ERROR, call.outcome)
        end()
    }
}
