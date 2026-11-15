// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [BudgetsMigrations], as Exposed reads and writes them: the migrations create the schema, these objects
// only name its columns. Tenant tables: read and written in `JdbcDatabase.inOrg`, each query filtered on `org_id` too.

/**
 * The limits: one row per subject (`scope`, with its member or its workflow) and window (`period`), with its action.
 * Unique by `budgets_limits_scope`, nulls not distinct.
 */
internal object BudgetsLimits : Table("budgets_limits") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id").nullable()
    val workflowId = text("workflow_id").nullable()
    val scope = text("scope")
    val period = text("period")
    val action = text("action")
    val limitMicros = long("limit_micros")
    val updatedAt = instant("updated_at")
}

/** The alerts sent, once per limit value, action, threshold and period (`budgets_alerts_once`, nulls not distinct). */
internal object BudgetsAlerts : Table("budgets_alerts") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id").nullable()
    val workflowId = text("workflow_id").nullable()
    val scope = text("scope")
    val period = text("period")
    val action = text("action")
    val periodStart = instant("period_start")
    val threshold = integer("threshold")
    val limitMicros = long("limit_micros")
    val alertedAt = instant("alerted_at")
}

/** A member's temporary daily budgets, kept after they end. */
internal object BudgetsOverrides : Table("budgets_overrides") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val micros = long("micros")
    val createdBy = uuid("created_by")
    val reason = text("reason")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val replacesId = uuid("replaces_id").nullable()
    val revokedAt = instant("revoked_at").nullable()
    val revokedBy = uuid("revoked_by").nullable()
    val revocationReason = text("revocation_reason").nullable()

    override val primaryKey = PrimaryKey(id)
}
