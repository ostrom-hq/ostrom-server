// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.SpendLimits
import run.ostrom.server.usage.domain.SpendTotals
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The spend reads of the ledger (UsageRepositoryImpl), one function each so that UsageQueryPlansTest explains exactly
// what the repository sends. Every one filters on the org.

/**
 * What is recorded since [since] ([spender]'s), TeamCode #163: the spend of the whole UTC days from the first midnight
 * at or after [since] (`usage_spend_days`, at most 31 rows of each member and workflow for a month), plus the calls
 * before that midnight, none when [since] is one (the start of a month or a day).
 */
internal fun recorded(orgId: Uuid, since: Instant, spender: Spender): ExpressionWithColumnType<Long> {
    val first = FirstWholeDay(since)
    val d = UsageSpendDays
    val days = d.select(d.costMicros.sum())
        .where { (d.orgId eq orgId) and (d.day greaterEq first.day) andIfNotNull spender.ofDays() }
    val e = UsageEvents
    val calls = e.select(e.costMicros.sum())
        .where {
            (e.orgId eq orgId) and (e.at greaterEq since) and (e.at less first.at) andIfNotNull spender.ofEvents()
        }
    return sumOrZero(days) + sumOrZero(calls)
}

/**
 * The org's or a member's spend since [since] (the budgets, the gateway).
 *
 * @param memberId The member, or null for the whole org.
 */
internal fun spentRead(orgId: Uuid, memberId: Uuid?, since: Instant): Read<Long> =
    sumRead(recorded(orgId, since, Spender(memberId = memberId)))

/** A workflow's spend since [since] (its budgets, index `usage_spend_days_workflow`). */
internal fun workflowSpentRead(orgId: Uuid, workflowId: String, since: Instant): Read<Long> =
    sumRead(recorded(orgId, since, Spender(workflowId = workflowId)))

/** A pooled account's spend since [since] (the sharing module). */
internal fun accountSpentRead(orgId: Uuid, accountId: Uuid, since: Instant): Read<Long> {
    val e = UsageEvents
    val spent = coalesce(e.costMicros.sum(), longLiteral(0)).asBigint()
    val query = e.select(spent)
        .where { (e.orgId eq orgId) and (e.accountId eq accountId) and (e.at greaterEq since) }
    return Read(query) { it[spent] }
}

/**
 * What a launch spent in `[from, to)`, null for nothing priced. Half open, so that a call on the boundary of two
 * segments counts once; the window bounds the read to the org's index on `at`.
 */
internal fun launchSpentRead(orgId: Uuid, launchId: String, from: Instant, to: Instant): Read<Long?> {
    val e = UsageEvents
    val spent = e.costMicros.sum().asBigint()
    val query = e.select(spent).where {
        (e.orgId eq orgId) and (e.at greaterEq from) and (e.at less to) and (e.launchId eq launchId) and
            (e.costMicros greater 0L)
    }
    return Read(query) { it.getOrNull(spent) }
}

/** One sum, read on its own (`SELECT CAST(sum AS BIGINT)`, no table). */
private fun sumRead(sum: ExpressionWithColumnType<Long>): Read<Long> {
    val spent = sum.asBigint()
    return Read(Table.Dual.select(spent)) { it[spent] }
}

/**
 * What is spent since the month's start and since the day's, and held by the other calls now, for the org, the
 * member and the workflow, each read only when a limit needs it (0 otherwise). The holds are read for a side with a
 * monthly or a daily limit: a call in flight counts against both. An expired hold, the uncertain cost of a call never
 * recorded, is spent at the time it was held (TeamCode's « Failure behavior »). A workflow's sums are read only for a
 * call of a launch that plays it.
 */
