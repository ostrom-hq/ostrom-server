// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.gateway.domain.ModelCallRecord
import dev.ostrom.server.gateway.domain.ModelUsageSink
import dev.ostrom.server.gateway.domain.TokenUsage
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRecorder

/**
 * The gateway's model calls into the ledger: each call is one MODEL event, with its tokens, its cost and the launch of
 * the agent token that made it. The spending, the budgets and a session's receipt read them from there.
 *
 * @param recorder Writes and publishes the event; a failed write is logged, never thrown at the gateway.
 */
class LedgerModelUsageSink(private val recorder: UsageRecorder) : ModelUsageSink {
    override suspend fun record(call: ModelCallRecord) {
        recorder.record(call.toUsageEvent())
    }
}

/** The ledger's event of a model call; a call that reached no provider has no tokens. */
fun ModelCallRecord.toUsageEvent(): UsageEvent {
    val tokens = usage ?: TokenUsage()
    return UsageEvent(
        id = id,
        orgId = orgId,
        memberId = memberId,
        kind = UsageKind.MODEL,
        name = model,
        provider = provider?.wire,
        accountId = accountId,
        sessionId = sessionId,
        launchId = launchId,
        inputTokens = tokens.input,
        outputTokens = tokens.output,
        cacheReadTokens = tokens.cacheRead,
        cacheWriteTokens = tokens.cacheWrite,
        costMicros = costMicros,
        durationMillis = durationMillis,
        outcome = outcome,
        at = startedAt,
    )
}
