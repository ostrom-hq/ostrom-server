// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.infrastructure

import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetLimits
import dev.ostrom.server.budgets.domain.BudgetSettings
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.domain.MemberBudget
import dev.ostrom.server.budgets.domain.SharedLimit
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
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
        // The two org-wide rows sort first, so that the limit only ever cuts member overrides.
        val rows = driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT scope, member_id, limit_micros, updated_at FROM budgets_limits WHERE org_id = :org_id " +
                    "ORDER BY scope = 'MEMBER', scope, member_id LIMIT :limit",
            ).bind("org_id", orgId).bind("limit", maxOverrides + 2),
        ).getOrThrow().map { row ->
            StoredLimit(
                scope = row.get("scope").asString(),
                memberId = row.get("member_id").asUuidOrNull(),
                limitMicros = row.get("limit_micros").asLong(),
                updatedAt = row.get("updated_at").asInstant(),
            )
        }
        return BudgetSettings(
            orgLimitMicros = rows.firstOrNull { it.scope == ORG }?.limitMicros,
            defaultMemberLimitMicros = rows.firstOrNull { it.scope == DEFAULT_MEMBER }?.limitMicros,
            overrides = rows
                .mapNotNull { row -> row.memberId?.let { MemberBudget(it, row.limitMicros, row.updatedAt) } }
                .take(maxOverrides),
        )
    }

    override suspend fun limits(orgId: Uuid, memberId: Uuid): BudgetLimits {
        val rows = driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT scope, limit_micros FROM budgets_limits WHERE org_id = :org_id " +
                    "AND (member_id IS NULL OR member_id = :member_id) LIMIT 3",
            ).bind("org_id", orgId).bind("member_id", memberId),
        ).getOrThrow().associate { it.get("scope").asString() to it.get("limit_micros").asLong() }
        return BudgetLimits(orgLimitMicros = rows[ORG], memberLimitMicros = rows[MEMBER] ?: rows[DEFAULT_MEMBER])
    }

    override suspend fun putOrgLimits(
        orgId: Uuid,
        orgLimitMicros: Long?,
        defaultMemberLimitMicros: Long?,
        at: Instant,
    ) {
        driver.inTenant(orgId) {
            putShared(orgId, ORG, orgLimitMicros, at)
            putShared(orgId, DEFAULT_MEMBER, defaultMemberLimitMicros, at)
        }
    }

    override suspend fun putSharedLimit(orgId: Uuid, limit: SharedLimit, limitMicros: Long?, at: Instant) {
        driver.inTenant(orgId) { putShared(orgId, limit.name, limitMicros, at) }
    }

    override suspend fun putMemberLimit(orgId: Uuid, memberId: Uuid, limitMicros: Long, at: Instant): MemberBudget {
        driver.executeInOrg(
            orgId,
            Statement.create(
                "INSERT INTO budgets_limits (org_id, member_id, scope, limit_micros, updated_at) " +
                    "VALUES (:org_id, :member_id, 'MEMBER', :limit, :at) " + UPSERT,
            ).bind("org_id", orgId).bind("member_id", memberId).bind("limit", limitMicros).bind("at", at),
        ).getOrThrow()
        return MemberBudget(memberId, limitMicros, at)
    }

    override suspend fun deleteMemberLimit(orgId: Uuid, memberId: Uuid): Boolean = driver.executeInOrg(
        orgId,
        Statement.create(
            "DELETE FROM budgets_limits WHERE org_id = :org_id AND scope = 'MEMBER' AND member_id = :member_id",
        )
            .bind("org_id", orgId).bind("member_id", memberId),
    ).getOrThrow() == 1L

    override suspend fun markAlerted(alert: BudgetAlert, at: Instant): Boolean {
        val statement = Statement.create(
            "INSERT INTO budgets_alerts " +
                "(org_id, member_id, scope, period_start, threshold, limit_micros, alerted_at) " +
                "VALUES (:org_id, :member_id, :scope, :period_start, :threshold, :limit, :at) " +
                "ON CONFLICT (org_id, scope, member_id, period_start, threshold, limit_micros) DO NOTHING",
        ).bindAlert(alert).bind("at", at)
        return driver.executeInOrg(alert.orgId, statement).getOrThrow() == 1L
    }

    override suspend fun unmarkAlerted(alert: BudgetAlert) {
        val statement = Statement.create(
            "DELETE FROM budgets_alerts WHERE org_id = :org_id AND scope = :scope " +
                "AND member_id IS NOT DISTINCT FROM :member_id AND period_start = :period_start " +
                "AND threshold = :threshold AND limit_micros = :limit",
        ).bindAlert(alert)
        driver.executeInOrg(alert.orgId, statement).getOrThrow()
    }

    private fun Statement.bindAlert(alert: BudgetAlert): Statement {
        bind("org_id", alert.orgId)
        bind("scope", alert.scope.name)
        bind("period_start", alert.periodStart)
        bind("threshold", alert.thresholdPercent)
        bind("limit", alert.limitMicros)
        val member = alert.memberId
        if (member == null) bindNull("member_id", Uuid::class) else bind("member_id", member)
        return this
    }

    private suspend fun QueryExecutor.putShared(orgId: Uuid, scope: String, limitMicros: Long?, at: Instant) {
        if (limitMicros == null) {
            execute(
                Statement.create(
                    "DELETE FROM budgets_limits WHERE org_id = :org_id AND scope = :scope AND member_id IS NULL",
                )
                    .bind("org_id", orgId).bind("scope", scope),
            ).getOrThrow()
            return
        }
        execute(
            Statement.create(
                "INSERT INTO budgets_limits (org_id, member_id, scope, limit_micros, updated_at) " +
                    "VALUES (:org_id, NULL, :scope, :limit, :at) " + UPSERT,
            ).bind("org_id", orgId).bind("scope", scope).bind("limit", limitMicros).bind("at", at),
        ).getOrThrow()
    }

    private class StoredLimit(val scope: String, val memberId: Uuid?, val limitMicros: Long, val updatedAt: Instant)

    private companion object {
        const val UPSERT = "ON CONFLICT (org_id, scope, member_id) DO UPDATE " +
            "SET limit_micros = EXCLUDED.limit_micros, updated_at = EXCLUDED.updated_at"
        const val ORG = "ORG"
        const val DEFAULT_MEMBER = "DEFAULT_MEMBER"
        const val MEMBER = "MEMBER"
    }
}
