// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The tokens of the model calls of one bucket of time (an hour or a UTC day, by its number since the epoch), one
 * provider and one model, summed.
 *
 * @property bucket The hour or day number.
 * @property provider The provider as the ledger records it (`claude`, `anthropic`, `codex`, `openai`...), when known.
 * @property model The model.
 * @property input Uncached input tokens.
 * @property output Output tokens.
 * @property cacheRead Input tokens read from the cache.
 * @property cacheWrite Input tokens written to the cache.
 */
data class TokenBucket(
    val bucket: Long,
    val provider: String?,
    val model: String,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
)

/**
 * The tokens of the ledger's model calls by hour or by day, for the automatic pool size (TeamCode's
 * `account_token_usage` and `token_usage` reads). Bound in Koin by the usage module; the budgets read it. Every read is
 * bounded by an index prefix and keeps the newest buckets past its limit.
 */
interface TokenUsageReader {
    /**
     * One shared account's tokens by UTC hour since [since], newest first.
     *
     * @param orgId The org.
     * @param accountId The account.
     * @param since The first instant counted.
     * @param limit The most rows.
     */
    suspend fun accountHours(orgId: Uuid, accountId: Uuid, since: Instant, limit: Int): List<TokenBucket>

    /**
     * The org's tokens by UTC day since [since], newest first.
     *
     * @param orgId The org.
     * @param since The first instant counted.
     * @param limit The most rows.
     */
    suspend fun orgDays(orgId: Uuid, since: Instant, limit: Int): List<TokenBucket>
}
