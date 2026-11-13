// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.ModelCallObserver
import run.ostrom.server.gateway.domain.ModelCallRecord
import run.ostrom.server.kernel.observability.OstromMetrics
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
        call.provider?.let { metrics.modelCalled(call.toModelCall(it), call.durationMillis.milliseconds) }
        next.observe(call)
    }
}
