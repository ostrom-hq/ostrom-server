// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ElevatedTokensRepository
import run.ostrom.server.identity.domain.MAX_TOKENS_PER_USER
import run.ostrom.server.kernel.database.withOrganization
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ElevatedTokensRepository] on PostgreSQL: the `ADMIN` rows of `identity_api_tokens`, inside their organization (the
 * `identity_api_tokens_tenant` policy). No function of `ostrom_router` reaches them from outside it.
 *
 * @param driver The database.
 */
class ElevatedTokensRepositoryImpl(private val driver: Driver) : ElevatedTokensRepository {
    override suspend fun listLive(orgId: Uuid, userId: Uuid?, now: Instant): List<ApiToken> {
        val statement = if (userId == null) {
            Statement.create(LIVE_ELEVATED_TOKENS_SQL)
        } else {
            Statement.create(LIVE_ELEVATED_TOKENS_OF_USER_SQL).bind("user", userId)
        }
        statement.bind("org", orgId).bind("now", now).bind("limit", MAX_TOKENS_PER_USER)
        return driver.withOrganization(orgId) { tx -> tx.fetchAll(statement).getOrThrow().map { it.toApiToken() } }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): ApiToken? =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create("SELECT $ELEVATED_COLUMNS FROM identity_api_tokens WHERE id = :id AND kind = 'ADMIN'")
                    .bind("id", id),
            ).getOrThrow().firstOrNull()?.toApiToken()
        }

    override suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean =
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(
                    "UPDATE identity_api_tokens SET revoked_at = :at WHERE id = :id AND org_id = :org " +
                        "AND kind = 'ADMIN' AND revoked_at IS NULL",
                ).bind("at", at).bind("id", id).bind("org", orgId),
            ).getOrThrow() == 1L
        }
}

private const val ELEVATED_COLUMNS: String =
    "id, user_id, org_id, name, kind, scopes, hint, agent_session_id, created_at, expires_at, last_used_at, " +
        "revoked_at, authenticated_at"

/**
 * An organization's live tokens of `ostrom login --admin` (`:org`, `:now`, `:limit`), newest first, read inside it
 * through the partial index `identity_api_tokens_elevated` (`ElevatedTokensQueryPlansTest`).
 */
internal const val LIVE_ELEVATED_TOKENS_SQL: String =
    "SELECT $ELEVATED_COLUMNS FROM identity_api_tokens WHERE org_id = :org AND kind = 'ADMIN' " +
        "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) ORDER BY created_at DESC, id LIMIT :limit"

/** The same, of one member only (`:user`): what a member who governs nothing sees of their own. */
internal const val LIVE_ELEVATED_TOKENS_OF_USER_SQL: String =
    "SELECT $ELEVATED_COLUMNS FROM identity_api_tokens WHERE org_id = :org AND kind = 'ADMIN' " +
        "AND user_id = :user AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) " +
        "ORDER BY created_at DESC, id LIMIT :limit"
