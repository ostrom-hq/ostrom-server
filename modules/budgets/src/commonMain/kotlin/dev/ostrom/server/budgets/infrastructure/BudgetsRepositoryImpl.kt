// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.infrastructure

import dev.ostrom.server.budgets.domain.BudgetAction
import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetLimits
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.BudgetSettings
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.domain.ExceededBudget
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [BudgetsRepository] on PostgreSQL (tables of [BudgetsMigrations]).
 *
 * @param driver The database.
 */
class BudgetsRepositoryImpl(private val driver: Driver) : BudgetsRepository {
    override suspend fun settings(orgId: Uuid, maxOverrides: Int): BudgetSettings {
        // The org-wide rows (two windows each) sort first, so that the limit only ever cuts member overrides; it
        // reads one row more than every window of the members kept, so that a member is never cut between windows.
        val rows = driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT scope, member_id, period, action, limit_micros, updated_at FROM budgets_limits " +
                    "WHERE org_id = :org_id ORDER BY scope = 'MEMBER', scope, member_id, period DESC LIMIT :limit",
            ).bind("org_id", orgId).bind("limit", maxOverrides * WINDOWS + SHARED_ROWS),
        ).getOrThrow().map { it.toStored() }
        val kept = rows.mapNotNull { it.memberId }.distinct().take(maxOverrides).toSet()
        return BudgetSettings(
            org = rows.filter { it.scope == ORG }.map { it.limit },
            defaultMember = rows.filter { it.scope == DEFAULT_MEMBER }.map { it.limit },
            overrides = rows.mapNotNull { row ->
                val limit = row.limit
                row.memberId?.takeIf { it in kept }
                    ?.let { MemberBudget(it, limit.micros, row.updatedAt, limit.window, limit.action) }
            },
        )
    }

    override suspend fun limits(orgId: Uuid, memberId: Uuid): BudgetLimits {
        val rows = driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT scope, member_id, period, action, limit_micros, updated_at FROM budgets_limits " +
                    "WHERE org_id = :org_id AND (member_id IS NULL OR member_id = :member_id) " +
                    "ORDER BY period DESC LIMIT $LIMIT_ROWS",
            ).bind("org_id", orgId).bind("member_id", memberId),
        ).getOrThrow().map { it.toStored() }
        fun of(scope: String) = rows.filter { it.scope == scope }.map { it.limit }
        // A member's own limits replace the default member limits whole.
        return BudgetLimits(org = of(ORG), member = of(MEMBER).ifEmpty { of(DEFAULT_MEMBER) })
    }

    override suspend fun replaceLimits(orgId: Uuid, target: BudgetTarget, limits: List<BudgetLimit>, at: Instant) {
        val (scope, memberId) = when (target) {
            BudgetTarget.Org -> ORG to null
            BudgetTarget.DefaultMember -> DEFAULT_MEMBER to null
            is BudgetTarget.Member -> MEMBER to target.memberId
        }
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "DELETE FROM budgets_limits WHERE org_id = :org_id AND scope = :scope " +
                        "AND member_id IS NOT DISTINCT FROM :member_id",
                ).bind("org_id", orgId).bind("scope", scope).bindMember(memberId),
            ).getOrThrow()
            for (limit in limits) {
                execute(
                    Statement.create(
                        "INSERT INTO budgets_limits (org_id, member_id, scope, period, action, limit_micros, " +
                            "updated_at) VALUES (:org_id, :member_id, :scope, :period, :action, :limit, :at) " + UPSERT,
                    ).bind("org_id", orgId).bind("scope", scope).bindMember(memberId)
                        .bind("period", limit.window.name).bind("action", limit.action.name)
                        .bind("limit", limit.micros).bind("at", at),
                ).getOrThrow()
            }
        }
    }

    override suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean {
        val statement = Statement.create(
            "INSERT INTO budgets_alerts " +
                "(org_id, member_id, scope, period, action, period_start, threshold, limit_micros, alerted_at) " +
                "VALUES (:org_id, :member_id, :scope, :period, :action, :period_start, :threshold, :limit, :at) " +
                "ON CONFLICT (org_id, scope, member_id, period, action, period_start, threshold, limit_micros) " +
                "DO NOTHING",
        ).bindAlert(alert).bind("at", at)
        return driver.executeInOrg(alert.orgId, statement).getOrThrow() == 1L
    }

    override suspend fun unmarkAlerted(alert: BudgetAlert) {
        val statement = Statement.create(
            "DELETE FROM budgets_alerts WHERE org_id = :org_id AND scope = :scope " +
                "AND member_id IS NOT DISTINCT FROM :member_id AND period = :period AND action = :action " +
                "AND period_start = :period_start AND threshold = :threshold AND limit_micros = :limit",
        ).bindAlert(alert)
        driver.executeInOrg(alert.orgId, statement).getOrThrow()
    }

    override suspend fun exceeded(
        orgId: Uuid,
        memberId: Uuid?,
        day: Instant,
        month: Instant,
        max: Int,
    ): List<ExceededBudget> {
        val statement = Statement.create(BudgetsQueries.exceeded(member = memberId != null))
            .bind("org_id", orgId).bind("day", day).bind("month", month).bind("limit", max)
        if (memberId != null) statement.bind("member_id", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { row ->
            ExceededBudget(
                scope = BudgetScope.valueOf(row.get("scope").asString()),
                memberId = row.get("member_id").asUuidOrNull(),
                limit = BudgetLimit(
                    micros = row.get("limit_micros").asLong(),
                    window = BudgetWindow.valueOf(row.get("period").asString()),
                    action = BudgetAction.valueOf(row.get("action").asString()),
                ),
                since = row.get("alerted_at").asInstant(),
            )
        }
    }
}

private fun Statement.bindAlert(alert: BudgetAlert): Statement {
    bind("org_id", alert.orgId)
    bind("scope", alert.scope.name)
    bind("period", alert.window.name)
    bind("action", alert.action.name)
    bind("period_start", alert.periodStart)
    bind("threshold", alert.thresholdPercent)
    bind("limit", alert.limitMicros)
    return bindMember(alert.memberId)
}

private fun Statement.bindMember(memberId: Uuid?): Statement =
    if (memberId == null) bindNull("member_id", Uuid::class) else bind("member_id", memberId)

private fun ResultSet.Row.toStored() = StoredLimit(
    scope = get("scope").asString(),
    memberId = get("member_id").asUuidOrNull(),
    limit = BudgetLimit(
        micros = get("limit_micros").asLong(),
        window = BudgetWindow.valueOf(get("period").asString()),
        action = BudgetAction.valueOf(get("action").asString()),
    ),
    updatedAt = get("updated_at").asInstant(),
)

private class StoredLimit(val scope: String, val memberId: Uuid?, val limit: BudgetLimit, val updatedAt: Instant)

/** One row per subject and window, and the action with it: a monthly write that stops sets it back. */
private const val UPSERT = "ON CONFLICT (org_id, scope, member_id, period) DO UPDATE " +
    "SET limit_micros = EXCLUDED.limit_micros, action = EXCLUDED.action, updated_at = EXCLUDED.updated_at"
private const val ORG = "ORG"
private const val DEFAULT_MEMBER = "DEFAULT_MEMBER"
private const val MEMBER = "MEMBER"

/** The org's and the default member's rows: two windows each. */
private const val SHARED_ROWS = 4

/** The windows a subject may have a limit in: a day and a month. */
private const val WINDOWS = 2

/** The rows that can apply to one member: the org's, the default's and their own, two windows each. */
private const val LIMIT_ROWS = 6
