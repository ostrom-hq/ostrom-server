// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/** `budgets_automatic_daily` (version 6): one row an org, the automatic daily budget the pool size job stored. */
internal object BudgetsAutomaticDaily : Table("budgets_automatic_daily") {
    val orgId = uuid("org_id")
    val micros = long("micros")
    val computedAt = instant("computed_at")

    override val primaryKey = PrimaryKey(orgId)
}
