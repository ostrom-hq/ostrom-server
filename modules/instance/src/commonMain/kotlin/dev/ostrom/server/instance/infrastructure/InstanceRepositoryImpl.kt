package dev.ostrom.server.instance.infrastructure

import dev.ostrom.server.instance.domain.Instance
import dev.ostrom.server.instance.domain.InstanceRepository
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
        return checkNotNull(get()) { "The instance row is missing right after its creation." }
    }

    override suspend fun registerKey(key: SigningKey) {
        driver.execute(
            Statement.create(
                "INSERT INTO instance_signing_keys (key_id, algorithm, public_key_pem, first_seen_at) " +
                    "VALUES (:key_id, :algorithm, :public_key_pem, :first_seen_at) ON CONFLICT (key_id) DO NOTHING",
            )
                .bind("key_id", key.keyId)
                .bind("algorithm", key.algorithm)
                .bind("public_key_pem", key.publicKeyPem)
                .bind("first_seen_at", key.firstSeenAt),
        ).getOrThrow()
    }

    override suspend fun listKeys(): List<SigningKey> = driver.fetchAll(
        "SELECT key_id, algorithm, public_key_pem, first_seen_at FROM instance_signing_keys ORDER BY first_seen_at, key_id",
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
    )
}
