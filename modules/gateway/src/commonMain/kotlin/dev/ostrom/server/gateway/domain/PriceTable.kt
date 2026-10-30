package dev.ostrom.server.gateway.domain

import kotlin.math.roundToLong

/**
 * A price per million tokens, in USD millionths.
 *
 * @property provider `anthropic` or `openai` (shared accounts are priced as their API equivalent).
 * @property model The model or a family prefix.
 */
data class ModelPrice(val provider: String, val model: String, val input: Long, val cacheWrite: Long, val cacheRead: Long, val output: Long)

/**
 * Equivalent API cost of a call. An exact model wins over the longest prefix. Models without a row are unpriced (cost
 * 0, flagged), never guessed.
 *
 * @param prices The rows.
 */
class PriceTable(private val prices: List<ModelPrice> = ANTHROPIC_LIST_PRICES) {
    /** The cost in USD millionths, or null when no row prices the model. */
    fun costMicros(provider: InferenceProvider, model: String, usage: TokenUsage): Long? {
        val family = when (provider) {
            InferenceProvider.CLAUDE_SHARED, InferenceProvider.ANTHROPIC -> "anthropic"
            InferenceProvider.CODEX_SHARED, InferenceProvider.OPENAI -> "openai"
            InferenceProvider.OPENROUTER -> "openrouter"
        }
        val served = model.lowercase()
        val price = prices.filter { it.provider == family && served.startsWith(it.model) }
            .maxWithOrNull(compareBy<ModelPrice>({ it.model == served }, { it.model.length })) ?: return null
        fun of(count: Long, perMillion: Long) = (count.toDouble() * perMillion / 1_000_000).roundToLong()
        return of(usage.input, price.input) + of(usage.cacheWrite, price.cacheWrite) + of(usage.cacheRead, price.cacheRead) + of(usage.output, price.output)
    }

    companion object {
        private fun anthropic(model: String, input: Long, cacheWrite: Long, cacheRead: Long, output: Long) =
            ModelPrice("anthropic", model, input, cacheWrite, cacheRead, output)

        /**
         * Anthropic's pay-as-you-go list prices read on 2026-10-21 from https://platform.claude.com/docs/en/about-claude/pricing
         * (TeamCode's `AnthropicListPrices`), USD millionths per million tokens; cache writes at the 5-minute rate.
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
