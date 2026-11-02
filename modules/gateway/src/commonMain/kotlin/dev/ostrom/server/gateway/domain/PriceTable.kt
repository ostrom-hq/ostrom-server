// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import kotlin.math.roundToLong

/**
 * A price per million tokens, in USD millionths.
 *
 * @property provider `anthropic` or `openai` ([InferenceProvider.family]).
 * @property model The model or a family prefix.
 * @property input Uncached input.
 * @property cacheWrite Input written to the cache.
 * @property cacheRead Input read from the cache.
 * @property output Output.
 */
data class ModelPrice(
    val provider: String,
    val model: String,
    val input: Long,
    val cacheWrite: Long,
    val cacheRead: Long,
    val output: Long,
)

/**
 * Equivalent API cost of a call. An exact model wins over the longest prefix. Models without a row are unpriced (cost
 * 0, flagged), never guessed.
 *
 * @param prices The rows.
 */
class PriceTable(private val prices: List<ModelPrice> = ANTHROPIC_LIST_PRICES) {
    /** The cost in USD millionths, or null when no row prices the model. */
    fun costMicros(provider: InferenceProvider, model: String, usage: TokenUsage): Long? =
        priceOf(provider, model)?.let {
            cost(usage.input, it.input) + cost(usage.cacheWrite, it.cacheWrite) +
                cost(usage.cacheRead, it.cacheRead) + cost(usage.output, it.output)
        }

    /** The row that prices [model] at [provider]'s family, or null when none does. */
    fun priceOf(provider: InferenceProvider, model: String): ModelPrice? {
        val served = model.lowercase()
        return prices.filter { it.provider == provider.family && served.startsWith(it.model) }
            .maxWithOrNull(compareBy<ModelPrice>({ it.model == served }, { it.model.length }))
    }

    private fun cost(count: Long, perMillion: Long): Long = (count.toDouble() * perMillion / PER_MILLION).roundToLong()

    /** Anthropic's list prices. */
    companion object {
        private const val PER_MILLION = 1_000_000.0

        private fun anthropic(model: String, input: Long, cacheWrite: Long, cacheRead: Long, output: Long) =
            ModelPrice("anthropic", model, input, cacheWrite, cacheRead, output)

        /**
         * Anthropic's pay-as-you-go list prices read on 2026-10-21 from
         * https://platform.claude.com/docs/en/about-claude/pricing (TeamCode's `AnthropicListPrices`), USD millionths
         * per million tokens; cache writes at the 5-minute rate.
         */
        val ANTHROPIC_LIST_PRICES: List<ModelPrice> = listOf(
            anthropic("claude-fable-5-1", 10_000_000, 12_500_000, 250_000, 50_000_000),
            anthropic("claude-fable-5", 10_000_000, 12_500_000, 1_000_000, 50_000_000),
            anthropic("claude-opus-5-5", 4_000_000, 5_000_000, 200_000, 20_000_000),
            anthropic("claude-opus-5", 5_000_000, 6_250_000, 500_000, 25_000_000),
            anthropic("claude-opus-4-8", 5_000_000, 6_250_000, 500_000, 25_000_000),
            anthropic("claude-opus-4-7", 5_000_000, 6_250_000, 500_000, 25_000_000),
            anthropic("claude-opus-4-6", 5_000_000, 6_250_000, 500_000, 25_000_000),
            anthropic("claude-opus-4-5", 5_000_000, 6_250_000, 500_000, 25_000_000),
            anthropic("claude-sonnet-5-5", 2_000_000, 2_500_000, 100_000, 10_000_000),
            anthropic("claude-sonnet-5", 2_000_000, 2_500_000, 200_000, 10_000_000),
            anthropic("claude-sonnet-4-6", 3_000_000, 3_750_000, 300_000, 15_000_000),
            anthropic("claude-sonnet-4-5", 3_000_000, 3_750_000, 300_000, 15_000_000),
            anthropic("claude-haiku-5-5", 100_000, 125_000, 10_000, 500_000),
            anthropic("claude-haiku-4-5", 1_000_000, 1_250_000, 100_000, 5_000_000),
        )
    }
}
