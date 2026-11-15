// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.sharing.domain.AccountChangedException
import run.ostrom.server.sharing.domain.AccountOwnedByAnotherMemberException
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshingException
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountRepository
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SharedAccountRepository] on PostgreSQL ([SharingMigrations]) through Exposed. Every query runs confined to the org
 * it names (`inOrg`, the tenant role and `ostrom.org_id`): row-level security hides and refuses the rows of every
 * other org (S2-01, S2-21), and each statement filters on `org_id` too.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // One implementation of the one port of the accounts.
class SharedAccountRepositoryImpl(private val database: JdbcDatabase) : SharedAccountRepository {
    override suspend fun upsert(account: SharedAccount, envelope: String, expectedVersion: Long?): SharedAccount {
        // At a version above 0 the row must still exist: a plain update, so a row deleted meanwhile is never inserted
        // again.
        val row = database.inOrg(account.orgId) {
            if (expectedVersion != null && expectedVersion > 0) {
                shareAgain(account, envelope, expectedVersion)
            } else {
                firstShare(account, envelope, expectedVersion)
            }.firstOrNull()
        }
        if (row == null) {
            throw when {
                refreshing(account) -> AccountRefreshingException()
                expectedVersion == null -> AccountOwnedByAnotherMemberException()
                else -> AccountChangedException()
            }
        }
        return row.toAccount()
    }

    override suspend fun get(orgId: Uuid, id: Uuid): SharedAccount? = database.inOrg(orgId) {
        val a = SharingAccounts
        val row = a.select(ACCOUNT_COLUMNS).where { (a.orgId eq orgId) and (a.id eq id) }.firstOrNull()
        if (row == null) null else row.toAccount()
    }

    override suspend fun list(orgId: Uuid): List<SharedAccount> = database.inOrg(orgId) {
        val a = SharingAccounts
        a.select(ACCOUNT_COLUMNS).where { a.orgId eq orgId }
            .orderBy(a.priority to SortOrder.DESC, a.label to SortOrder.ASC, a.id to SortOrder.ASC)
            .limit(MAX_ACCOUNTS)
            .map { it.toAccount() }
    }

    override suspend fun ready(orgId: Uuid, provider: AccountProvider): List<SharedAccount> = database.inOrg(orgId) {
        val a = SharingAccounts
        a.select(ACCOUNT_COLUMNS)
            .where {
                (a.orgId eq orgId) and (a.provider eq provider.name) and (a.status eq AccountStatus.READY.name) and
                    (a.ownership neq RefreshOwnership.PENDING_HANDOVER.name)
            }
            .orderBy(a.priority to SortOrder.DESC, a.id to SortOrder.ASC)
            .limit(MAX_ACCOUNTS)
            .map { it.toAccount() }
    }

    override suspend fun update(account: SharedAccount, expectedVersion: Long?): SharedAccount? =
        database.inOrg(account.orgId) {
            val a = SharingAccounts
            val row = a.updateReturning(ACCOUNT_COLUMNS, where = {
                (a.orgId eq account.orgId) and (a.id eq account.id) and
                    (a.version eq coalesce(QueryParameter(expectedVersion, LongColumnType()), a.version))
            }) {
                it[label] = account.label
                it[priority] = account.priority
                // A status only sharing again leaves stays.
                it[status] = case()
                    .When(status inList AWAITING_SIGN_IN, status)
                    .Else(stringParam(account.status.name))
                it.limits(account.limits)
                it[poolId] = account.poolId
                it[version] = version + 1
            }.firstOrNull()
            if (row == null) null else row.toAccount()
        }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        SharingAccounts.deleteWhere { (SharingAccounts.orgId eq orgId) and (SharingAccounts.id eq id) } == 1
    }

    override suspend fun envelope(orgId: Uuid, id: Uuid): String? = database.inOrg(orgId) {
        val a = SharingAccounts
        val row = a.select(a.envelope).where { (a.orgId eq orgId) and (a.id eq id) }.firstOrNull()
        if (row == null) null else row[a.envelope]
    }

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        database.inOrg(orgId) {
            val a = SharingAccounts
            a.update({ (a.orgId eq orgId) and (a.id eq id) }) {
                it[a.status] = status.name
                it[version] = version + 1
            }
        }
    }

    // A routine write: the version stays, so it never fails a conditional write.
    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant) {
        database.inOrg(orgId) {
            val a = SharingAccounts
            a.update({ (a.orgId eq orgId) and (a.id eq id) }) { it[cooldownUntil] = until }
        }
    }

    // TeamCode's handover refusal: an intent on the row, in flight or left by a failure, is cleared by new tokens only.
    override suspend fun setOwnership(orgId: Uuid, id: Uuid, ownership: RefreshOwnership): SharedAccount? =
        database.inOrg(orgId) {
            val a = SharingAccounts
            val row = a.updateReturning(ACCOUNT_COLUMNS, where = {
                (a.orgId eq orgId) and (a.id eq id) and not(a.refreshPending)
            }) {
                it[a.ownership] = ownership.name
                it[version] = version + 1
            }.firstOrNull()
            if (row == null) null else row.toAccount()
        }

    /** Whether the org's row of [account]'s provider account is being refreshed now. */
    private suspend fun refreshing(account: SharedAccount): Boolean = database.inOrg(account.orgId) {
        val a = SharingAccounts
        a.select(a.id)
            .where {
                (a.orgId eq account.orgId) and (a.provider eq account.provider.name) and
                    (a.providerAccountId eq account.providerAccountId) and
                    (a.ownerMemberId.isNull() or (a.ownerMemberId eq account.ownerMemberId)) and refreshingNow()
            }
            .firstOrNull() != null
    }
}

