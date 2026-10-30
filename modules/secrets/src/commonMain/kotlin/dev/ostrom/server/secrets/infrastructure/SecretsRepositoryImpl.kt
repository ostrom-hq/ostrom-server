// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets.infrastructure

import dev.ostrom.server.secrets.domain.Secret
import dev.ostrom.server.secrets.domain.SecretsRepository
import dev.ostrom.server.secrets.domain.StoredSecret
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SecretsRepository] on PostgreSQL (tables of [SecretsMigrations]).
 *
 * @param driver The database.
 */
class SecretsRepositoryImpl(private val driver: Driver) : SecretsRepository {
    override suspend fun create(stored: StoredSecret): Boolean {
        val secret = stored.secret
        val statement = Statement.create(
            "INSERT INTO secrets_entries " +
                "(id, org_id, name, owner_member_id, envelope, version, created_at, updated_at, updated_by) " +
                "VALUES (:id, :org_id, :name, :owner, :envelope, :version, :created_at, :updated_at, :updated_by) " +
                "ON CONFLICT (org_id, name, owner_member_id) DO NOTHING",
        )
            .bind("id", secret.id)
            .bind("org_id", secret.orgId)
            .bind("name", secret.name)
            .bindOwner(secret.ownerMemberId)
            .bind("envelope", stored.envelope)
            .bind("version", secret.version)
            .bind("created_at", secret.createdAt)
            .bind("updated_at", secret.updatedAt)
            .bind("updated_by", secret.updatedBy)
        return driver.executeInOrg(secret.orgId, statement).getOrThrow() == 1L
    }

    override suspend fun get(orgId: Uuid, id: Uuid): StoredSecret? = driver.fetchInOrg(
        orgId,
        Statement.create("SELECT $COLUMNS, envelope FROM secrets_entries WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow().firstOrNull()?.let { StoredSecret(it.toSecret(), it.get("envelope").asString()) }

    override suspend fun getMany(orgId: Uuid, ids: Collection<Uuid>): List<StoredSecret> {
        if (ids.isEmpty()) return emptyList()
        return driver.fetchInOrg(
            orgId,
            Statement.create("SELECT $COLUMNS, envelope FROM secrets_entries WHERE org_id = :org_id AND id IN :ids")
                .bind("org_id", orgId)
                .bind("ids", ids.toList()),
        ).getOrThrow().map { StoredSecret(it.toSecret(), it.get("envelope").asString()) }
    }

    override suspend fun list(orgId: Uuid, memberId: Uuid?, afterName: String?, limit: Int): List<Secret> {
        val visibility = if (memberId == null) "" else " AND (owner_member_id IS NULL OR owner_member_id = :member)"
        val statement = Statement.create(
            "SELECT $COLUMNS FROM secrets_entries WHERE org_id = :org_id$visibility AND name > :after " +
                "ORDER BY name, id LIMIT :limit",
        )
            .bind("org_id", orgId)
            .bind("after", afterName ?: "")
            .bind("limit", limit)
        if (memberId != null) statement.bind("member", memberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().map { it.toSecret() }
    }

    override suspend fun rotate(orgId: Uuid, id: Uuid, envelope: String, updatedAt: Instant, updatedBy: Uuid): Secret? =
        driver.fetchInOrg(
            orgId,
            Statement.create(
                "UPDATE secrets_entries SET envelope = :envelope, version = version + 1, " +
                    "updated_at = :updated_at, updated_by = :updated_by " +
                    "WHERE org_id = :org_id AND id = :id RETURNING $COLUMNS",
            )
                .bind("envelope", envelope)
                .bind("updated_at", updatedAt)
                .bind("updated_by", updatedBy)
                .bind("org_id", orgId)
                .bind("id", id),
        ).getOrThrow().firstOrNull()?.toSecret()

    override suspend fun findByName(orgId: Uuid, ownerMemberId: Uuid?, name: String): Secret? {
        val owner = if (ownerMemberId == null) "owner_member_id IS NULL" else "owner_member_id = :owner"
        val statement = Statement.create(
            "SELECT $COLUMNS FROM secrets_entries WHERE org_id = :org_id AND name = :name AND $owner",
        )
            .bind("org_id", orgId)
            .bind("name", name)
        if (ownerMemberId != null) statement.bind("owner", ownerMemberId)
        return driver.fetchInOrg(orgId, statement).getOrThrow().firstOrNull()?.toSecret()
    }

    override suspend fun getOrgSecretsByName(orgId: Uuid, names: Collection<String>): List<StoredSecret> {
        if (names.isEmpty()) return emptyList()
        return driver.fetchInOrg(
            orgId,
            Statement.create(
                "SELECT $COLUMNS, envelope FROM secrets_entries " +
                    "WHERE org_id = :org_id AND owner_member_id IS NULL AND name IN :names",
            )
                .bind("org_id", orgId)
                .bind("names", names.toList()),
        ).getOrThrow().map { StoredSecret(it.toSecret(), it.get("envelope").asString()) }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.executeInOrg(
        orgId,
        Statement.create("DELETE FROM secrets_entries WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id),
    ).getOrThrow() == 1L

    private fun Statement.bindOwner(owner: Uuid?): Statement =
        if (owner == null) bindNull("owner", Uuid::class) else bind("owner", owner)

    private fun ResultSet.Row.toSecret() = Secret(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        name = get("name").asString(),
        ownerMemberId = get("owner_member_id").asUuidOrNull(),
        version = get("version").asInt(),
        createdAt = get("created_at").asInstant(),
        updatedAt = get("updated_at").asInstant(),
        updatedBy = get("updated_by").asUuid(),
    )

    private companion object {
        const val COLUMNS = "id, org_id, name, owner_member_id, version, created_at, updated_at, updated_by"
    }
}
