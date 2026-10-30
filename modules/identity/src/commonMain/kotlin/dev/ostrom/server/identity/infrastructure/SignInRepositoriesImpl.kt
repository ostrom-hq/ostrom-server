// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.MagicLink
import dev.ostrom.server.identity.domain.MagicLinksRepository
import dev.ostrom.server.identity.domain.OAuthAttempt
import dev.ostrom.server.identity.domain.OAuthAttemptsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant

/**
 * [OAuthAttemptsRepository] on PostgreSQL.
 *
 * @param driver The database.
 */
class OAuthAttemptsRepositoryImpl(private val driver: Driver) : OAuthAttemptsRepository {
    override suspend fun create(attempt: OAuthAttempt, stateHash: String) {
        driver.execute(
            Statement.create(
                "INSERT INTO identity_oauth_attempts (id, state_hash, provider, connection_id, code_verifier, " +
                    "nonce, redirect_path, created_at, expires_at, link_user_id) VALUES (:id, :state, :provider, " +
                    ":connection, :verifier, :nonce, :redirect, :created, :expires, :link)",
            ).bind("id", attempt.id).bind("state", stateHash).bind("provider", attempt.provider.key)
                .bind("connection", attempt.connectionId).bind("verifier", attempt.codeVerifier)
                .bind("nonce", attempt.nonce)
                .bind("redirect", attempt.redirectPath).bind("created", attempt.createdAt)
                .bind("expires", attempt.expiresAt).bind("link", attempt.linkUserId),
        ).getOrThrow()
    }

    override suspend fun consume(stateHash: String, now: Instant): OAuthAttempt? = driver.fetchAll(
        // One statement marks it used and returns it: two callbacks with the same state cannot both get it.
        Statement.create(
            "UPDATE identity_oauth_attempts SET used_at = :now WHERE state_hash = :state AND used_at IS NULL " +
                "AND expires_at > :now RETURNING id, provider, connection_id, code_verifier, nonce, redirect_path, " +
                "created_at, expires_at, link_user_id",
        ).bind("state", stateHash).bind("now", now),
    ).getOrThrow().firstOrNull()?.toAttempt()

    private fun ResultSet.Row.toAttempt() = OAuthAttempt(
        id = get("id").asUuid(),
        provider = IdentityProvider.of(get("provider").asString()),
        connectionId = get("connection_id").asUuidOrNull(),
        codeVerifier = get("code_verifier").asString(),
        nonce = get("nonce").asString(),
        redirectPath = get("redirect_path").asString(),
        createdAt = get("created_at").asInstant(),
        expiresAt = get("expires_at").asInstant(),
        linkUserId = get("link_user_id").asUuidOrNull(),
    )
}

/**
 * [MagicLinksRepository] on PostgreSQL.
 *
 * @param driver The database.
 */
class MagicLinksRepositoryImpl(private val driver: Driver) : MagicLinksRepository {
    override suspend fun create(link: MagicLink, tokenHash: String) {
        driver.execute(
            Statement.create(
                "INSERT INTO identity_magic_links (id, token_hash, email, redirect_path, created_at, expires_at) " +
                    "VALUES (:id, :token, :email, :redirect, :created, :expires)",
            ).bind("id", link.id).bind("token", tokenHash).bind("email", link.email).bind("redirect", link.redirectPath)
                .bind("created", link.createdAt).bind("expires", link.expiresAt),
        ).getOrThrow()
    }

    override suspend fun consume(tokenHash: String, now: Instant): MagicLink? = driver.fetchAll(
        Statement.create(
            "UPDATE identity_magic_links SET used_at = :now WHERE token_hash = :token AND used_at IS NULL " +
                "AND expires_at > :now RETURNING $LINK_COLUMNS",
        ).bind("token", tokenHash).bind("now", now),
    ).getOrThrow().firstOrNull()?.toLink()

    private fun ResultSet.Row.toLink() = MagicLink(
        id = get("id").asUuid(),
        email = get("email").asString(),
        redirectPath = get("redirect_path").asString(),
        createdAt = get("created_at").asInstant(),
        expiresAt = get("expires_at").asInstant(),
    )

    private companion object {
        const val LINK_COLUMNS = "id, email, redirect_path, created_at, expires_at"
    }
}
