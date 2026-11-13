// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.domain

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

/**
 * The events a summary totals.
 *
 * @property orgId The org.
 * @property memberId One member, or null for the whole org.
 * @property since The start, included.
 * @property until The end, excluded, or null for now.
 */
data class UsageWindow(val orgId: Uuid, val memberId: Uuid?, val since: Instant, val until: Instant? = null)

/** The ledger. Every read is scoped by org (or by pooled account) and bounded. */
@Suppress("TooManyFunctions") // The one storage port of the module: its writes, holds, sums and reads.
interface UsageRepository {
    /**
     * Writes events of one org, all in one statement.
     *
     * @param orgId The org of every event.
     * @param events The events.
     */
    suspend fun insert(orgId: Uuid, events: List<UsageEvent>)

    /**
     * Writes a model call's event and drops its budget hold ([holdWithin]), in one transaction: what the call cost
     * replaces what it held, with no instant where both or neither count.
     *
     * @param event The call's event; its id is the hold's call.
     */
    suspend fun settle(event: UsageEvent)

    /**
     * Admits a call against the limits and writes its hold, in one transaction of the org's, on one connection, under
     * a lock of the org: the calls of an org are admitted one at a time, each seeing every hold written before it.
     * Expired holds of the org are dropped there.
     *
     * @param hold The call and its reserve; a call asking again replaces its hold.
     * @param limits The limits that apply; only their sums are read.
     * @param window The month counted, now, and when the new hold expires.
     *
     * @return The answer of [SpendLimits.admit]; the hold is written only when [Admission.Admitted].
     */
    suspend fun holdWithin(hold: SpendHold, limits: SpendLimits, window: HoldWindow): Admission

    /**
     * Drops a call's hold without recording it: the call waits in the gateway's admission queue and holds nothing
     * meanwhile; it asks again ([holdWithin]) when its turn comes. Nothing when it holds none.
     *
     * @param orgId The org.
     * @param callId The call.
     */
    suspend fun dropHold(orgId: Uuid, callId: Uuid)

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
     * Sums the cost spent on one pooled account of the org since a time.
     *
     * @param orgId The org that shares the account.
     * @param accountId The account.
     * @param since The start, included.
     *
     * @return USD millionths.
     */
    suspend fun accountSpentMicros(orgId: Uuid, accountId: Uuid, since: Instant): Long

    /**
     * Sums the cost of the model calls of the launches that play one workflow of the org since a time.
     *
     * @param orgId The org.
     * @param workflowId The workflow.
     * @param since The start, included.
     *
     * @return USD millionths.
     */
    suspend fun workflowSpentMicros(orgId: Uuid, workflowId: String, since: Instant): Long

    /**
     * Sums the cost one launch's agent token spent in a window (a session segment's receipt).
     *
     * @param orgId The org.
     * @param launchId The launch.
     * @param from The start, included.
     * @param to The end, excluded.
     *
     * @return USD millionths, or null when no priced call of the launch falls in the window.
     */
    suspend fun launchSpentMicros(orgId: Uuid, launchId: String, from: Instant, to: Instant): Long?

    /**
     * Totals the events of a window per group, most expensive first.
     *
     * @param window The org, the member (or the whole org) and the times.
     * @param groupBy The grouping; [UsageGroupBy.MODEL] only counts model events.
     * @param limit The maximum number of groups.
     *
     * @return The groups.
     */
    suspend fun summary(window: UsageWindow, groupBy: UsageGroupBy, limit: Int): List<UsageTotal>

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
