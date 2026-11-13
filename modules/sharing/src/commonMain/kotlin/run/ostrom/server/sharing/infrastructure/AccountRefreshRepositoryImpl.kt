// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.RefreshState
import run.ostrom.server.sharing.domain.RefreshWrite
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.uuid.Uuid

/**
 * [AccountRefreshRepository] on PostgreSQL ([SharingMigrations] version 7): the row lock is `SELECT … FOR UPDATE` in a
 * transaction confined to the org, and the intent's age is read with the database's clock, the one every replica
 * shares (TeamCode's `SqlCredentialSource`).
 *
 * @param driver The database.
 */
class AccountRefreshRepositoryImpl(private val driver: Driver) : AccountRefreshRepository {
    override suspend fun <T> locked(
        orgId: Uuid,
        id: Uuid,
        decide: suspend (RefreshState?) -> Pair<RefreshWrite, T>,
    ): T = driver.inTenant(orgId) {
        val state = fetchAll(Statement.create(LOCK).bind("org_id", orgId).bind("id", id)).getOrThrow()
            .firstOrNull()
            ?.let { row ->
                RefreshState(
                    status = AccountStatus.valueOf(row.get("status").asString()),
                    envelope = row.get("envelope").asString(),
                    pending = row.get("refresh_pending").asBoolean(),
                    stale = row.get("stale").asBoolean(),
                    credentialVersion = row.get("credential_version").asLong(),
                )
            }
        val (write, result) = decide(state)
        val assignment = when (write) {
            RefreshWrite.Keep -> null
            RefreshWrite.Claim -> Statement.create("$UPDATE refresh_pending = TRUE, refresh_started_at = now() $ROW")
            RefreshWrite.Retire ->
                Statement.create("$UPDATE status = 'RECONNECT_REQUIRED', version = version + 1 $ROW")
            is RefreshWrite.Reseal ->
                Statement.create("$UPDATE envelope = :envelope $ROW").bind("envelope", write.envelope)
        }
        // Only a row that was read is written: a write never follows a missing row.
        if (state != null && assignment != null) {
            execute(assignment.bind("org_id", orgId).bind("id", id)).getOrThrow()
        }
        result
    }

    override suspend fun save(orgId: Uuid, id: Uuid, credentialVersion: Long, envelope: String): Boolean =
        driver.executeInOrg(
            orgId,
            Statement.create(
                "$UPDATE envelope = :envelope, credential_version = credential_version + 1, " +
                    "refresh_pending = FALSE, refresh_started_at = NULL $ROW $INTENT",
            )
                .bind("org_id", orgId)
                .bind("id", id)
                .bind("credential_version", credentialVersion)
                .bind("envelope", envelope),
        ).getOrThrow() == 1L

    override suspend fun fail(orgId: Uuid, id: Uuid, credentialVersion: Long, status: AccountStatus): Boolean =
        driver.executeInOrg(
            orgId,
            Statement.create("$UPDATE status = :status, version = version + 1 $ROW $INTENT")
                .bind("org_id", orgId)
                .bind("id", id)
                .bind("credential_version", credentialVersion)
                .bind("status", status.name),
        ).getOrThrow() == 1L

    private companion object {
        const val UPDATE = "UPDATE sharing_accounts SET"
        const val ROW = "WHERE org_id = :org_id AND id = :id"
        const val INTENT = "AND refresh_pending AND credential_version = :credential_version"
        val LOCK =
            "SELECT status, envelope, refresh_pending, credential_version, COALESCE(refresh_started_at <= now() - " +
                "INTERVAL '${AccountTokens.PENDING_LIMIT.inWholeSeconds} seconds', FALSE) AS stale " +
                "FROM sharing_accounts WHERE org_id = :org_id AND id = :id FOR UPDATE"
    }
}
