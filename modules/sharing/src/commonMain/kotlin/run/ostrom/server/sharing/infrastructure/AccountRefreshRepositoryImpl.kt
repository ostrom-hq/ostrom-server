// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.written
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.RefreshState
import run.ostrom.server.sharing.domain.RefreshWrite
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [AccountRefreshRepository] on PostgreSQL ([SharingMigrations] version 7), in transactions confined to the org. The
 * decision opens the tokens (a suspend call, which a transaction never makes), so it runs between a read and a
 * conditional write: the write lands only while the row still holds the state that was read (compare and set), else
 * the state is read and decided again. Two replicas never both claim one refresh: the second's write finds the intent
 * the first committed. The intent's age is read with the database's clock, the one every replica shares (TeamCode's
 * `SqlCredentialSource`).
 *
 * @param database The serving role's pool.
 */
class AccountRefreshRepositoryImpl(private val database: JdbcDatabase) : AccountRefreshRepository {
    override suspend fun <T> locked(
        orgId: Uuid,
        id: Uuid,
        decide: suspend (RefreshState?) -> Pair<RefreshWrite, T>,
    ): T {
        repeat(DECISIONS) {
            val read = database.inOrg(orgId) { read(orgId, id) }
            val (write, result) = decide(read?.state)
            // Only a row that was read is written: a write never follows a missing row.
            val assign = assignmentOf(write)
            if (read == null || assign == null) return result
            if (database.inOrg(orgId) { written { refreshWrite(orgId, id, read, assign) } } == 1) return result
        }
        error("The refresh state of the account kept changing.")
    }

    override suspend fun save(orgId: Uuid, id: Uuid, credentialVersion: Long, envelope: String): Boolean =
        database.inOrg(orgId) {
            val a = SharingAccounts
            a.update({ intent(orgId, id, credentialVersion) }) {
                it[a.envelope] = envelope
                it[a.credentialVersion] = a.credentialVersion + 1
                it[refreshPending] = false
                it[refreshStartedAt] = null
            } == 1
        }

    override suspend fun fail(orgId: Uuid, id: Uuid, credentialVersion: Long, status: AccountStatus): Boolean =
        database.inOrg(orgId) {
            val a = SharingAccounts
            a.update({ intent(orgId, id, credentialVersion) }) {
                it[a.status] = status.name
                it[version] = version + 1
            } == 1
        }
}

/** A state read, with what the write compares it to. */
internal class StateRead(val state: RefreshState, val startedAt: Instant?)

/** The account's refresh state, its intent's age by the database's clock. */
private fun read(orgId: Uuid, id: Uuid): StateRead? {
    val a = SharingAccounts
    val now = databaseNow()
    val row = a.select(
        a.status, a.ownership, a.envelope, a.refreshPending, a.refreshStartedAt, a.credentialVersion, now,
    ).where { (a.orgId eq orgId) and (a.id eq id) }.firstOrNull() ?: return null
    val startedAt = row[a.refreshStartedAt]
    val stale = startedAt != null && startedAt <= row[now] - AccountTokens.PENDING_LIMIT
    return StateRead(
        RefreshState(
            status = AccountStatus.valueOf(row[a.status]),
            envelope = row[a.envelope],
            pending = row[a.refreshPending],
            stale = stale,
            credentialVersion = row[a.credentialVersion],
            ownership = RefreshOwnership.valueOf(row[a.ownership]),
        ),
        startedAt,
    )
}

/** What [write] sets on the row, none for [RefreshWrite.Keep]. */
private fun assignmentOf(write: RefreshWrite): (SharingAccounts.(UpdateStatement) -> Unit)? = when (write) {
    RefreshWrite.Keep -> null
    RefreshWrite.Claim -> { row ->
        row[refreshPending] = true
        row[refreshStartedAt] = databaseNow()
    }
    RefreshWrite.Retire -> { row ->
        row[status] = AccountStatus.RECONNECT_REQUIRED.name
        row[version] = version + 1
    }
    is RefreshWrite.Reseal -> { row -> row[envelope] = write.envelope }
}

/** [assign], only on the row that still holds the state [read]. */
internal fun StatementBuilder.refreshWrite(
    orgId: Uuid,
    id: Uuid,
    read: StateRead,
    assign: SharingAccounts.(UpdateStatement) -> Unit,
): UpdateStatement {
    val a = SharingAccounts
    val state = read.state
    return a.update({
        (a.orgId eq orgId) and (a.id eq id) and (a.status eq state.status.name) and
            (a.ownership eq state.ownership.name) and (a.envelope eq state.envelope) and
            (a.refreshPending eq state.pending) and (a.credentialVersion eq state.credentialVersion) and
            (a.refreshStartedAt eq read.startedAt)
    }, body = assign)
}

/** The account's row while the intent taken at [credentialVersion] is still on it. */
private fun intent(orgId: Uuid, id: Uuid, credentialVersion: Long): Op<Boolean> {
    val a = SharingAccounts
    return (a.orgId eq orgId) and (a.id eq id) and (a.refreshPending eq true) and
        (a.credentialVersion eq credentialVersion)
}

// The decisions a refresh look makes before giving up, each made because another write changed the row meanwhile.
private const val DECISIONS = 8
