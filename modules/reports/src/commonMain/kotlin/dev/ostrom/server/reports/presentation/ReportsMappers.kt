// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports.presentation

import dev.ostrom.server.reports.domain.ActivityItem
import dev.ostrom.server.reports.domain.ActivityKind
import dev.ostrom.server.reports.domain.ActivityPage
import dev.ostrom.server.reports.domain.SpendingDay
import dev.ostrom.server.reports.domain.SpendingLine
import dev.ostrom.server.reports.domain.SpendingReport

private const val MICROS_PER_USD = 1_000_000.0

private fun usd(micros: Long): Double = micros / MICROS_PER_USD

/** Maps the report to the contract. */
fun SpendingReport.toDto() = SpendingDto(
    period = period.wire,
    from = from.toString(),
    to = to.toString(),
    totalUsd = usd(totalMicros),
    budgetUsd = budgetMicros?.let(::usd),
    byAgent = totals.byAgent.map { it.toDto() },
    byRepo = totals.byRepo.map { it.toDto() },
    byPerson = totals.byPerson.map { it.toDto() },
    daily = daily.map { it.toDto() },
)

/** Maps a line. */
fun SpendingLine.toDto() = SpendingLineDto(key, label, usd(micros), sessions)

/** Maps a day. */
fun SpendingDay.toDto() = SpendingDayDto(date.toString(), usd(micros))

/** Maps a page of the feed. */
fun ActivityPage.toDto() = ActivityPageDto(items.map { it.toDto() }, next?.wire)

/** Maps an item, with its one-line summary. */
fun ActivityItem.toDto() = ActivityItemDto(
    id = id,
    at = at.toString(),
    kind = kind.wire,
    actor = ActivityActorDto(if (kind.byAgent) "agent" else "person", actor),
    summary = summary(),
    repo = repo,
    costUsd = costMicros?.let(::usd),
    gateId = gateId,
    sessionId = sessionId,
)

private fun ActivityItem.summary(): String = when (kind) {
    ActivityKind.SESSION_STARTED -> "$subject started a $actor session" + inRepo()
    ActivityKind.SESSION_ENDED -> "$subject's $actor session ended" + inRepo()
    ActivityKind.GATE_REQUESTED -> "$actor asked for a yes: $subject"
    ActivityKind.GATE_DECIDED -> "$actor ${if (outcome == "rejected") "said no to" else "said yes to"}: $subject"
    ActivityKind.WORKFLOW_CHANGED -> "$actor changed the workflow $subject"
}

private fun ActivityItem.inRepo(): String = if (repo == null) "" else " in $repo"
