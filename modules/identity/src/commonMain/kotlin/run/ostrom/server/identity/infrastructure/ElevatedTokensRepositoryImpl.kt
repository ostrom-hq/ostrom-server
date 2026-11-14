// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ElevatedTokensRepository
import run.ostrom.server.identity.domain.MAX_TOKENS_PER_USER
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.Op
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
 * [ElevatedTokensRepository] on PostgreSQL through Exposed: the `ADMIN` rows of `identity_api_tokens`, inside their
 * organization (the `identity_api_tokens_tenant` policy), as the serving role ([withOrganization]), each query
 * filtered on the organization too. No function of `ostrom_router` reaches them from outside it.
 *
 * @param database The JDBC pool.
 */
class ElevatedTokensRepositoryImpl(private val database: JdbcDatabase) : ElevatedTokensRepository {
    private val t = IdentityApiTokens

    override suspend fun listLive(orgId: Uuid, userId: Uuid?, now: Instant): List<ApiToken> =
        database.withOrganization(orgId) {
            liveElevatedTokens(orgId, userId, now, MAX_TOKENS_PER_USER).map { it.toApiToken(t) }
        }

    override suspend fun get(orgId: Uuid, id: Uuid): ApiToken? = database.withOrganization(orgId) {
        t.select(t.read).where { (t.id eq id) and (t.orgId eq orgId) and (t.kind eq ADMIN) }.singleOrNull()
            ?.toApiToken(t)
    }

    override suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean = database.withOrganization(orgId) {
        t.update({ (t.id eq id) and (t.orgId eq orgId) and (t.kind eq ADMIN) and t.revokedAt.isNull() }) {
            it[revokedAt] = at
        } == 1
    }
}

private const val ADMIN = "ADMIN"

/**
 * An organization's live tokens of `ostrom login --admin`, newest first, read inside it through the partial index
 * `identity_api_tokens_elevated` (`ElevatedTokensQueryPlansTest`); of one member only when [userId] is given (what a
 * member who governs nothing sees of their own).
 *
 * @param orgId The organization.
 * @param userId Their owner, or null for every member's.
 * @param now The time that decides which tokens are expired.
 * @param limit The most tokens read.
 */
internal fun liveElevatedTokens(orgId: Uuid, userId: Uuid?, now: Instant, limit: Int): Query {
    val t = IdentityApiTokens
    val owner: Op<Boolean> = if (userId == null) Op.TRUE else t.userId eq userId
    return t.select(t.read)
        .where {
            (t.orgId eq orgId) and (t.kind eq ADMIN) and owner and t.revokedAt.isNull() and
                (t.expiresAt.isNull() or (t.expiresAt greater now))
        }
        .orderBy(t.createdAt to SortOrder.DESC, t.id to SortOrder.ASC)
        .limit(limit)
}
