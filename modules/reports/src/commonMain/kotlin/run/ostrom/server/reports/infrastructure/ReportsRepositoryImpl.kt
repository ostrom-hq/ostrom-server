// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.reports.domain.ActivityCursor
import run.ostrom.server.reports.domain.ActivityItem
import run.ostrom.server.reports.domain.ActivityKind
import run.ostrom.server.reports.domain.ReportsRepository
import run.ostrom.server.reports.domain.SpendingDay
import run.ostrom.server.reports.domain.SpendingLine
import run.ostrom.server.reports.domain.SpendingTotals
import kotlinx.datetime.LocalDate
import kotlin.math.roundToLong
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ReportsRepository] on PostgreSQL. Each read runs in a transaction scoped to the organization ([withOrganization]):
 * the row level security of the tables it reads applies, on top of their `org_id` filters.
 *
 * @param database The database.
 */
class ReportsRepositoryImpl(private val database: JdbcDatabase) : ReportsRepository {
    override suspend fun spending(orgId: Uuid, memberId: Uuid?, from: Instant, to: Instant): SpendingTotals {
        val rows = spendingRows(orgId, memberId, SpendingRanges.of(from, to))
        fun lines(dim: String) = rows.filter { it.dim == dim }.map { it.toLine() }
        return SpendingTotals(
            byAgent = lines(SpendingReads.AGENT),
            byRepo = lines(SpendingReads.REPO),
            byPerson = lines(SpendingReads.PERSON),
            byDay = rows.filter { it.dim == SpendingReads.DAY }
                .map { SpendingDay(LocalDate.parse(it.key), it.micros) }
                .sortedBy { it.date },
        )
    }

    /**
     * The lines of the spending report over [ranges], as they are added up.
     *
     * @param orgId The organization.
     * @param memberId One member's spending only, when set.
     * @param ranges The window.
     * @return The lines, in no order.
     */
    internal suspend fun spendingRows(orgId: Uuid, memberId: Uuid?, ranges: SpendingRanges): List<SpendingRow> =
        database.withOrganization(orgId) { with(SpendingReads(orgId, memberId, ranges)) { rows() } }

    override suspend fun activity(
        orgId: Uuid,
        before: ActivityCursor?,
        repo: String?,
        limit: Int,
    ): List<ActivityItem> = database.withOrganization(orgId) {
        val reads = ActivityReads(orgId, before, repo, limit)
        reads.page().map { row ->
            ActivityItem(
                id = row[reads.lineId],
                at = row[reads.lineAt],
                kind = KINDS.getValue(row[reads.lineKind]),
                actor = row[reads.lineActor],
                subject = row[reads.lineSubject],
                outcome = row[reads.lineOutcome],
                repo = row[reads.lineRepo],
                costMicros = row[reads.lineCost]?.let { (it * MICROS_PER_USD).roundToLong() },
                sessionId = row[reads.lineSession],
                gateId = row[reads.lineGate],
            )
        }
    }

    private companion object {
        const val MICROS_PER_USD = 1_000_000.0
        val KINDS = ActivityKind.entries.associateBy { it.wire }

        fun SpendingRow.toLine(): SpendingLine {
            val shown = label.ifEmpty { if (dim == SpendingReads.PERSON) "Organization" else "No session" }
            return SpendingLine(key, shown, micros, sessions)
        }
    }
}
