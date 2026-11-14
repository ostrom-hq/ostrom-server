// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.MAX_LISTED_TOKENS
import run.ostrom.server.identity.domain.MAX_TOKENS_PER_USER
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.WebSession
import run.ostrom.server.identity.domain.WebSessionsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.PurgeDates
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WebSessionsRepository] on PostgreSQL through Exposed ([IdentityWebSessions]); no organization's rows.
 *
 * @param database The JDBC pool.
 */
class WebSessionsRepositoryImpl(private val database: JdbcDatabase) : WebSessionsRepository {
    private val t = IdentityWebSessions

    override suspend fun create(session: WebSession, secretHash: String) {
        database.query {
            t.insert {
                it[id] = session.id
                it[userId] = session.userId
                it[this.secretHash] = secretHash
                it[createdAt] = session.createdAt
                it[expiresAt] = session.expiresAt
                it[lastSeenAt] = session.lastSeenAt
            }
        }
    }

    override suspend fun findBySecretHash(secretHash: String): WebSession? = database.query {
        t.select(columns).where { t.secretHash eq secretHash }.singleOrNull()?.toWebSession()
    }

    override suspend fun touch(id: Uuid, at: Instant) {
        database.query { t.update({ t.id eq id }) { it[lastSeenAt] = at } }
    }

    override suspend fun revoke(id: Uuid, at: Instant) {
        database.query { t.update({ (t.id eq id) and t.revokedAt.isNull() }) { it[revokedAt] = at } }
    }

    override suspend fun revokeAllOfUser(userId: Uuid, at: Instant): Long = database.query {
        t.update({ (t.userId eq userId) and t.revokedAt.isNull() }) { it[revokedAt] = at }.toLong()
    }

    override suspend fun listLive(userId: Uuid, now: Instant, limit: Int): List<WebSession> = database.query {
        t.select(columns)
            .where { (t.userId eq userId) and t.revokedAt.isNull() and (t.expiresAt greater now) }
            .orderBy(t.lastSeenAt to SortOrder.DESC, t.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toWebSession() }
    }

    override suspend fun revokeOfUser(userId: Uuid, id: Uuid, at: Instant): Boolean = database.query {
        t.update({
            (t.id eq id) and (t.userId eq userId) and t.revokedAt.isNull() and (t.expiresAt greater at)
        }) { it[revokedAt] = at } == 1
    }

    /** What a read of a session selects: never its hash. */
    private val columns = listOf(t.id, t.userId, t.createdAt, t.expiresAt, t.lastSeenAt, t.revokedAt)

    private fun ResultRow.toWebSession() = WebSession(
        id = this[t.id],
        userId = this[t.userId],
        createdAt = this[t.createdAt],
        expiresAt = this[t.expiresAt],
        lastSeenAt = this[t.lastSeenAt],
        revokedAt = this[t.revokedAt],
    )
}

/**
 * [ApiTokensRepository] on PostgreSQL through Exposed ([IdentityApiTokens]). Scopes are stored space-separated (a
 * scope never contains a space).
 *
 * Outside any organization the serving role only sees the account-level tokens (personal and CLI, `org_id IS NULL`),
 * which a user lists, rotates and revokes. An organization's token (an agent token) is written inside its
 * organization, as the serving role ([withOrganization]: `ostrom_tenant` only reads the table); it is found by its
 * hash, marked as used and revoked with its agent session through the `identity_*` functions of `ostrom_router` (RLS
 * audit R1, docs/tenancy.md).
 *
 * @param database The JDBC pool.
 * @param clock Bounds the purge's date.
 */
