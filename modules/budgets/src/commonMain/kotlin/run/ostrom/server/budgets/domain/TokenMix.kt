// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.domain

import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.ModelPrice
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.TokenUsage
import kotlin.math.roundToLong

// TeamCode's model mix (`ApiPrices.kt`: PricedCost, ModelUsage, TokenMix, PricedTokens), priced by the gateway's
// [PriceTable] of the org. Ostrom's ledger names the provider of the call (`claude`, `anthropic`, `codex`, `openai`):
// models are grouped and priced by its family (`anthropic`, `openai`), as the price table is.

/**
 * An API-equivalent split by token type, in USD millionths.
 *
 * @property inputMicros Uncached input.
 * @property cacheWriteMicros Input written to the cache.
 * @property cacheReadMicros Input read from the cache.
 * @property outputMicros Output.
 */
data class PricedCost(
    val inputMicros: Long = 0,
    val cacheWriteMicros: Long = 0,
    val cacheReadMicros: Long = 0,
    val outputMicros: Long = 0,
) {
    /** The whole cost. */
    val totalMicros: Long get() = inputMicros + cacheWriteMicros + cacheReadMicros + outputMicros

    /** Both costs, type by type. */
    operator fun plus(other: PricedCost): PricedCost = PricedCost(
        inputMicros + other.inputMicros,
        cacheWriteMicros + other.cacheWriteMicros,
        cacheReadMicros + other.cacheReadMicros,
        outputMicros + other.outputMicros,
    )
}

/** Every token of this usage, cached or not: what "tokens" means when a capacity is converted from dollars. */
val TokenUsage.allTokens: Long get() = input + cacheRead + cacheWrite + output

/**
 * One model's tokens over the look-back and the API-equivalent of its priced tokens by token type.
 *
 * @property provider The provider family (`anthropic`, `openai`), or the ledger's provider when it has none.
 * @property model The model.
 * @property tokens Its tokens.
 * @property cost Their cost; null when none is priced.
 * @property unpricedTokens The tokens without a price.
 */
data class ModelUsage(
    val provider: String,
    val model: String,
    val tokens: TokenUsage,
    val cost: PricedCost?,
    val unpricedTokens: Long = 0,
)

/**
 * Tokens of one provider, model and UTC day, as the ledger sums them.
 *
 * @property provider The provider the ledger records, when known.
 * @property model The model.
 * @property day The UTC day number.
 * @property tokens The tokens.
 */
data class PricedTokens(val provider: String?, val model: String, val day: Long, val tokens: TokenUsage)

/** The provider of a ledger's [wire] name, null when the gateway has none of that name. */
internal fun providerOf(wire: String?): InferenceProvider? = InferenceProvider.entries.firstOrNull { it.wire == wire }

/** The API-equivalent of [tokens] by token type, rounded per type as [PriceTable.costMicros]; null without a price. */
internal fun PriceTable.costOf(provider: InferenceProvider?, model: String, tokens: TokenUsage): PricedCost? =
    provider?.let { priceOf(it, model) }?.let { price: ModelPrice ->
        fun of(count: Long, perMillion: Long) = (count.toDouble() * perMillion / PER_MILLION).roundToLong()
        PricedCost(
            of(tokens.input, price.input),
            of(tokens.cacheWrite, price.cacheWrite),
            of(tokens.cacheRead, price.cacheRead),
            of(tokens.output, price.output),
        )
    }

private const val PER_MILLION = 1_000_000.0

/**
 * The org's model mix over the last seven days of the ledger. [tokensPerMicro] converts a capacity in API-equivalent
 * into tokens at that mix (every token type, cache reads included, over priced tokens only); null without priced
 * traffic. A capacity in tokens therefore moves with the mix, which is why the capacity is learned in API-equivalent
 * and only shown in tokens.
 *
 * @property models Each model's tokens and cost, the costliest first.
 * @property tokensPerMicro Tokens per USD millionth.
 */
data class TokenMix(val models: List<ModelUsage>, val tokensPerMicro: Double?) {
    /** The models used without a price, `provider/model`, sorted. */
    val unpricedModels: List<String> get() =
        models.filter { it.unpricedTokens > 0 }.map { "${it.provider}/${it.model}" }.sorted()

    /** The mix of a ledger. */
    companion object {
        /**
         * The mix of [usage] at [prices].
         *
         * @param prices The org's prices.
         * @param usage The ledger's tokens by provider, model and day.
         */
        fun of(prices: PriceTable, usage: List<PricedTokens>): TokenMix {
            var pricedTokens = 0L
            var pricedMicros = 0L
            val models = usage.groupBy { (providerOf(it.provider)?.family ?: it.provider.orEmpty()) to it.model }
                .map { (key, rows) ->
                    var tokens = TokenUsage()
                    var cost: PricedCost? = null
                    var unpriced = 0L
                    for (row in rows) {
                        tokens = TokenUsage(
                            tokens.input + row.tokens.input,
                            tokens.output + row.tokens.output,
                            tokens.cacheRead + row.tokens.cacheRead,
                            tokens.cacheWrite + row.tokens.cacheWrite,
                        )
                        val priced = prices.costOf(providerOf(row.provider), row.model, row.tokens)
                        if (priced == null) {
                            unpriced += row.tokens.allTokens
                        } else {
                            cost = (cost ?: PricedCost()) + priced
                            pricedTokens += row.tokens.allTokens
                            pricedMicros += priced.totalMicros
                        }
                    }
                    ModelUsage(key.first, key.second, tokens, cost, unpriced)
                }
                .sortedWith(
                    compareByDescending<ModelUsage> { it.cost?.totalMicros ?: -1L }
                        .thenBy { it.provider }.thenBy { it.model },
                )
            return TokenMix(models, if (pricedMicros > 0) pricedTokens.toDouble() / pricedMicros else null)
        }
    }
}
