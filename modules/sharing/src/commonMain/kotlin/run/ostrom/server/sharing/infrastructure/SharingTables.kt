// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The sharing tables Exposed reads and writes, as [SharingMigrations] creates them: the migrations are the schema's
 * only source (no `SchemaUtils`). The other tables are still read through sqlx4k.
 */
internal object SharingAccounts : Table("sharing_accounts") {
    val id = uuid("id")
    val orgId = uuid("org_id")

    override val primaryKey = PrimaryKey(id)
}

/** The readings of the accounts' windows over time, one per minute and window (version 11). */
internal object SharingQuotaHistory : Table("sharing_quota_history") {
    val orgId = uuid("org_id")
    val accountId = uuid("account_id")
    val name = text("name")
    val utilization = double("utilization")
    val resetsAt = instant("resets_at").nullable()
    val observedAt = instant("observed_at")

    /** The reading's minute since the epoch: the history keeps the newest reading of each. */
    val sampleMinute = long("sample_minute")

    override val primaryKey = PrimaryKey(accountId, name, sampleMinute)
}
