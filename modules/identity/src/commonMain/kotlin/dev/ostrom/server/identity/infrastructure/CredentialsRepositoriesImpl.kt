// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.MAX_TOKENS_PER_USER
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.WebSession
import dev.ostrom.server.identity.domain.WebSessionsRepository
import dev.ostrom.server.kernel.database.withOrganization
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WebSessionsRepository] on PostgreSQL.
 *
 * @param driver The database.
 */
class WebSessionsRepositoryImpl(private val driver: Driver) : WebSessionsRepository {
    override suspend fun create(session: WebSession, secretHash: String) {
        driver.execute(
            Statement.create(
                "INSERT INTO identity_web_sessions (id, user_id, secret_hash, created_at, expires_at, last_seen_at) " +
                    "VALUES (:id, :user, :hash, :created, :expires, :seen)",
            ).bind("id", session.id).bind("user", session.userId).bind("hash", secretHash)
                .bind("created", session.createdAt).bind("expires", session.expiresAt).bind("seen", session.lastSeenAt),
        ).getOrThrow()
    }

    override suspend fun findBySecretHash(secretHash: String): WebSession? = driver.fetchAll(
        Statement.create(
            "SELECT id, user_id, created_at, expires_at, last_seen_at, revoked_at FROM identity_web_sessions " +
                "WHERE secret_hash = :hash",
        ).bind("hash", secretHash),
    ).getOrThrow().firstOrNull()?.let {
        WebSession(
            id = it.get("id").asUuid(),
            userId = it.get("user_id").asUuid(),
            createdAt = it.get("created_at").asInstant(),
            expiresAt = it.get("expires_at").asInstant(),
            lastSeenAt = it.get("last_seen_at").asInstant(),
            revokedAt = it.get("revoked_at").asInstantOrNull(),
        )
    }

    override suspend fun touch(id: Uuid, at: Instant) {
        driver.execute(
            Statement.create("UPDATE identity_web_sessions SET last_seen_at = :at WHERE id = :id")
                .bind("at", at).bind("id", id),
        ).getOrThrow()
    }

    override suspend fun revoke(id: Uuid, at: Instant) {
        driver.execute(
            Statement.create("UPDATE identity_web_sessions SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
                .bind("at", at).bind("id", id),
        ).getOrThrow()
    }

    override suspend fun revokeAllOfUser(userId: Uuid, at: Instant): Long = driver.execute(
        Statement.create(
            "UPDATE identity_web_sessions SET revoked_at = :at WHERE user_id = :user AND revoked_at IS NULL",
        )
            .bind("at", at).bind("user", userId),
    ).getOrThrow()
}

/**
 * [ApiTokensRepository] on PostgreSQL. Scopes are stored space-separated (a scope never contains a space).
 *
 * Outside any organization the serving role only sees the account-level tokens (personal and CLI, `org_id IS NULL`),
 * which a user lists, rotates and revokes. An organization's token (an agent token) is written inside its
 * organization; it is found by its hash, marked as used and revoked with its agent session through the
 * `identity_*` functions of `ostrom_router` (RLS audit R1, docs/tenancy.md).
 *
 * @param driver The database.
 */
class ApiTokensRepositoryImpl(private val driver: Driver) : ApiTokensRepository {
    override suspend fun create(token: ApiToken, secretHash: String) {
        val orgId = token.orgId
        if (orgId == null) {
            insert(driver, token, secretHash)
        } else {
            driver.withOrganization(orgId) { insert(it, token, secretHash) }
        }
    }

    override suspend fun findBySecretHash(secretHash: String): ApiToken? = driver.fetchAll(
        Statement.create("SELECT $TOKEN_COLUMNS FROM identity_token_by_hash(:hash)").bind("hash", secretHash),
    ).getOrThrow().firstOrNull()?.toApiToken()

    override suspend fun get(userId: Uuid, id: Uuid): ApiToken? = driver.fetchAll(
        Statement.create("SELECT $TOKEN_COLUMNS FROM identity_api_tokens WHERE id = :id AND user_id = :user")
            .bind("id", id).bind("user", userId),
    ).getOrThrow().firstOrNull()?.toApiToken()

    override suspend fun listLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): List<ApiToken> = driver.fetchAll(
        Statement.create(
            "SELECT $TOKEN_COLUMNS FROM identity_api_tokens WHERE user_id = :user AND revoked_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > :now) AND kind IN :kinds ORDER BY created_at DESC, id " +
                "LIMIT :limit",
        ).bind("user", userId).bind("now", now).bind("kinds", kinds.map { it.name }).bind("limit", MAX_TOKENS_PER_USER),
    ).getOrThrow().map { it.toApiToken() }

