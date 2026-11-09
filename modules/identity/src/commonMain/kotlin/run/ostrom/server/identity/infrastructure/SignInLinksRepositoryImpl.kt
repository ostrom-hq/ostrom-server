// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.SignInLink
import run.ostrom.server.identity.domain.SignInLinksRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant

/**
 * [SignInLinksRepository] on PostgreSQL (`identity_sign_in_links`, migration 4).
 *
 * @param driver The database.
 */
class SignInLinksRepositoryImpl(private val driver: Driver) : SignInLinksRepository {
    override suspend fun create(link: SignInLink, tokenHash: String) {
        driver.execute(
            Statement.create(
                "INSERT INTO identity_sign_in_links (id, token_hash, user_id, created_at, expires_at) " +
                    "VALUES (:id, :token, :user, :created, :expires)",
            ).bind("id", link.id).bind("token", tokenHash).bind("user", link.userId)
                .bind("created", link.createdAt).bind("expires", link.expiresAt),
        ).getOrThrow()
    }

    override suspend fun consume(tokenHash: String, now: Instant): SignInLink? = driver.fetchAll(
        Statement.create(
            "UPDATE identity_sign_in_links SET used_at = :now WHERE token_hash = :token AND used_at IS NULL " +
                "AND expires_at > :now RETURNING id, user_id, created_at, expires_at",
        ).bind("token", tokenHash).bind("now", now),
    ).getOrThrow().firstOrNull()?.let {
        SignInLink(
            id = it.get("id").asUuid(),
            userId = it.get("user_id").asUuid(),
            createdAt = it.get("created_at").asInstant(),
            expiresAt = it.get("expires_at").asInstant(),
        )
    }
}
