// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The readings of the accounts' windows over time (TeamCode's `quota_history`): one per minute and window, the newest
 * of the minute kept, so the history grows with time and not with the gateway's traffic. Scoped by org like the
 * accounts, except [prune], which crosses organizations.
 */
interface QuotaHistoryRepository {
    /**
     * Records the readings of an account of the org: the [isRecordable] ones, each replacing the reading of its minute
     * unless that one is newer. Nothing for an account of another org.
     */
    suspend fun record(orgId: Uuid, accountId: Uuid, windows: List<QuotaWindow>)

    /**
     * The readings of [windows] of an account of the org observed in [observed], newest first, at most
     * [limit].
     */
    suspend fun recent(
        orgId: Uuid,
        accountId: Uuid,
        windows: List<String>,
        observed: ClosedRange<Instant>,
        limit: Int,
    ): List<QuotaWindow>

    /**
     * Deletes at most [limit] readings observed before [before], of every organization, never one younger than
     * [QUOTA_HISTORY_RETENTION].
     *
     * @return How many were deleted.
     *
     * @throws run.ostrom.server.kernel.database.PurgeDateRefusedException When [before] is in the future or before
     *   1970.
     */
    suspend fun prune(before: Instant, limit: Int): Long

    /** The bounds. */
    companion object {
        /** How long readings are kept (TeamCode's seven days). */
        val QUOTA_HISTORY_RETENTION: Duration = 7.days

        /** Readings deleted at most per pass (TeamCode's 1,000). */
        const val PRUNE_BATCH: Int = 1_000

        /** The longest window name kept. */
        const val MAX_WINDOW_NAME: Int = 80
    }
}

/**
 * Whether a reading is kept in the history (TeamCode's checks): a name of 1 to 80 characters, a utilization from 0 to
 * 1, and a reset, when known, after the reading.
 */
fun QuotaWindow.isRecordable(): Boolean = window.length in 1..QuotaHistoryRepository.MAX_WINDOW_NAME &&
    utilization.isFinite() && utilization in 0.0..1.0 && (resetsAt == null || resetsAt > observedAt)

/** No history: the pool's default where none is bound (the gateway's tests). */
object NoQuotaHistory : QuotaHistoryRepository {
    override suspend fun record(orgId: Uuid, accountId: Uuid, windows: List<QuotaWindow>) = Unit

    override suspend fun recent(
        orgId: Uuid,
        accountId: Uuid,
        windows: List<String>,
        observed: ClosedRange<Instant>,
        limit: Int,
    ): List<QuotaWindow> = emptyList()

    override suspend fun prune(before: Instant, limit: Int): Long = 0
}
