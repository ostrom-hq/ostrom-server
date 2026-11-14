// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.MAX_SERVICE_KEYS_PER_ORG
import run.ostrom.server.identity.domain.ServiceKeysRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ServiceKeysRepository] on PostgreSQL through Exposed: the `SERVICE` rows of `identity_api_tokens`, inside their
 * organization (the `identity_api_tokens_tenant` policy), as the serving role ([withOrganization]), each query
 * filtered on the organization too.
 *
 * @param database The JDBC pool.
 */
class ServiceKeysRepositoryImpl(private val database: JdbcDatabase) : ServiceKeysRepository {
    override suspend fun listLive(orgId: Uuid, now: Instant): List<ApiToken> =
        database.withOrganization(orgId) {
            liveServiceKeys(orgId, now, MAX_SERVICE_KEYS_PER_ORG).map { it.toApiToken(IdentityApiTokens) }
        }

    override suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean = database.withOrganization(orgId) {
        val t = IdentityApiTokens
        t.update({
            (t.id eq id) and (t.orgId eq orgId) and (t.kind eq SERVICE) and t.revokedAt.isNull() and
                (t.expiresAt.isNull() or (t.expiresAt greater at))
        }) { it[revokedAt] = at } == 1
    }
}

private const val SERVICE = "SERVICE"

/**
 * An organization's live service keys, newest first, read through the partial index `identity_api_tokens_service`
 * (`ServiceKeysQueryPlansTest`).
 *
 * @param orgId The organization.
 * @param now The time that decides which keys are expired.
 * @param limit The most keys read.
 */
internal fun liveServiceKeys(orgId: Uuid, now: Instant, limit: Int): Query {
    val t = IdentityApiTokens
    return t.select(t.read)
        .where {
            (t.orgId eq orgId) and (t.kind eq SERVICE) and t.revokedAt.isNull() and
                (t.expiresAt.isNull() or (t.expiresAt greater now))
        }
        .orderBy(t.createdAt to SortOrder.DESC, t.id to SortOrder.ASC)
        .limit(limit)
}
