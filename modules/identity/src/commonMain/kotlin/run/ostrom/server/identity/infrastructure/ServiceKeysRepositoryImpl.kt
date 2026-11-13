// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.MAX_SERVICE_KEYS_PER_ORG
import run.ostrom.server.identity.domain.ServiceKeysRepository
import run.ostrom.server.kernel.database.withOrganization
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ServiceKeysRepository] on PostgreSQL: the `SERVICE` rows of `identity_api_tokens`, inside their organization (the
 * `identity_api_tokens_tenant` policy).
 *
 * @param driver The database.
 */
class ServiceKeysRepositoryImpl(private val driver: Driver) : ServiceKeysRepository {
    override suspend fun listLive(orgId: Uuid, now: Instant): List<ApiToken> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(LIVE_SERVICE_KEYS_SQL).bind("org", orgId).bind("now", now)
                    .bind("limit", MAX_SERVICE_KEYS_PER_ORG),
            ).getOrThrow().map { it.toApiToken() }
        }

    override suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean =
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(
                    "UPDATE identity_api_tokens SET revoked_at = :at WHERE id = :id AND org_id = :org " +
                        "AND kind = 'SERVICE' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :at)",
                ).bind("at", at).bind("id", id).bind("org", orgId),
            ).getOrThrow() == 1L
        }
}

/**
 * An organization's live service keys (`:org`, `:now`, `:limit`), newest first, read through the partial index
 * `identity_api_tokens_service` (`ServiceKeysQueryPlansTest`).
 */
internal const val LIVE_SERVICE_KEYS_SQL: String =
    "SELECT id, user_id, org_id, name, kind, scopes, hint, agent_session_id, created_at, expires_at, last_used_at, " +
        "revoked_at, authenticated_at FROM identity_api_tokens WHERE org_id = :org AND kind = 'SERVICE' " +
        "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) ORDER BY created_at DESC, id LIMIT :limit"
