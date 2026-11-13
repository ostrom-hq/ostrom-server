// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.core.telemetry.genai.ModelCall
import run.ostrom.core.telemetry.genai.ModelCallSource
import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.ModelCallRecord

private const val MICROS_PER_USD = 1_000_000.0

/**
 * The call as the telemetry describes a model call (OpenTelemetry GenAI conventions): input tokens count the cache
 * reads and writes, the cost is in dollars when the call was priced. No organization nor session: a metric never
 * carries them as labels; a span adds them.
 *
 * @param provider The provider the call went to.
 * @return The description.
 */
internal fun ModelCallRecord.toModelCall(provider: InferenceProvider): ModelCall = ModelCall(
    source = ModelCallSource.GATEWAY,
    provider = provider.wire,
    requestModel = model,
    inputTokens = usage?.let { it.input + it.cacheRead + it.cacheWrite } ?: 0,
    outputTokens = usage?.output ?: 0,
    costUsd = if (priced) costMicros / MICROS_PER_USD else null,
)
