// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance.infrastructure

import dev.ostrom.server.instance.domain.Instance
import dev.ostrom.server.instance.domain.InstanceRepository
import dev.ostrom.server.instance.domain.KeyRotation
import dev.ostrom.server.instance.domain.SigningKey
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid

/**
 * [InstanceRepository] on PostgreSQL (tables of [InstanceMigrations]).
 *
 * @param driver The database.
 */
class InstanceRepositoryImpl(private val driver: Driver) : InstanceRepository {
    override suspend fun get(): Instance? =
        driver.fetchAll("SELECT id, created_at FROM instance_info").getOrThrow().firstOrNull()?.toInstance()

    override suspend fun createIfAbsent(instance: Instance): Instance {
        driver.execute(
            Statement.create(
                "INSERT INTO instance_info (singleton, id, created_at) VALUES (TRUE, :id, :created_at) " +
                    "ON CONFLICT (singleton) DO NOTHING",
            )
                .bind("id", instance.id)
                .bind("created_at", instance.createdAt),
        ).getOrThrow()
        // The row exists now, ours or the one another replica wrote first.
        return driver.fetchAll("SELECT id, created_at FROM instance_info").getOrThrow().single().toInstance()
    }

    override suspend fun registerKey(key: SigningKey) {
        driver.execute(
            Statement.create(
                "INSERT INTO instance_signing_keys " +
                    "(key_id, algorithm, public_key_pem, first_seen_at, rotation_key_id, rotation_signature) " +
                    "VALUES (:key_id, :algorithm, :public_key_pem, :first_seen_at, :rotation_key_id, " +
                    ":rotation_signature) ON CONFLICT (key_id) DO UPDATE SET " +
                    "rotation_key_id = EXCLUDED.rotation_key_id, rotation_signature = EXCLUDED.rotation_signature " +
                    "WHERE instance_signing_keys.rotation_key_id IS NULL",
            )
                .bind("key_id", key.keyId)
                .bind("algorithm", key.algorithm)
                .bind("public_key_pem", key.publicKeyPem)
                .bind("first_seen_at", key.firstSeenAt)
                .bind("rotation_key_id", key.rotation?.keyId)
                .bind("rotation_signature", key.rotation?.signature),
        ).getOrThrow()
    }

    override suspend fun listKeys(): List<SigningKey> = driver.fetchAll(
        "SELECT key_id, algorithm, public_key_pem, first_seen_at, rotation_key_id, rotation_signature " +
            "FROM instance_signing_keys ORDER BY first_seen_at, key_id",
    ).getOrThrow().map { it.toSigningKey() }

    private fun ResultSet.Row.toInstance() = Instance(
        id = get("id").asUuid(),
        createdAt = get("created_at").asInstant(),
    )

    private fun ResultSet.Row.toSigningKey() = SigningKey(
        keyId = get("key_id").asString(),
        algorithm = get("algorithm").asString(),
        publicKeyPem = get("public_key_pem").asString(),
        firstSeenAt = get("first_seen_at").asInstant(),
        rotation = get("rotation_key_id").asStringOrNull()
            ?.let { KeyRotation(it, get("rotation_signature").asString()) },
    )
}
