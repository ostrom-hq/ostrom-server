// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.sharing.domain.AccountChangedException
import run.ostrom.server.sharing.domain.AccountOwnedByAnotherMemberException
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshingException
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The statuses only sharing again leaves ([AccountStatus.awaitsSignIn]), kept by a stale write; a test holds them
 * equal.
 */
internal const val AWAITING_SIGN_IN: String = "'RECONNECT_REQUIRED', 'WRONG_ACCOUNT'"

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
                .bind("ownership", account.ownership.name)
                .bind("envelope", envelope),
        ).getOrThrow().singleOrNull()
            ?: throw when {
                refreshing(account) -> AccountRefreshingException()
                expectedVersion == null -> AccountOwnedByAnotherMemberException()
                else -> AccountChangedException()
            }
        return row.toAccount()
    }

    /** Whether the org's row of [account]'s provider account is being refreshed now. */
    private suspend fun refreshing(account: SharedAccount): Boolean = driver.fetchInOrg(
        account.orgId,
        Statement.create(
            "SELECT 1 FROM sharing_accounts WHERE org_id = :org_id AND provider = :provider " +
                "AND provider_account_id = :provider_account_id " +
                "AND (owner_member_id IS NULL OR owner_member_id = :owner) AND ${refreshingSql("sharing_accounts")}",
        )
            .bind("org_id", account.orgId)
            .bindNullable("owner", account.ownerMemberId, Uuid::class)
            .bind("provider", account.provider.name)
            .bind("provider_account_id", account.providerAccountId),
    ).getOrThrow().firstOrNull() != null

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
                "AND ownership <> 'PENDING_HANDOVER' ORDER BY priority DESC, id LIMIT 1000",
        ).bind("org_id", orgId).bind("provider", provider.name),
    ).getOrThrow().map { it.toAccount() }

    override suspend fun update(account: SharedAccount, expectedVersion: Long?): SharedAccount? = driver.fetchInOrg(
        account.orgId,
        Statement.create(
            "UPDATE sharing_accounts SET label = :label, priority = :priority, " +
                "status = CASE WHEN status IN ($AWAITING_SIGN_IN) THEN status ELSE :status END, " +
                "max_concurrent = :max_concurrent, utilization_ceiling = :ceiling, " +
                "monthly_budget_micros = :budget, daily_budget_micros = :daily, allowed_members = :allowed, " +
                "version = version + 1 " +
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

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        driver.setColumn(orgId, id, "status = :value", bump = true) { bind("value", status.name) }
    }

    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant) {
        driver.setColumn(orgId, id, "cooldown_until = :value", bump = false) { bind("value", until) }
    }

    // TeamCode's handover refusal: an intent on the row, in flight or left by a failure, is cleared by new tokens only.
    override suspend fun setOwnership(orgId: Uuid, id: Uuid, ownership: RefreshOwnership): SharedAccount? =
        driver.fetchInOrg(
            orgId,
            Statement.create(
                "UPDATE sharing_accounts SET ownership = :ownership, version = version + 1 " +
                    "WHERE org_id = :org_id AND id = :id AND NOT refresh_pending RETURNING $ACCOUNT_COLUMNS",
            )
                .bind("org_id", orgId)
                .bind("id", id)
                .bind("ownership", ownership.name),
        ).getOrThrow().firstOrNull()?.toAccount()
}

/**
 * Whether [table]'s row is being refreshed now: an intent younger than [AccountTokens.PENDING_LIMIT] by the
 * database's clock on an account that serves. New tokens then wait for the refresh (TeamCode's import and
 * sign-in conflict): the refresh would store its pair over them, or fail and retire the account. An older
 * intent, or one on an account that waits for a sign-in, is uncertain: new tokens are what clears it.
 */
private fun refreshingSql(table: String) =
    "$table.refresh_pending AND $table.status NOT IN ($AWAITING_SIGN_IN) AND $table.refresh_started_at > " +
        "now() - INTERVAL '${AccountTokens.PENDING_LIMIT.inWholeSeconds} seconds'"

// Sharing again keeps the limits. Two shares racing on a new account both insert: the second keeps the first
// row's id, so its envelope (sealed for its own id) would not open; the first's stays, and so do its status,
// refresh intent and credential version, since no new tokens were stored. An account another member owns is
// never taken over, nor a row past the expected version (no row comes back).
private val SHARE_AGAIN =
    "UPDATE sharing_accounts SET label = :label, email = :email, status = :status, ownership = :ownership, " +
        "cooldown_until = NULL, " +
        "envelope = :envelope, version = version + 1, refresh_pending = FALSE, refresh_started_at = NULL, " +
        "credential_version = credential_version + 1 " +
        "WHERE org_id = :org_id AND provider = :provider AND provider_account_id = :provider_account_id " +
        "AND id = :id AND version = :expected " +
        "AND (owner_member_id IS NULL OR owner_member_id = :owner) " +
        "AND NOT (${refreshingSql("sharing_accounts")}) " +
        "RETURNING $ACCOUNT_COLUMNS"
private val UPSERT =
    "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, " +
        "owner_member_id, priority, status, max_concurrent, utilization_ceiling, monthly_budget_micros, " +
        "daily_budget_micros, allowed_members, cooldown_until, envelope, created_at, version, ownership) " +
        "VALUES (:id, :org_id, :provider, :label, :email, :provider_account_id, :owner, :priority, " +
        ":status, :max_concurrent, :ceiling, :budget, :daily, :allowed, NULL, :envelope, :created_at, 1, " +
        ":ownership) " +
        "ON CONFLICT (org_id, provider, provider_account_id) DO UPDATE SET label = EXCLUDED.label, " +
        "email = EXCLUDED.email, status = CASE WHEN sharing_accounts.id = EXCLUDED.id " +
        "THEN EXCLUDED.status ELSE sharing_accounts.status END, ownership = CASE WHEN sharing_accounts.id = " +
        "EXCLUDED.id THEN EXCLUDED.ownership ELSE sharing_accounts.ownership END, cooldown_until = NULL, " +
        "envelope = CASE WHEN sharing_accounts.id = EXCLUDED.id THEN EXCLUDED.envelope " +
        "ELSE sharing_accounts.envelope END, version = sharing_accounts.version + 1, " +
        "refresh_pending = sharing_accounts.refresh_pending AND sharing_accounts.id <> EXCLUDED.id, " +
        "refresh_started_at = CASE WHEN sharing_accounts.id = EXCLUDED.id THEN NULL " +
        "ELSE sharing_accounts.refresh_started_at END, " +
        "credential_version = sharing_accounts.credential_version + " +
        "CASE WHEN sharing_accounts.id = EXCLUDED.id THEN 1 ELSE 0 END " +
        "WHERE (sharing_accounts.owner_member_id IS NULL " +
        "OR sharing_accounts.owner_member_id = EXCLUDED.owner_member_id) " +
        "AND sharing_accounts.version = COALESCE(:expected, sharing_accounts.version) " +
        "AND NOT (${refreshingSql("sharing_accounts")}) " +
        "RETURNING $ACCOUNT_COLUMNS"
