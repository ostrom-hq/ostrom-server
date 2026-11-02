// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports

import dev.ostrom.server.reports.domain.ActivityCursor
import dev.ostrom.server.reports.domain.ActivityItem
import dev.ostrom.server.reports.domain.ReportsRepository
import dev.ostrom.server.reports.domain.SpendingBudgets
import dev.ostrom.server.reports.domain.SpendingTotals
import dev.ostrom.server.reports.domain.Viewer
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")
val NOW: Instant = Instant.parse("2026-10-30T12:00:00Z")

class FixedClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now
}

/** Answers what the test sets and records what it was asked. */
class FakeReportsRepository : ReportsRepository {
    var totals = SpendingTotals(emptyList(), emptyList(), emptyList(), emptyList())
    var items: List<ActivityItem> = emptyList()
    val spendingAsked = mutableListOf<List<Any?>>()
    val activityAsked = mutableListOf<List<Any?>>()

    override suspend fun spending(orgId: Uuid, memberId: Uuid?, from: Instant, to: Instant): SpendingTotals {
        spendingAsked += listOf(orgId, memberId, from, to)
        return totals
    }

    override suspend fun activity(orgId: Uuid, before: ActivityCursor?, repo: String?, limit: Int): List<ActivityItem> {
        activityAsked += listOf(orgId, before, repo, limit)
        return items.take(limit)
    }
}

class FakeBudgets(private val org: Long?, private val member: Long?) : SpendingBudgets {
    override suspend fun monthlyMicros(viewer: Viewer): Long? = if (viewer.wholeOrganization) org else member
}