    override suspend fun countLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): Int = driver.fetchAll(
        Statement.create(
            "SELECT COUNT(*) AS live FROM (SELECT 1 FROM identity_api_tokens WHERE user_id = :user AND " +
                "revoked_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > :now) AND kind IN :kinds LIMIT :limit) AS capped",
        ).bind("user", userId).bind("now", now).bind("kinds", kinds.map { it.name }).bind("limit", MAX_TOKENS_PER_USER),
    ).getOrThrow().first().get("live").asInt()

    override suspend fun touch(id: Uuid, at: Instant) {
        driver.execute(
            Statement.create("SELECT identity_token_touch(CAST(:id AS UUID), CAST(:at AS TIMESTAMPTZ))")
                .bind("id", id).bind("at", at),
        ).getOrThrow()
    }

    override suspend fun revoke(userId: Uuid, id: Uuid, at: Instant): Boolean = driver.execute(
        Statement.create(
            "UPDATE identity_api_tokens SET revoked_at = :at WHERE id = :id AND user_id = :user AND revoked_at " +
                "IS NULL " +
                "AND kind <> 'AGENT'",
        ).bind("at", at).bind("id", id).bind("user", userId),
    ).getOrThrow() == 1L

    override suspend fun rotate(
        userId: Uuid,
        oldId: Uuid,
        replacement: ApiToken,
        secretHash: String,
        at: Instant,
    ): Boolean =
        driver.transaction {
            val revoked = execute(
                Statement.create(
                    "UPDATE identity_api_tokens SET revoked_at = :at WHERE id = :id AND user_id = :user " +
                        "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :at)",
                ).bind("at", at).bind("id", oldId).bind("user", userId),
            ).getOrThrow()
            if (revoked == 1L) insert(this, replacement, secretHash)
            revoked == 1L
        }

    override suspend fun revokeOfAgentSession(agentSessionId: String, at: Instant): Long = driver.fetchAll(
        Statement.create(
            "SELECT identity_revoke_agent_session_tokens(:session, CAST(:at AS TIMESTAMPTZ)) AS revoked",
        ).bind("session", agentSessionId).bind("at", at),
    ).getOrThrow().first().get("revoked").asLong()

    private companion object {
        const val TOKEN_COLUMNS = "id, user_id, org_id, name, kind, scopes, hint, agent_session_id, created_at, " +
            "expires_at, last_used_at, revoked_at"
    }
}

/**
 * Inserts a token with an executor (the pool, or a transaction).
 *
 * @param executor Where to run the insert.
 * @param token The token.
 * @param secretHash The SHA-256 of its secret.
 */
internal suspend fun insert(executor: QueryExecutor, token: ApiToken, secretHash: String) {
    executor.execute(
        Statement.create(
            "INSERT INTO identity_api_tokens (id, user_id, org_id, name, kind, scopes, hint, agent_session_id, " +
                "secret_hash, " +
                "created_at, expires_at, last_used_at, revoked_at) VALUES (:id, :user, :org, :name, :kind, " +
                ":scopes, :hint, " +
                ":session, :hash, :created, :expires, NULL, NULL)",
        ).bind("id", token.id).bind("user", token.userId).bind("org", token.orgId).bind("name", token.name)
            .bind("kind", token.kind.name).bind("scopes", token.scopes.sorted().joinToString(" "))
            .bind("hint", token.hint)
            .bind("session", token.agentSessionId).bind("hash", secretHash).bind("created", token.createdAt)
            .bind("expires", token.expiresAt),
    ).getOrThrow()
}

/** Reads a token row. */
internal fun ResultSet.Row.toApiToken() = ApiToken(
    id = get("id").asUuid(),
    userId = get("user_id").asUuid(),
    orgId = get("org_id").asUuidOrNull(),
    name = get("name").asString(),
    kind = TokenKind.of(get("kind").asString()),
    scopes = get("scopes").asString().split(' ').toSet() - "",
    hint = get("hint").asString(),
    agentSessionId = get("agent_session_id").asStringOrNull(),
    createdAt = get("created_at").asInstant(),
    expiresAt = get("expires_at").asInstantOrNull(),
    lastUsedAt = get("last_used_at").asInstantOrNull(),
    revokedAt = get("revoked_at").asInstantOrNull(),
)
