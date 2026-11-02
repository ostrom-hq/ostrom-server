// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.infrastructure

import dev.ostrom.core.telemetry.genai.ModelCall
import dev.ostrom.core.telemetry.genai.ModelCallSource
import dev.ostrom.server.gateway.domain.ModelCallObserver
import dev.ostrom.server.gateway.domain.ModelCallRecord
import dev.ostrom.server.kernel.observability.OstromMetrics
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adds every relayed call to the inference metrics ([OstromMetrics.modelCalled]: cost, tokens, duration, by provider),
 * then hands it to [next]. A call refused before a provider was chosen reached no model and is not counted.
 *
 * @param metrics The server's instruments.
 * @param next The observer that traces the call.
 */
class MeteredModelCallObserver(
    private val metrics: OstromMetrics,
    private val next: ModelCallObserver,
) : ModelCallObserver {
    override suspend fun observe(call: ModelCallRecord) {
        call.provider?.let { provider ->
            val usage = call.usage
            val model = ModelCall(
                source = ModelCallSource.GATEWAY,
                provider = provider.wire,
                requestModel = call.model,
                inputTokens = usage?.let { it.input + it.cacheRead + it.cacheWrite } ?: 0,
                outputTokens = usage?.output ?: 0,
                costUsd = if (call.priced) call.costMicros / MICROS_PER_USD else null,
            )
            metrics.modelCalled(model, call.durationMillis.milliseconds)
        }
        next.observe(call)
    }
}

private const val MICROS_PER_USD = 1_000_000.0
