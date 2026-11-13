// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.gateway.domain.ModelCallRecord
import run.ostrom.server.gateway.domain.ModelUsageSink
import run.ostrom.server.gateway.domain.TokenUsage
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRecorder

/**
 * The gateway's model calls into the ledger: each call is one MODEL event, with its tokens, its cost and the launch of
 * the agent token that made it, written with the release of the call's budget hold. The spending, the budgets and a
 * session's receipt read them from there.
 *
 * @param recorder Writes and publishes the event; a failed write is logged, never thrown at the gateway.
 */
class LedgerModelUsageSink(private val recorder: UsageRecorder) : ModelUsageSink {
    override suspend fun record(call: ModelCallRecord) {
        recorder.settle(call.toUsageEvent())
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
        workflowId = workflowId,
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
