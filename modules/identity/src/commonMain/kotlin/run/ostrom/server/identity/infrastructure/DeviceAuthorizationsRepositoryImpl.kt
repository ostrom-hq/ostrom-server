// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.DeviceAuthorization
import run.ostrom.server.identity.domain.DeviceAuthorizationStatus
import run.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import run.ostrom.server.kernel.database.withOrganization
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [DeviceAuthorizationsRepository] on PostgreSQL.
 *
 * @param driver The database.
 */
class DeviceAuthorizationsRepositoryImpl(private val driver: Driver) : DeviceAuthorizationsRepository {
    override suspend fun create(authorization: DeviceAuthorization, deviceCodeHash: String) {
        driver.execute(
            Statement.create(
                "INSERT INTO identity_device_authorizations (id, device_code_hash, user_code, client_name, status, " +
                    "created_at, expires_at, scopes, requested_from) " +
                    "VALUES (:id, :hash, :code, :client, :status, :created, :expires, :scopes, :from)",
            ).bind("id", authorization.id).bind("hash", deviceCodeHash).bind("code", authorization.userCode)
                .bind("client", authorization.clientName).bind("status", authorization.status.name)
                .bind("created", authorization.createdAt).bind("expires", authorization.expiresAt)
                .bind("scopes", authorization.scopes.sorted().joinToString(" "))
                .bind("from", authorization.requestedFrom),
        ).getOrThrow()
    }

    override suspend fun findByDeviceCodeHash(deviceCodeHash: String): DeviceAuthorization? = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM identity_device_authorizations WHERE device_code_hash = :hash")
            .bind("hash", deviceCodeHash),
    ).getOrThrow().firstOrNull()?.toAuthorization()

    override suspend fun findPendingByUserCode(userCode: String, now: Instant): DeviceAuthorization? = driver.fetchAll(
        Statement.create(
            "SELECT $COLUMNS FROM identity_device_authorizations WHERE user_code = :code AND status = 'PENDING' " +
                "AND expires_at > :now",
        ).bind("code", userCode).bind("now", now),
    ).getOrThrow().firstOrNull()?.toAuthorization()

    override suspend fun answer(
        id: Uuid,
        userId: Uuid,
        approved: Boolean,
        now: Instant,
        approvingOrgId: Uuid?,
    ): Boolean {
        val status = if (approved) DeviceAuthorizationStatus.APPROVED else DeviceAuthorizationStatus.DENIED
        return driver.execute(
            Statement.create(
                "UPDATE identity_device_authorizations SET status = :status, user_id = :user, " +
                    "approving_org_id = :org WHERE id = :id AND status = 'PENDING' AND expires_at > :now",
            ).bind("status", status.name).bind("user", userId).bind("org", approvingOrgId).bind("id", id)
                .bind("now", now),
        ).getOrThrow() == 1L
    }

    override suspend fun markPolled(id: Uuid, at: Instant) {
        driver.execute(
            Statement.create("UPDATE identity_device_authorizations SET last_polled_at = :at WHERE id = :id")
                .bind("at", at).bind("id", id),
        ).getOrThrow()
    }

    override suspend fun consume(id: Uuid, token: ApiToken, secretHash: String): Boolean {
        val orgId = token.orgId
        return if (orgId == null) {
            driver.transaction { consume(this, id, token, secretHash) }
        } else {
            // An organization's token is written in it (RLS): the authorization itself belongs to no organization.
            driver.withOrganization(orgId) { consume(it, id, token, secretHash) }
        }
    }

    private suspend fun consume(tx: QueryExecutor, id: Uuid, token: ApiToken, secretHash: String): Boolean {
        val consumed = tx.execute(
            Statement.create(
                "UPDATE identity_device_authorizations SET status = 'CONSUMED' WHERE id = :id AND status = 'APPROVED'",
            ).bind("id", id),
        ).getOrThrow() == 1L
        if (consumed) insert(tx, token, secretHash)
        return consumed
    }

    private fun ResultSet.Row.toAuthorization() = DeviceAuthorization(
        id = get("id").asUuid(),
        userCode = get("user_code").asString(),
        clientName = get("client_name").asString(),
        status = DeviceAuthorizationStatus.valueOf(get("status").asString()),
        userId = get("user_id").asUuidOrNull(),
        createdAt = get("created_at").asInstant(),
        expiresAt = get("expires_at").asInstant(),
        lastPolledAt = get("last_polled_at").asInstantOrNull(),
        scopes = get("scopes").asString().split(' ').filter { it.isNotEmpty() }.toSet(),
        approvingOrgId = get("approving_org_id").asUuidOrNull(),
        requestedFrom = get("requested_from").asStringOrNull(),
    )

    private companion object {
        const val COLUMNS =
            "id, user_code, client_name, status, user_id, created_at, expires_at, last_polled_at, scopes, " +
                "approving_org_id, requested_from"
    }
}
