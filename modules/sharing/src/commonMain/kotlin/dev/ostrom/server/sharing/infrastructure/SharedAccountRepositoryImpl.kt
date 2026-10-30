// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SharedAccountRepository] on PostgreSQL ([SharingMigrations]). Every query runs confined to the org it names
 * (`fetchInOrg`, `executeInOrg`): row-level security hides and refuses the rows of every other org (S2-01, S2-21).
 *
 * @param driver The database.
 */
class SharedAccountRepositoryImpl(private val driver: Driver) : SharedAccountRepository {
    override suspend fun upsert(account: SharedAccount, envelope: String): SharedAccount = driver.fetchInOrg(
        account.orgId,
        Statement.create(UPSERT)
            .bind("id", account.id)
            .bind("org_id", account.orgId)
            .bind("provider", account.provider.name)
            .bind("label", account.label)
            .bindNullable("email", account.email, String::class)
            .bind("provider_account_id", account.providerAccountId)
            .bindNullable("owner", account.ownerMemberId, Uuid::class)
            .bind("priority", account.priority)
            .bind("status", account.status.name)
            .bindLimits(account.limits)
            .bind("envelope", envelope)
            .bind("created_at", account.createdAt),
    ).getOrThrow().single().toAccount()

    override suspend fun get(orgId: Uuid, id: Uuid): SharedAccount? = driver.fetchInOrg(
        orgId,
        Statement.create("SELECT $ACCOUNT_COLUMNS FROM sharing_accounts WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow().firstOrNull()?.toAccount()

    override suspend fun list(orgId: Uuid): List<SharedAccount> = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT $ACCOUNT_COLUMNS FROM sharing_accounts WHERE org_id = :org_id " +
                "ORDER BY priority DESC, label, id LIMIT 1000",
        ).bind("org_id", orgId),
    ).getOrThrow().map { it.toAccount() }

    override suspend fun ready(orgId: Uuid, provider: AccountProvider): List<SharedAccount> = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT $ACCOUNT_COLUMNS FROM sharing_accounts " +
                "WHERE org_id = :org_id AND provider = :provider AND status = 'READY' " +
                "ORDER BY priority DESC, id LIMIT 1000",
        ).bind("org_id", orgId).bind("provider", provider.name),
    ).getOrThrow().map { it.toAccount() }

    override suspend fun update(account: SharedAccount): SharedAccount? = driver.fetchInOrg(
        account.orgId,
        Statement.create(
            "UPDATE sharing_accounts SET label = :label, priority = :priority, status = :status, " +
                "max_concurrent = :max_concurrent, utilization_ceiling = :ceiling, " +
                "monthly_budget_micros = :budget, allowed_members = :allowed " +
                "WHERE org_id = :org_id AND id = :id RETURNING $ACCOUNT_COLUMNS",
        )
            .bind("org_id", account.orgId)
            .bind("id", account.id)
            .bind("label", account.label)
            .bind("priority", account.priority)
            .bind("status", account.status.name)
            .bindLimits(account.limits),
    ).getOrThrow().firstOrNull()?.toAccount()

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.executeInOrg(
        orgId,
        Statement.create("DELETE FROM sharing_accounts WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow() == 1L

    override suspend fun envelope(orgId: Uuid, id: Uuid): String? = driver.fetchInOrg(
        orgId,
        Statement.create("SELECT envelope FROM sharing_accounts WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow().firstOrNull()?.let { it.get("envelope").asString() }

    override suspend fun putEnvelope(orgId: Uuid, id: Uuid, envelope: String) {
        driver.setColumn(orgId, id, "envelope = :value") { bind("value", envelope) }
    }

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        driver.setColumn(orgId, id, "status = :value") { bind("value", status.name) }
    }

    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant) {
        driver.setColumn(orgId, id, "cooldown_until = :value") { bind("value", until) }
    }

    private companion object {
        const val UPSERT =
            "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, " +
                "owner_member_id, priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, " +
                "allowed_members, cooldown_until, envelope, created_at) " +
                "VALUES (:id, :org_id, :provider, :label, :email, :provider_account_id, :owner, :priority, " +
                ":status, :max_concurrent, :ceiling, :budget, :allowed, NULL, :envelope, :created_at) " +
                "ON CONFLICT (org_id, provider, provider_account_id) DO UPDATE SET label = EXCLUDED.label, " +
                "email = EXCLUDED.email, status = EXCLUDED.status, max_concurrent = EXCLUDED.max_concurrent, " +
                "utilization_ceiling = EXCLUDED.utilization_ceiling, " +
                "monthly_budget_micros = EXCLUDED.monthly_budget_micros, " +
                "allowed_members = EXCLUDED.allowed_members, cooldown_until = NULL, envelope = EXCLUDED.envelope " +
                "RETURNING $ACCOUNT_COLUMNS"
    }
}
