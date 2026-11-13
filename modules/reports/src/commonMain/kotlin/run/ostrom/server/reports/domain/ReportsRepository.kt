// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The reads of the reports, each in one transaction scoped to the organization (row level security applies).
 */
interface ReportsRepository {
    /**
     * Totals the ledger of a window.
     *
     * @param orgId The organization.
     * @param memberId Only this member's spending, or null for the whole organization.
     * @param from The start, included.
     * @param to The end, excluded.
     * @return The totals.
     */
    suspend fun spending(orgId: Uuid, memberId: Uuid?, from: Instant, to: Instant): SpendingTotals

    /**
     * A page of the activity, newest first.
     *
     * @param orgId The organization.
     * @param before Only what happened before this position, or null for the newest.
     * @param repo Only what concerns this repository, or null.
     * @param limit The page size.
     * @return The items.
     */
    suspend fun activity(orgId: Uuid, before: ActivityCursor?, repo: String?, limit: Int): List<ActivityItem>
}
