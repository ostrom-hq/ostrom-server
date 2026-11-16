// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.budgets.domain.AutomaticPoolBudgetsRepository
import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetScope
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.ExceededBudget
import run.ostrom.server.kernel.database.Read
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IsNotDistinctFromOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The reads of the budgets, explained by BudgetsQueryPlansTest: each an `internal` query the repository runs.

/** The rows that apply to one member's call: the org's, the default's, their own, its workflow's; two each. */
internal const val LIMIT_ROWS: Int = 8

/** The threshold of the alert a reached limit marks, in percent. */
private const val REACHED = 100

internal const val ORG = "ORG"
internal const val DEFAULT_MEMBER = "DEFAULT_MEMBER"
internal const val MEMBER = "MEMBER"
internal const val WORKFLOW = "WORKFLOW"

private val L = BudgetsLimits

/** The columns of a limit row. */
internal val limitColumns = listOf(L.scope, L.memberId, L.workflowId, L.period, L.action, L.limitMicros, L.updatedAt)

/** `column = value`, or `column IS NULL` when [value] is null: the rows the former `IS NOT DISTINCT FROM :x` read. */
internal fun <T : Any> Column<T?>.sameAs(value: T?): Op<Boolean> = if (value == null) isNull() else eq(value)

/**
 * The rows of [orgId] but its workflows', the org-wide rows first (two windows each), then the members' by member, at
 * most [limit]: the limit only ever cuts member overrides.
 */
internal fun settingsRead(orgId: Uuid, limit: Int): Query = L.select(limitColumns)
    .where { (L.orgId eq orgId) and (L.scope neq WORKFLOW) }
    .orderBy(
        (L.scope eq MEMBER) to SortOrder.ASC,
        L.scope to SortOrder.ASC,
        L.memberId to SortOrder.ASC,
        L.period to SortOrder.DESC,
    )
    .limit(limit)

/**
 * Every row of the first [limit] workflows by id that have a budget in [orgId]: a workflow is listed whole, both
 * windows, or not at all.
 */
internal fun workflowLimitsRead(orgId: Uuid, limit: Int): Query {
    val first = L.select(L.workflowId)
        .where { (L.orgId eq orgId) and (L.scope eq WORKFLOW) }
        .withDistinct()
        .orderBy(L.workflowId to SortOrder.ASC)
        .limit(limit)
    return L.select(limitColumns)
        .where { (L.orgId eq orgId) and (L.scope eq WORKFLOW) and (L.workflowId inSubQuery first) }
        .orderBy(L.workflowId to SortOrder.ASC, L.period to SortOrder.DESC)
}

/**
 * The rows that can apply to one member's call: the org's, the default member's, their own, and its workflow's (none
 * without one); [LIMIT_ROWS] at most.
 */
internal fun limitsRead(orgId: Uuid, memberId: Uuid, workflowId: String?): Query {
    val none = L.workflowId.isNull()
    val workflow = if (workflowId == null) none else none or (L.workflowId eq workflowId)
    return L.select(limitColumns)
        .where { (L.orgId eq orgId) and (L.memberId.isNull() or (L.memberId eq memberId)) and workflow }
        .orderBy(L.period to SortOrder.DESC)
        .limit(LIMIT_ROWS)
}

/**
 * The limits reached in their current period ([day] for a daily limit, [month] for a monthly one), from the alerts
 * the guard marked at 100 %, kept only while a limit of the same amount and action still applies: the subject's own
 * row, or for a member without limits of their own, the default member's. One member's only when [memberId] is set
 * (a workflow's limit is no member's); at most [limit], oldest first.
 */
internal fun exceededRead(
    orgId: Uuid,
    memberId: Uuid?,
    day: Instant,
    month: Instant,
    limit: Int,
): Read<ExceededBudget> {
    val alerts = BudgetsAlerts
    val a = alerts.alias("a")
    val l = L.alias("l")
    val o = L.alias("o")
    val own = notExists(
        o.select(intLiteral(1)).where {
            (o[L.orgId] eq a[alerts.orgId]) and (o[L.scope] eq MEMBER) and (o[L.memberId] eq a[alerts.memberId])
        },
    )
    val sameSubject = (l[L.scope] eq a[alerts.scope]) and
        IsNotDistinctFromOp(l[L.memberId], a[alerts.memberId]) and
        IsNotDistinctFromOp(l[L.workflowId], a[alerts.workflowId])
    val byDefault = (a[alerts.scope] eq MEMBER) and (l[L.scope] eq DEFAULT_MEMBER) and own
    val applies = exists(
        l.select(intLiteral(1)).where {
            (l[L.orgId] eq a[alerts.orgId]) and (l[L.period] eq a[alerts.period]) and
                (l[L.action] eq a[alerts.action]) and (l[L.limitMicros] eq a[alerts.limitMicros]) and
                (sameSubject or byDefault)
        },
    )
    // The automatic daily limit's alerts carry a mark instead of an amount (it moves at every pass): the decorator
    // that applies the automatic value keeps them while it is in force.
    val automatic = (a[alerts.scope] eq ORG) and (a[alerts.period] eq "DAY") and
        (a[alerts.limitMicros] eq AutomaticPoolBudgetsRepository.AUTOMATIC_MARK)
    val current = ((a[alerts.period] eq "DAY") and (a[alerts.periodStart] eq day)) or
        ((a[alerts.period] eq "MONTH") and (a[alerts.periodStart] eq month))
    val whose = memberId?.let { a[alerts.memberId] eq it }
    val query = a.select(
        a[alerts.scope], a[alerts.memberId], a[alerts.workflowId], a[alerts.period], a[alerts.action],
        a[alerts.limitMicros], a[alerts.alertedAt],
    )
        .where {
            ((a[alerts.orgId] eq orgId) and (a[alerts.threshold] eq REACHED) and current andIfNotNull whose) and
                (applies or automatic)
        }
        .orderBy(
            a[alerts.alertedAt] to SortOrder.ASC,
            a[alerts.memberId] to SortOrder.ASC_NULLS_FIRST,
            a[alerts.period] to SortOrder.DESC,
        )
        .limit(limit)
    return Read(query) { row ->
        ExceededBudget(
            scope = BudgetScope.valueOf(row[a[alerts.scope]]),
            memberId = row[a[alerts.memberId]],
            limit = BudgetLimit(
                micros = row[a[alerts.limitMicros]],
                window = BudgetWindow.valueOf(row[a[alerts.period]]),
                action = BudgetAction.valueOf(row[a[alerts.action]]),
            ),
            since = row[a[alerts.alertedAt]],
            workflowId = row[a[alerts.workflowId]],
        )
    }
}
