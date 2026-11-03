// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import dev.ostrom.server.secrets.domain.Caller
import kotlin.uuid.Uuid

/**
 * Asks the budgets ([SpendGuard]) whether a call may go, with what it may cost at most ([reserveMicros]): the output
 * the body allows at the output price, plus the input its size suggests at the dearer of the input and cache-write
 * prices.
 *
 * The output part is a true bound: a provider never writes more than the body's `max_tokens` (or [defaultMaxOutput]
 * when it names none, where the provider's own default may be larger). The input part is an estimate, one token for
 * every [CHARS_PER_TOKEN] characters of the body: a provider that counts more tokens bills more than was held, by
 * that gap. A model without a price holds nothing, as it costs nothing in the ledger.
 *
 * @param guard The budgets.
 * @param prices The prices.
 * @param defaultMaxOutput The output assumed when the body bounds none.
 */
class BudgetCheck(
    private val guard: SpendGuard,
    private val prices: PriceTable,
    private val defaultMaxOutput: Long = DEFAULT_MAX_OUTPUT,
) {
    /**
     * Decides for a call, and holds its reserve when it may go.
     *
     * @param caller The member.
     * @param callId The call; every try of it asks with the same id.
     * @param protocol The API shape, which names the price family.
     * @param body The body.
     *
     * @return The decision.
     */
    suspend fun check(caller: Caller, callId: Uuid, protocol: WireProtocol, body: RequestBody): SpendDecision {
        val reserve = reserveMicros(protocol.keyProvider(body.model), body)
        return guard.check(SpendRequest(caller.orgId, caller.memberId, callId, reserve, caller.workflowId))
    }

    /**
     * The most a call may cost.
     *
     * @param provider Where it goes: only its price family counts.
     * @param body Its body.
     *
     * @return USD millionths; 0 for a model without a price.
     */
    fun reserveMicros(provider: InferenceProvider, body: RequestBody): Long {
        val price = prices.priceOf(provider, body.model) ?: return 0
        val output = (body.maxOutputTokens ?: defaultMaxOutput).coerceAtMost(MAX_OUTPUT)
        val input = body.text.length.toLong() / CHARS_PER_TOKEN + 1
        return ceilMicros(output, price.output) + ceilMicros(input, maxOf(price.input, price.cacheWrite))
    }

    private fun ceilMicros(tokens: Long, perMillion: Long): Long = (tokens * perMillion + PER_MILLION - 1) / PER_MILLION

    /** The bounds of the estimate. */
    companion object {
        /** The output held when the body bounds none. */
        const val DEFAULT_MAX_OUTPUT: Long = 32_000

        /** The most output tokens held: no model writes more in one call, and the product stays far from overflow. */
        const val MAX_OUTPUT: Long = 1_000_000

        /** Characters of body per input token: fewer than tokenizers read, so the estimate rarely falls short. */
        const val CHARS_PER_TOKEN: Long = 2

        private const val PER_MILLION = 1_000_000L
    }
}
