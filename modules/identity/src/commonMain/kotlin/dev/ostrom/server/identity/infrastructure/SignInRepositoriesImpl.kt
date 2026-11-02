// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.MagicLink
import dev.ostrom.server.identity.domain.MagicLinksRepository
import dev.ostrom.server.identity.domain.MobileClient
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
                    "nonce, redirect_path, created_at, expires_at, link_user_id, mobile_redirect_uri, " +
                    "mobile_code_challenge, mobile_state) VALUES (:id, :state, :provider, :connection, :verifier, " +
                    ":nonce, :redirect, :created, :expires, :link, :mobileUri, :mobileChallenge, :mobileState)",
            ).bind("id", attempt.id).bind("state", stateHash).bind("provider", attempt.provider.key)
                .bind("connection", attempt.connectionId).bind("verifier", attempt.codeVerifier)
                .bind("nonce", attempt.nonce)
                .bind("redirect", attempt.redirectPath).bind("created", attempt.createdAt)
                .bind("expires", attempt.expiresAt).bind("link", attempt.linkUserId)
                .bind("mobileUri", attempt.mobile?.redirectUri).bind("mobileChallenge", attempt.mobile?.codeChallenge)
                .bind("mobileState", attempt.mobile?.state),
        ).getOrThrow()
    }

    override suspend fun consume(stateHash: String, now: Instant): OAuthAttempt? = driver.fetchAll(
        // One statement marks it used and returns it: two callbacks with the same state cannot both get it.
        Statement.create(
            "UPDATE identity_oauth_attempts SET used_at = :now WHERE state_hash = :state AND used_at IS NULL " +
                "AND expires_at > :now RETURNING id, provider, connection_id, code_verifier, nonce, redirect_path, " +
                "created_at, expires_at, link_user_id, mobile_redirect_uri, mobile_code_challenge, mobile_state",
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
        mobile = get("mobile_redirect_uri").asStringOrNull()?.let { uri ->
            MobileClient(uri, get("mobile_code_challenge").asString(), get("mobile_state").asString())
        },
    )
}

/**
 * [MagicLinksRepository] on PostgreSQL.
 *
 * @param driver The database.
 */
class MagicLinksRepositoryImpl(private val driver: Driver) : MagicLinksRepository {
    override suspend fun create(link: MagicLink, tokenHash: String, browserHash: String) {
        driver.execute(
            Statement.create(
                "INSERT INTO identity_magic_links " +
                    "(id, token_hash, email, redirect_path, created_at, expires_at, browser_hash) " +
                    "VALUES (:id, :token, :email, :redirect, :created, :expires, :browser)",
            ).bind("id", link.id).bind("token", tokenHash).bind("email", link.email).bind("redirect", link.redirectPath)
                .bind("created", link.createdAt).bind("expires", link.expiresAt).bind("browser", browserHash),
        ).getOrThrow()
    }

    // A link of before the browser binding (browser_hash NULL) matches no browser: ask for a new one.
    override suspend fun consume(tokenHash: String, browserHash: String, now: Instant): MagicLink? = driver.fetchAll(
        Statement.create(
            "UPDATE identity_magic_links SET used_at = :now WHERE token_hash = :token AND used_at IS NULL " +
                "AND expires_at > :now AND browser_hash = :browser RETURNING $LINK_COLUMNS",
        ).bind("token", tokenHash).bind("now", now).bind("browser", browserHash),
    ).getOrThrow().firstOrNull()?.toLink()

    override suspend fun pending(tokenHash: String, now: Instant): Boolean = driver.fetchAll(
        Statement.create(
            "SELECT 1 FROM identity_magic_links WHERE token_hash = :token AND used_at IS NULL AND expires_at > :now",
        ).bind("token", tokenHash).bind("now", now),
    ).getOrThrow().firstOrNull() != null

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
