// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The sharing tables Exposed reads and writes, as [SharingMigrations] creates them: the migrations are the schema's
 * only source (no `SchemaUtils`).
 */
internal object SharingAccounts : Table("sharing_accounts") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val provider = text("provider")
    val label = text("label")
    val email = text("email").nullable()
    val providerAccountId = text("provider_account_id")
    val ownerMemberId = uuid("owner_member_id").nullable()
    val priority = integer("priority")
    val status = text("status")
    val maxConcurrent = integer("max_concurrent")
    val utilizationCeiling = double("utilization_ceiling")
    val monthlyBudgetMicros = long("monthly_budget_micros").nullable()
    val allowedMembers = text("allowed_members").nullable()
    val cooldownUntil = instant("cooldown_until").nullable()
    val envelope = text("envelope")
    val createdAt = instant("created_at")

    // Version 3.
    val version = long("version")

    // Version 4.
    val dailyBudgetMicros = long("daily_budget_micros").nullable()

    // Version 7: the refresh intent.
    val refreshPending = bool("refresh_pending")
    val refreshStartedAt = instant("refresh_started_at").nullable()
    val credentialVersion = long("credential_version")

    // Version 9.
    val ownership = text("ownership")

    // Version 13: TeamCode's account pool.
    val poolId = text("pool_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The last reading of each window of an account (version 1). */
internal object SharingWindows : Table("sharing_windows") {
    val orgId = uuid("org_id")
    val accountId = uuid("account_id")
    val name = text("name")
    val utilization = double("utilization")
    val resetsAt = instant("resets_at").nullable()
    val observedAt = instant("observed_at")

    override val primaryKey = PrimaryKey(accountId, name)
}

/** The organization's groups (version 5). */
internal object SharingGroups : Table("sharing_groups") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val name = text("name")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** The members of the groups (version 5). */
internal object SharingGroupMembers : Table("sharing_group_members") {
    val orgId = uuid("org_id")
    val groupId = uuid("group_id")
    val memberId = uuid("member_id")
    val pausedUntil = instant("paused_until").nullable()

    override val primaryKey = PrimaryKey(groupId, memberId)
}

/** The accounts lent to the groups (version 5). */
internal object SharingGroupAccounts : Table("sharing_group_accounts") {
    val orgId = uuid("org_id")
    val groupId = uuid("group_id")
    val accountId = uuid("account_id")

    override val primaryKey = PrimaryKey(groupId, accountId)
}

/** The requests in flight on each account, each expiring after its lease (version 8). */
internal object SharingSlots : Table("sharing_slots") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val accountId = uuid("account_id")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(id)
}

/** The account each session used last, by the session id's digest (version 8). */
internal object SharingAffinities : Table("sharing_affinities") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val sessionHash = text("session_hash")
    val accountId = uuid("account_id")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(orgId, memberId, sessionHash)
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