internal fun holdTotalsRead(hold: SpendHold, limits: SpendLimits, window: HoldWindow): Read<SpendTotals> {
    val sums = HoldSums(hold, window)
    val daily = limits.daily
    val org = limits.orgMicros != null
    val member = limits.memberMicros != null
    val orgDay = daily?.orgMicros != null
    val memberDay = daily?.memberMicros != null
    val workflowId = hold.workflowId
    val workflow = workflowId != null && limits.workflowMicros != null
    val workflowDay = workflowId != null && daily?.workflowMicros != null
    // The day's start, read only with a daily limit.
    val daySince = if (daily == null) window.since else daily.since
    val mine = Spender(memberId = hold.memberId)
    val its = Spender(workflowId = workflowId)
    // A launch's budget applies only to a call of that launch; its sums are read only then.
    val launchId = hold.launchId
    val launchSums = if (launchId == null || limits.launchMicros == null) {
        List(LAUNCH_SUMS) { longLiteral(0) }
    } else {
        listOf(sums.launchSpent(launchId), sums.launchHeld(launchId))
    }
    val columns = listOf(
        sums.spent(org, window.since, Spender.ORG),
        sums.held(org || orgDay, Spender.ORG),
        sums.spent(member, window.since, mine),
        sums.held(member || memberDay, mine),
        sums.spent(orgDay, daySince, Spender.ORG),
        sums.spent(memberDay, daySince, mine),
        sums.spent(workflow, window.since, its),
        sums.held(workflow || workflowDay, its),
        sums.spent(workflowDay, daySince, its),
    )
    return Read(Table.Dual.select(columns)) { row ->
        val values = columns.map { row[it] }
        SpendTotals(
            orgSpent = values[0],
            orgHeld = values[1],
            memberSpent = values[2],
            memberHeld = values[3],
            orgDaySpent = values[4],
            memberDaySpent = values[5],
            workflowSpent = values[6],
            workflowHeld = values[7],
            workflowDaySpent = values[8],
            launchSpent = values[9],
            launchHeld = values[10],
        )
    }
}

/** The sums of [holdTotalsRead] for one call at the window's now. */
private class HoldSums(private val hold: SpendHold, private val window: HoldWindow) {
    /** What the other calls in flight hold now, or 0 unless [read]. */
    fun held(read: Boolean, spender: Spender): ExpressionWithColumnType<Long> {
        if (!read) return longLiteral(0)
        val h = UsageHolds
        val query = h.select(h.micros.sum()).where {
            (h.orgId eq hold.orgId) and (h.expiresAt greater window.now) and (h.callId neq hold.callId) andIfNotNull
                spender.ofHolds()
        }
        return sumOrZero(query).asBigint()
    }

    /** What is recorded since [since], plus what the calls never recorded held then, or 0 unless [read]. */
    fun spent(read: Boolean, since: Instant, spender: Spender): ExpressionWithColumnType<Long> {
        if (!read) return longLiteral(0)
        val h = UsageHolds
        val lost = h.select(h.micros.sum()).where {
            (h.orgId eq hold.orgId) and (h.heldAt greaterEq since) and (h.expiresAt lessEq window.now) andIfNotNull
                spender.ofHolds()
        }
        return (recorded(hold.orgId, since, spender) + sumOrZero(lost)).asBigint()
    }

    /**
     * What a launch spent over its life (at most [LAUNCH_LIFE_HOURS] hours): its recorded events, plus what its calls
     * never recorded held then.
     */
    fun launchSpent(launchId: String): ExpressionWithColumnType<Long> {
        val since = window.now - LAUNCH_LIFE_HOURS.hours
        val e = UsageEvents
        val recorded = e.select(e.costMicros.sum())
            .where { (e.orgId eq hold.orgId) and (e.launchId eq launchId) and (e.at greaterEq since) }
        val h = UsageHolds
        val lost = h.select(h.micros.sum()).where {
            (h.orgId eq hold.orgId) and (h.launchId eq launchId) and (h.heldAt greaterEq since) and
                (h.expiresAt lessEq window.now)
        }
        return (sumOrZero(recorded) + sumOrZero(lost)).asBigint()
    }

    /** What the launch's other calls in flight hold now. */
    fun launchHeld(launchId: String): ExpressionWithColumnType<Long> {
        val h = UsageHolds
        val query = h.select(h.micros.sum()).where {
            (h.orgId eq hold.orgId) and (h.expiresAt greater window.now) and (h.callId neq hold.callId) and
                (h.launchId eq launchId)
        }
        return sumOrZero(query).asBigint()
    }
}