/**
 * Whether the row is being refreshed now: an intent younger than [AccountTokens.PENDING_LIMIT] by the database's
 * clock on an account that serves. New tokens then wait for the refresh (TeamCode's import and sign-in conflict): the
 * refresh would store its pair over them, or fail and retire the account. An older intent, or one on an account that
 * waits for a sign-in, is uncertain: new tokens are what clears it.
 */
private fun refreshingNow(): Op<Boolean> {
    val a = SharingAccounts
    return (a.refreshPending eq true) and (a.status notInList AWAITING_SIGN_IN) and
        (a.refreshStartedAt greater databaseNowPlus(-AccountTokens.PENDING_LIMIT))
}

// Sharing again keeps the limits. An account another member owns is never taken over, nor a row past the expected
// version (no row comes back).
private fun shareAgain(account: SharedAccount, envelope: String, expectedVersion: Long) = SharingAccounts.run {
    updateReturning(ACCOUNT_COLUMNS, where = {
        (orgId eq account.orgId) and (provider eq account.provider.name) and
            (providerAccountId eq account.providerAccountId) and (id eq account.id) and
            (version eq expectedVersion) and
            (ownerMemberId.isNull() or (ownerMemberId eq account.ownerMemberId)) and not(refreshingNow())
    }) {
        it[label] = account.label
        it[email] = account.email
        it[status] = account.status.name
        it[ownership] = account.ownership.name
        it[cooldownUntil] = null
        it[this.envelope] = envelope
        it[version] = version + 1
        it[refreshPending] = false
        it[refreshStartedAt] = null
        it[credentialVersion] = credentialVersion + 1
    }
}

// Two shares racing on a new account both insert: the second keeps the first row's id, so its envelope (sealed for its
// own id) would not open; the first's stays, and so do its status, refresh intent and credential version, since no
// new tokens were stored.
private fun firstShare(account: SharedAccount, envelope: String, expectedVersion: Long?) = SharingAccounts.run {
    val same = id eq insertValue(id)
    upsertReturning(
        orgId, provider, providerAccountId,
        returning = ACCOUNT_COLUMNS,
        onUpdate = {
            it[label] = insertValue(label)
            it[email] = insertValue(email)
            it[status] = case().When(same, insertValue(status)).Else(status)
            it[ownership] = case().When(same, insertValue(ownership)).Else(ownership)
            it[cooldownUntil] = null
            val sealed = SharingAccounts.envelope
            it[sealed] = case().When(same, insertValue(sealed)).Else(sealed)
            it[version] = version + 1
            it[refreshPending] = refreshPending and (id neq insertValue(id))
            it[refreshStartedAt] = case().When(same, QueryParameter(null, refreshStartedAt.columnType))
                .Else(refreshStartedAt)
            it[credentialVersion] = credentialVersion + case().When(same, longLiteral(1)).Else(longLiteral(0))
        },
        where = {
            (ownerMemberId.isNull() or (ownerMemberId eq insertValue(ownerMemberId))) and
                (version eq coalesce(QueryParameter(expectedVersion, LongColumnType()), version)) and
                not(refreshingNow())
        },
    ) {
        it[id] = account.id
        it[orgId] = account.orgId
        it[provider] = account.provider.name
        it[label] = account.label
        it[email] = account.email
        it[providerAccountId] = account.providerAccountId
        it[ownerMemberId] = account.ownerMemberId
        it[priority] = account.priority
        it[status] = account.status.name
        it.limits(account.limits)
        it[cooldownUntil] = null
        it[this.envelope] = envelope
        it[createdAt] = account.createdAt
        it[version] = 1
        it[ownership] = account.ownership.name
    }
}

private const val MAX_ACCOUNTS = 1000
