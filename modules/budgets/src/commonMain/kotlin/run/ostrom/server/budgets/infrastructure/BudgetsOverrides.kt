// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The tables of [BudgetsMigrations] read through Exposed (the others are still read by sqlx4k). The migrations create
 * the schema; these objects only name its columns. Tenant tables: read and written in `JdbcDatabase.inOrg`, each query
 * filtered on `org_id` too.
 */
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
