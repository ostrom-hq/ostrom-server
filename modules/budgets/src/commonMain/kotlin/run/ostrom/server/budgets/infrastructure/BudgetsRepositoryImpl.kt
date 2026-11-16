// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetAlert
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetLimits
import run.ostrom.server.budgets.domain.BudgetRules
import run.ostrom.server.budgets.domain.BudgetSettings
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.domain.ExceededBudget
import run.ostrom.server.budgets.domain.MemberBudget
import run.ostrom.server.budgets.domain.WorkflowBudget
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.kernel.database.present
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [BudgetsRepository] on `budgets_limits` and `budgets_alerts` (tables of [BudgetsMigrations]): every statement filters
 * on its organization and runs in it (`inOrg`), under row-level security.
 *
 * @param database The serving role's pool.
 */
class BudgetsRepositoryImpl(private val database: JdbcDatabase) : BudgetsRepository {
    override suspend fun settings(orgId: Uuid, maxOverrides: Int): BudgetSettings = database.inOrg(orgId) {
        // The org-wide rows (two windows each) sort first, so that the limit only ever cuts member overrides; it
        // reads one row more than every window of the members kept, so that a member is never cut between windows.
        val rows = settingsRead(orgId, maxOverrides * WINDOWS + SHARED_ROWS).map { it.toStored() }
        // The workflows' rows apart, so that they never take the members' places, and whole workflows only.
        val workflows = workflowLimitsRead(orgId, BudgetRules.MAX_WORKFLOW_BUDGETS).map { row ->
            val stored = row.toStored()
            WorkflowBudget(row.present(BudgetsLimits.workflowId, BudgetsLimits.scope), stored.limit, stored.updatedAt)
        }
        val kept = rows.mapNotNull { it.memberId }.distinct().take(maxOverrides).toSet()
        BudgetSettings(
            org = rows.filter { it.scope == ORG }.map { it.limit },
            defaultMember = rows.filter { it.scope == DEFAULT_MEMBER }.map { it.limit },
            overrides = rows.mapNotNull { row ->
                val limit = row.limit
                row.memberId?.takeIf { it in kept }
                    ?.let { MemberBudget(it, limit.micros, row.updatedAt, limit.window, limit.action) }
            },
            workflows = workflows,
        )
    }

    override suspend fun limits(orgId: Uuid, memberId: Uuid, workflowId: String?): BudgetLimits {
        // A workflow's rows have no member: only the call's workflow's are read, none without one.
        val rows = database.inOrg(orgId) { limitsRead(orgId, memberId, workflowId).map { it.toStored() } }
        fun of(scope: String) = rows.filter { it.scope == scope }.map { it.limit }
        // A member's own limits replace the default member limits whole.
        return BudgetLimits(org = of(ORG), member = of(MEMBER).ifEmpty { of(DEFAULT_MEMBER) }, workflow = of(WORKFLOW))
    }

    override suspend fun replaceLimits(orgId: Uuid, target: BudgetTarget, limits: List<BudgetLimit>, at: Instant) {
        val (scope, memberId, workflowId) = when (target) {
            BudgetTarget.Org -> Triple(ORG, null, null)
            BudgetTarget.DefaultMember -> Triple(DEFAULT_MEMBER, null, null)
            is BudgetTarget.Member -> Triple(MEMBER, target.memberId, null)
            is BudgetTarget.Workflow -> Triple(WORKFLOW, null, target.workflowId)
        }
        database.inOrg(orgId) {
            // One write of a subject at a time: without it, a second write's DELETE skips the rows the first one
            // inserts meanwhile, and the subject keeps a mix of both writes' windows and actions.
            lockKeyUntilCommit(subjectKey(orgId, scope, memberId, workflowId))
            val l = BudgetsLimits
            l.deleteWhere {
                (l.orgId eq orgId) and (l.scope eq scope) and l.memberId.sameAs(memberId) and
                    l.workflowId.sameAs(workflowId)
            }
            for (limit in limits) {
                // One row per subject and window, and the action with it: a monthly write that stops sets it back.
                l.upsert(
                    l.orgId, l.scope, l.memberId, l.workflowId, l.period,
                    onUpdate = {
                        it[l.limitMicros] = insertValue(l.limitMicros)
                        it[l.action] = insertValue(l.action)
                        it[l.updatedAt] = insertValue(l.updatedAt)
                    },
                ) {
                    it[this.orgId] = orgId
                    it[this.scope] = scope
                    it[this.memberId] = memberId
                    it[this.workflowId] = workflowId
                    it[period] = limit.window.name
                    it[action] = limit.action.name
                    it[limitMicros] = limit.micros
                    it[updatedAt] = at
                }
            }
        }
    }

