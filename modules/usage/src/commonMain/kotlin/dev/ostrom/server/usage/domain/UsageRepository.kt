// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How a usage summary groups the ledger. */
enum class UsageGroupBy { MEMBER, KIND, MODEL, PROVIDER, ACCOUNT }

/**
 * The totals of one group of a summary.
 *
 * @property key The member id, kind, model id, provider or account id; null for events without one (no provider).
 * @property events The number of events.
 * @property inputTokens Input tokens.
 * @property outputTokens Output tokens.
 * @property cacheReadTokens Cache read tokens.
 * @property cacheWriteTokens Cache write tokens.
 * @property costMicros The cost, USD millionths.
 */
data class UsageTotal(
    val key: String?,
    val events: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val costMicros: Long,
)

/**
 * Where a page of the ledger starts: events strictly older than this position, newest first.
 *
 * @property at The time of the last event of the previous page.
 * @property id Its id, which orders events of the same instant; null to take everything before [at].
 */
data class UsageCursor(val at: Instant, val id: Uuid?)

/** The ledger. Every read is scoped by org (or by pooled account) and bounded. */
interface UsageRepository {
    /**
     * Writes events, all in one statement.
     *
     * @param events The events.
     */
    suspend fun insert(events: List<UsageEvent>)

    /**
     * Sums the cost of the org, or of one member, since a time.
     *
     * @param orgId The org.
     * @param memberId The member, or null for the whole org.
     * @param since The start, included.
     *
     * @return USD millionths.
     */
    suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long

    /**
     * Sums the cost spent on one pooled account since a time.
     *
     * @param accountId The account.
     * @param since The start, included.
     *
     * @return USD millionths.
     */
    suspend fun accountSpentMicros(accountId: Uuid, since: Instant): Long

    /**
     * Totals the org's events (or one member's) per group, most expensive first.
     *
     * @param orgId The org.
     * @param memberId The member, or null for the whole org.
     * @param since The start, included.
     * @param groupBy The grouping; [UsageGroupBy.MODEL] only counts model events.
     * @param limit The maximum number of groups.
     *
     * @return The groups.
     */
    suspend fun summary(orgId: Uuid, memberId: Uuid?, since: Instant, groupBy: UsageGroupBy, limit: Int): List<UsageTotal>

    /**
     * Lists events, newest first.
     *
     * @param orgId The org.
     * @param memberId The member, or null for the whole org.
     * @param before Keyset cursor, or null for the newest.
     * @param limit The page size.
     *
     * @return The events.
     */
    suspend fun list(orgId: Uuid, memberId: Uuid?, before: UsageCursor?, limit: Int): List<UsageEvent>

    /**
     * Deletes up to [limit] events older than a time, every org included.
     *
     * @param before The cut-off, excluded.
     * @param limit The batch size: one statement never holds a lock on the whole table.
     *
     * @return The number of events deleted.
     */
    suspend fun purge(before: Instant, limit: Int): Long
}