class ApiTokensRepositoryImpl(
    private val database: JdbcDatabase,
    private val clock: Clock = Clock.System,
) : ApiTokensRepository {
    private val t = IdentityApiTokens

    override suspend fun create(token: ApiToken, secretHash: String) {
        val orgId = token.orgId
        if (orgId == null) {
            database.query { insertToken(token, secretHash) }
        } else {
            database.withOrganization(orgId) { insertToken(token, secretHash) }
        }
    }

    override suspend fun findBySecretHash(secretHash: String): ApiToken? = database.query {
        val found = TokenByHash(secretHash)
        found.select(found.read).firstOrNull()?.toApiToken(found)
    }

    override suspend fun get(userId: Uuid, id: Uuid): ApiToken? = database.query {
        t.select(t.read).where { (t.id eq id) and (t.userId eq userId) }.singleOrNull()?.toApiToken(t)
    }

    override suspend fun listLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): List<ApiToken> =
        database.query {
            t.select(t.read)
                .where { live(userId, kinds, now) }
                .orderBy(t.createdAt to SortOrder.DESC, t.id to SortOrder.ASC)
                .limit(MAX_LISTED_TOKENS)
                .map { it.toApiToken(t) }
        }

    // Counted over a page of at most MAX_TOKENS_PER_USER rows (`SELECT COUNT(*) FROM (… LIMIT n)`), never every row.
    override suspend fun countLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): Int = database.query {
        t.select(t.id).where { live(userId, kinds, now) }.limit(MAX_TOKENS_PER_USER).count().toInt()
    }

    override suspend fun touch(id: Uuid, at: Instant) {
        database.query { call("identity_token_touch", uuidParam(id), instantParam(at)) }
    }

    override suspend fun revoke(userId: Uuid, id: Uuid, at: Instant, orgId: Uuid?): Boolean {
        val mine = { (t.id eq id) and (t.userId eq userId) and t.revokedAt.isNull() and (t.kind neq AGENT) }
        val revoked = if (orgId == null) {
            database.query { t.update({ mine() }) { it[revokedAt] = at } }
        } else {
            database.withOrganization(orgId) { t.update({ mine() and (t.orgId eq orgId) }) { it[revokedAt] = at } }
        }
        return revoked == 1
    }

    override suspend fun rotate(
        userId: Uuid,
        oldId: Uuid,
        replacement: ApiToken,
        secretHash: String,
        at: Instant,
    ): Boolean = database.query {
        val revoked = t.update({
            (t.id eq oldId) and (t.userId eq userId) and t.revokedAt.isNull() and
                (t.expiresAt.isNull() or (t.expiresAt greater at))
        }) { it[revokedAt] = at } == 1
        if (revoked) insertToken(replacement, secretHash)
        revoked
    }

    override suspend fun revokeOfAgentSession(agentSessionId: String, at: Instant): Long = database.query {
        countOf("identity_revoke_agent_session_tokens", textParam(agentSessionId), instantParam(at))
    }

    // The one deletion across organizations, through the function of IdentityMigrations versions 14 and 15, which
    // bounds the date to the retention itself.
    override suspend fun purgeAgentTokens(before: Instant): Long {
        PurgeDates.check(before, clock.now())
        return database.query { countOf("identity_purge_agent_tokens", instantParam(before)) }
    }

    private companion object {
        const val AGENT = "AGENT"
    }
}

/** A user's live tokens (not revoked, not expired) of [kinds]. */
private fun live(userId: Uuid, kinds: Set<TokenKind>, now: Instant): Op<Boolean> {
    val t = IdentityApiTokens
    return (t.userId eq userId) and t.revokedAt.isNull() and (t.expiresAt.isNull() or (t.expiresAt greater now)) and
        (t.kind inList kinds.map { it.name })
}

/**
 * Inserts a token in this transaction.
 *
 * @param token The token.
 * @param secretHash The SHA-256 of its secret.
 * @param unlockKeyHash For the phone app's token, the SHA-256 of its unlock key; null for every other kind.
 */
internal fun JdbcTransaction.insertToken(token: ApiToken, secretHash: String, unlockKeyHash: String? = null) {
    IdentityApiTokens.insert {
        it[id] = token.id
        it[userId] = token.userId
        it[orgId] = token.orgId
        it[name] = token.name
        it[kind] = token.kind.name
        it[scopes] = token.scopes.sorted().joinToString(" ")
        it[hint] = token.hint
        it[agentSessionId] = token.agentSessionId
        it[this.secretHash] = secretHash
        it[createdAt] = token.createdAt
        it[expiresAt] = token.expiresAt
        it[this.unlockKeyHash] = unlockKeyHash
        it[authenticatedAt] = token.authenticatedAt
    }
}

/** Reads a token row of [t] (the table, or [TokenByHash]). */
internal fun ResultRow.toApiToken(t: TokenColumns) = ApiToken(
    id = this[t.id],
    userId = this[t.userId],
    orgId = this[t.orgId],
    name = this[t.name],
    kind = TokenKind.of(this[t.kind]),
    scopes = this[t.scopes].split(' ').toSet() - "",
    hint = this[t.hint],
    agentSessionId = this[t.agentSessionId],
    createdAt = this[t.createdAt],
    expiresAt = this[t.expiresAt],
    lastUsedAt = this[t.lastUsedAt],
    revokedAt = this[t.revokedAt],
    authenticatedAt = this[t.authenticatedAt],
)
