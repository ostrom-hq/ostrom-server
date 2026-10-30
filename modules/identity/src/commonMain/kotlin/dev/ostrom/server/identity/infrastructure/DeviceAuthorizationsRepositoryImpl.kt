package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.DeviceAuthorization
import dev.ostrom.server.identity.domain.DeviceAuthorizationStatus
import dev.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import io.github.smyrgeorge.sqlx4k.Driver
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
                    "created_at, expires_at) VALUES (:id, :hash, :code, :client, :status, :created, :expires)",
            ).bind("id", authorization.id).bind("hash", deviceCodeHash).bind("code", authorization.userCode)
                .bind("client", authorization.clientName).bind("status", authorization.status.name)
                .bind("created", authorization.createdAt).bind("expires", authorization.expiresAt),
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

    override suspend fun answer(id: Uuid, userId: Uuid, approved: Boolean, now: Instant): Boolean = driver.execute(
        Statement.create(
            "UPDATE identity_device_authorizations SET status = :status, user_id = :user " +
                "WHERE id = :id AND status = 'PENDING' AND expires_at > :now",
        ).bind("status", if (approved) DeviceAuthorizationStatus.APPROVED.name else DeviceAuthorizationStatus.DENIED.name)
            .bind("user", userId).bind("id", id).bind("now", now),
    ).getOrThrow() == 1L

    override suspend fun markPolled(id: Uuid, at: Instant) {
        driver.execute(
            Statement.create("UPDATE identity_device_authorizations SET last_polled_at = :at WHERE id = :id")
                .bind("at", at).bind("id", id),
        ).getOrThrow()
    }

    override suspend fun consume(id: Uuid, token: ApiToken, secretHash: String): Boolean = driver.transaction {
        val consumed = execute(
            Statement.create(
                "UPDATE identity_device_authorizations SET status = 'CONSUMED' WHERE id = :id AND status = 'APPROVED'",
            ).bind("id", id),
        ).getOrThrow() == 1L
        if (consumed) insert(this, token, secretHash)
        consumed
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
    )

    private companion object {
        const val COLUMNS = "id, user_code, client_name, status, user_id, created_at, expires_at, last_polled_at"
    }
}
