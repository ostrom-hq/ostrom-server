// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.SetupLink
import run.ostrom.server.identity.domain.SetupLinksRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SetupLinksRepository] on PostgreSQL (`identity_setup_links`, migration 17).
 *
 * @param driver The database.
 */
class SetupLinksRepositoryImpl(private val driver: Driver) : SetupLinksRepository {
    override suspend fun replace(link: SetupLink, tokenHash: String) {
        driver.transaction {
            execute(
                Statement.create(
                    "UPDATE identity_setup_links SET expires_at = :now WHERE used_at IS NULL AND expires_at > :now",
                ).bind("now", link.createdAt),
            ).getOrThrow()
            execute(
                Statement.create(
                    "INSERT INTO identity_setup_links (id, token_hash, created_at, expires_at) " +
                        "VALUES (:id, :token, :created, :expires)",
                ).bind("id", link.id).bind("token", tokenHash).bind("created", link.createdAt)
                    .bind("expires", link.expiresAt),
            ).getOrThrow()
        }
    }

    override suspend fun consume(tokenHash: String, userId: Uuid, now: Instant): Boolean = driver.execute(
        Statement.create(
            "UPDATE identity_setup_links SET used_at = :now, used_by = :user WHERE token_hash = :token " +
                "AND used_at IS NULL AND expires_at > :now",
        ).bind("now", now).bind("user", userId).bind("token", tokenHash),
    ).getOrThrow() == 1L

    override suspend fun pending(tokenHash: String, now: Instant): Boolean = driver.fetchAll(
        Statement.create(
            "SELECT 1 FROM identity_setup_links WHERE token_hash = :token AND used_at IS NULL AND expires_at > :now",
        ).bind("token", tokenHash).bind("now", now),
    ).getOrThrow().firstOrNull() != null

    override suspend fun usedBy(userId: Uuid): Boolean = driver.fetchAll(
        Statement.create("SELECT 1 FROM identity_setup_links WHERE used_by = :user LIMIT 1").bind("user", userId),
    ).getOrThrow().firstOrNull() != null

    override suspend fun anyAccount(): Boolean = driver.fetchAll(
        Statement.create("SELECT 1 FROM identity_users LIMIT 1"),
    ).getOrThrow().firstOrNull() != null
}
