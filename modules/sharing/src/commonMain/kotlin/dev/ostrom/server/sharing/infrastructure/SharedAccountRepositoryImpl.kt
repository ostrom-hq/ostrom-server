// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.sharing.domain.AccountChangedException
import dev.ostrom.server.sharing.domain.AccountOwnedByAnotherMemberException
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
    override suspend fun upsert(account: SharedAccount, envelope: String, expectedVersion: Long?): SharedAccount {
        // At a version above 0 the row must still exist: a plain update, so a row deleted meanwhile is never inserted
        // again.
        val statement = if (expectedVersion != null && expectedVersion > 0) {
            Statement.create(SHARE_AGAIN).bind("expected", expectedVersion)
        } else {
            Statement.create(UPSERT)
                .bind("priority", account.priority)
                .bindLimits(account.limits)
                .bind("created_at", account.createdAt)
                .bindNullable("expected", expectedVersion, Long::class)
        }
        val row = driver.fetchInOrg(
            account.orgId,
            statement
                .bind("id", account.id)
                .bind("org_id", account.orgId)
                .bind("provider", account.provider.name)
                .bind("label", account.label)
                .bindNullable("email", account.email, String::class)
                .bind("provider_account_id", account.providerAccountId)
                .bindNullable("owner", account.ownerMemberId, Uuid::class)
                .bind("status", account.status.name)
                .bind("envelope", envelope),
        ).getOrThrow().singleOrNull()
            ?: throw if (expectedVersion == null) AccountOwnedByAnotherMemberException() else AccountChangedException()
        return row.toAccount()
    }

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

    override suspend fun update(account: SharedAccount, expectedVersion: Long?): SharedAccount? = driver.fetchInOrg(
        account.orgId,
        Statement.create(
            "UPDATE sharing_accounts SET label = :label, priority = :priority, " +
                "status = CASE WHEN status IN ($AWAITING_SIGN_IN) THEN status ELSE :status END, " +
                "max_concurrent = :max_concurrent, utilization_ceiling = :ceiling, " +
                "monthly_budget_micros = :budget, allowed_members = :allowed, version = version + 1 " +
                "WHERE org_id = :org_id AND id = :id AND version = COALESCE(:expected, version) " +
                "RETURNING $ACCOUNT_COLUMNS",
        )
            .bind("org_id", account.orgId)
            .bind("id", account.id)
            .bind("label", account.label)
            .bind("priority", account.priority)
            .bind("status", account.status.name)
            .bindLimits(account.limits)
            .bindNullable("expected", expectedVersion, Long::class),
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
        driver.setColumn(orgId, id, "envelope = :value", bump = false) { bind("value", envelope) }
    }

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        driver.setColumn(orgId, id, "status = :value", bump = true) { bind("value", status.name) }
    }

    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant) {
        driver.setColumn(orgId, id, "cooldown_until = :value", bump = false) { bind("value", until) }
    }

    private companion object {
        // The statuses only sharing again leaves (AccountStatus.awaitsSignIn), kept by a stale write.
        val AWAITING_SIGN_IN = AccountStatus.entries.filter { it.awaitsSignIn }.joinToString { "'${it.name}'" }

        // Sharing again keeps the limits. Two shares racing on a new account both insert: the second keeps the first
        // row's id, so its envelope (sealed for its own id) would not open; the first's stays. An account another
        // member owns is never taken over, nor a row past the expected version (no row comes back).
        const val SHARE_AGAIN =
            "UPDATE sharing_accounts SET label = :label, email = :email, status = :status, cooldown_until = NULL, " +
                "envelope = :envelope, version = version + 1 " +
                "WHERE org_id = :org_id AND provider = :provider AND provider_account_id = :provider_account_id " +
                "AND id = :id AND version = :expected " +
                "AND (owner_member_id IS NULL OR owner_member_id = :owner) " +
                "RETURNING $ACCOUNT_COLUMNS"
        const val UPSERT =
            "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, " +
                "owner_member_id, priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, " +
                "allowed_members, cooldown_until, envelope, created_at, version) " +
                "VALUES (:id, :org_id, :provider, :label, :email, :provider_account_id, :owner, :priority, " +
                ":status, :max_concurrent, :ceiling, :budget, :allowed, NULL, :envelope, :created_at, 1) " +
                "ON CONFLICT (org_id, provider, provider_account_id) DO UPDATE SET label = EXCLUDED.label, " +
                "email = EXCLUDED.email, status = EXCLUDED.status, cooldown_until = NULL, " +
                "envelope = CASE WHEN sharing_accounts.id = EXCLUDED.id THEN EXCLUDED.envelope " +
                "ELSE sharing_accounts.envelope END, version = sharing_accounts.version + 1 " +
                "WHERE (sharing_accounts.owner_member_id IS NULL " +
                "OR sharing_accounts.owner_member_id = EXCLUDED.owner_member_id) " +
                "AND sharing_accounts.version = COALESCE(:expected, sharing_accounts.version) " +
                "RETURNING $ACCOUNT_COLUMNS"
    }
}