    override suspend fun removeWorkflow(orgId: Uuid, workflowId: String) {
        database.inOrg(orgId) {
            // Under the subject's lock, so that a budget set at the same moment is not left behind.
            lockKeyUntilCommit(subjectKey(orgId, WORKFLOW, null, workflowId))
            val l = BudgetsLimits
            l.deleteWhere { (l.orgId eq orgId) and (l.scope eq WORKFLOW) and (l.workflowId eq workflowId) }
            val a = BudgetsAlerts
            a.deleteWhere { (a.orgId eq orgId) and (a.scope eq WORKFLOW) and (a.workflowId eq workflowId) }
        }
    }

    override suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean = database.inOrg(alert.orgId) {
        BudgetsAlerts.insertIgnore {
            it.alert(alert)
            it[alertedAt] = at
        }.insertedCount == 1
    }

    override suspend fun unmarkAlerted(alert: BudgetAlert) {
        database.inOrg(alert.orgId) {
            val a = BudgetsAlerts
            a.deleteWhere {
                (a.orgId eq alert.orgId) and (a.scope eq alert.scope.name) and a.memberId.sameAs(alert.memberId) and
                    a.workflowId.sameAs(alert.workflowId) and (a.period eq alert.window.name) and
                    (a.action eq alert.action.name) and (a.periodStart eq alert.periodStart) and
                    (a.threshold eq alert.thresholdPercent) and (a.limitMicros eq alert.limitMicros)
            }
        }
    }

    override suspend fun exceeded(
        orgId: Uuid,
        memberId: Uuid?,
        day: Instant,
        month: Instant,
        max: Int,
    ): List<ExceededBudget> = database.inOrg(orgId) { exceededRead(orgId, memberId, day, month, max).rows() }
}

private fun UpdateBuilder<*>.alert(alert: BudgetAlert) {
    val a = BudgetsAlerts
    this[a.orgId] = alert.orgId
    this[a.scope] = alert.scope.name
    this[a.memberId] = alert.memberId
    this[a.workflowId] = alert.workflowId
    this[a.period] = alert.window.name
    this[a.action] = alert.action.name
    this[a.periodStart] = alert.periodStart
    this[a.threshold] = alert.thresholdPercent
    this[a.limitMicros] = alert.limitMicros
}

private fun ResultRow.toStored(): StoredLimit {
    val l = BudgetsLimits
    return StoredLimit(
        scope = this[l.scope],
        memberId = this[l.memberId],
        limit = BudgetLimit(
            micros = this[l.limitMicros],
            window = BudgetWindow.valueOf(this[l.period]),
            action = BudgetAction.valueOf(this[l.action]),
        ),
        updatedAt = this[l.updatedAt],
    )
}

private class StoredLimit(
    val scope: String,
    val memberId: Uuid?,
    val limit: BudgetLimit,
    val updatedAt: Instant,
)

/** The key of one subject's advisory lock, as the former repository took it (`hashtextextended`). */
private fun subjectKey(orgId: Uuid, scope: String, memberId: Uuid?, workflowId: String?): String =
    "budgets_limits:$orgId:$scope:${memberId ?: ""}:${workflowId ?: ""}"

/** The org's and the default member's rows: two windows each. */
private const val SHARED_ROWS = 4

/** The windows a subject may have a limit in: a day and a month. */
private const val WINDOWS = 2
