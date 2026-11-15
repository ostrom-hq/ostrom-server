// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubAccountInUseException
import run.ostrom.server.integrations.github.domain.GitHubIdentity
import run.ostrom.server.integrations.github.domain.GitHubLink
import run.ostrom.server.integrations.github.domain.GitHubLinkAttempt
import run.ostrom.server.integrations.github.domain.GitHubLinkKind
import run.ostrom.server.integrations.github.domain.GitHubLinkState
import run.ostrom.server.integrations.github.domain.GitHubLinkStore
import run.ostrom.server.integrations.github.domain.GitHubRevocable
import run.ostrom.server.integrations.github.domain.GitHubSavedLink
import run.ostrom.server.integrations.github.domain.GitHubTokenClaim
import run.ostrom.server.integrations.github.domain.GitHubTokens
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.database.write
import run.ostrom.server.secrets.domain.SecretCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GitHubLinkStore] on PostgreSQL. Every call is one transaction of the organization (`ostrom.org_id`), the only rows
 * row level security lets it see or write. The tokens of a link and the secret of an attempt are sealed with the
 * secrets module's cipher (AES-256-GCM with the instance's secrets key), bound to their organization and member
 * (`github-link:<org>:<member>`, `github-attempt:<org>:<id>:<member>`): an envelope moved to another row does not
 * open, and one that no longer opens (the secrets key changed) reads as a link that needs reconnecting.
 *
 * A claim locks the link's row (`FOR UPDATE`) for the time of its decision only: GitHub is called after the commit.
 * The tokens are opened before that transaction (opening is a suspend call, a transaction's block is not): the
 * decision runs only if the locked row still holds the envelope that was opened, else the claim reads it again.
 *
 * @param database The serving role's pool.
 * @param cipher Seals the tokens.
 */
@Suppress("TooManyFunctions") // One implementation of the port: its attempts, links and refresh claims.
class PostgresGitHubLinkStore(private val database: JdbcDatabase, private val cipher: SecretCipher) : GitHubLinkStore {
    override suspend fun startAttempt(attempt: GitHubLinkAttempt, now: Instant) {
        val context = attemptContext(attempt.orgId, attempt.id, attempt.memberId)
        val sealed = cipher.seal(attempt.secret.encodeToByteArray(), context)
        database.withOrganization(attempt.orgId) {
            val a = GitHubLinkAttempts
            // One pending attempt per member; the organization's expired ones go with it, so the table stays small.
            write { attemptsCleanup(attempt.orgId, attempt.memberId, now) }
            a.insert {
                it[orgId] = attempt.orgId
                it[id] = attempt.id
                it[memberId] = attempt.memberId
                it[kind] = attempt.kind.wire
                it[secretSealed] = sealed
                it[binding] = attempt.binding
                it[pollInterval] = attempt.pollIntervalSeconds
                it[nextPollAt] = attempt.nextPollAt
                it[expiresAt] = attempt.expiresAt
            }
        }
    }

    @Suppress("ReturnCount") // An attempt that is missing, unreadable or another row's is no attempt.
    override suspend fun attempt(orgId: Uuid, id: String): GitHubLinkAttempt? {
        val a = GitHubLinkAttempts
        val row = database.withOrganization(orgId) { attemptOf(orgId, id).firstOrNull() } ?: return null
        val memberId = row[a.memberId]
        val kind = GitHubLinkKind.entries.firstOrNull { it.wire == row[a.kind] } ?: return null
        val context = attemptContext(orgId, id, memberId)
        val opened = cipher.open(row[a.secretSealed], context) ?: return null
        val secret = opened.decodeToString()
        return GitHubLinkAttempt(
            id, orgId, memberId, kind, secret, row[a.binding], row[a.pollInterval],
            row[a.nextPollAt], row[a.expiresAt],
        )
    }

    override suspend fun consumeAttempt(orgId: Uuid, id: String): Boolean = database.withOrganization(orgId) {
        val a = GitHubLinkAttempts
        a.deleteWhere { (a.orgId eq orgId) and (a.id eq id) } == 1
    }

    override suspend fun deferPoll(
        orgId: Uuid,
        id: String,
        expectedNextPollAt: Instant?,
        intervalSeconds: Int,
        nextPollAt: Instant,
    ): Boolean = database.withOrganization(orgId) {
        val a = GitHubLinkAttempts
        // With an expected value, a compare-and-set: of two polls that read the same slot, one moves it.
        val slot = (a.orgId eq orgId) and (a.id eq id)
        val where = if (expectedNextPollAt == null) slot else slot and (a.nextPollAt eq expectedNextPollAt)
        a.update({ where }) {
            it[pollInterval] = intervalSeconds
            it[this.nextPollAt] = nextPollAt
        } == 1
    }

    override suspend fun saveLink(
        orgId: Uuid,
        memberId: Uuid,
        identity: GitHubIdentity,
        clientId: String,
        tokens: GitHubTokens,
        now: Instant,
    ): GitHubSavedLink {
        val sealed = seal(orgId, memberId, tokens)
        val (link, previous) = database.withOrganization(orgId) {
            val l = GitHubLinks
            // Serialized per organization: two members linking the same account at once never both pass the check.
            lockUntilCommit("github-links:$orgId")
            val holder = holderOf(orgId, identity.userId).firstOrNull()
            if (holder != null && holder[l.memberId] != memberId) throw GitHubAccountInUseException()
            val old = l.select(l.tokenVersion, l.clientId, l.tokensSealed)
                .where { (l.orgId eq orgId) and (l.memberId eq memberId) }
                .firstOrNull()
            val version = if (old == null) 0 else old[l.tokenVersion]
            l.deleteWhere { (l.orgId eq orgId) and (l.memberId eq memberId) }
            val saved = l.insertReturning(LINK_COLUMNS) {
                it[this.orgId] = orgId
                it[this.memberId] = memberId
                it[githubUserId] = identity.userId
                it[githubLogin] = identity.login
                it[this.clientId] = clientId
                it[tokensSealed] = sealed
                it[state] = GitHubLinkState.LINKED.wire
                it[accessExpiresAt] = tokens.accessExpiresAt
                it[refreshExpiresAt] = tokens.refreshExpiresAt
                it[tokenVersion] = version + 1
                it[refreshPending] = false
                it[refreshStartedAt] = null
                it[linkedAt] = now
                it[refreshedAt] = now
            }.first().toLink(orgId)
            saved to (if (old == null) null else old[l.clientId] to old[l.tokensSealed])
        }
        val replaced = previous?.let { (client, envelope) ->
            open(orgId, memberId, envelope)?.let { GitHubRevocable(client, it.accessToken) }
        }
        return GitHubSavedLink(link, replaced)
    }

    override suspend fun link(orgId: Uuid, memberId: Uuid): GitHubLink? = database.withOrganization(orgId) {
        val row = linkOf(orgId, memberId).firstOrNull()
        if (row == null) null else row.toLink(orgId)
    }

    override suspend fun links(orgId: Uuid, limit: Int): List<GitHubLink> =
        database.withOrganization(orgId) { linksOf(orgId, limit).map { it.toLink(orgId) } }

    @Suppress("ReturnCount") // No link, or a link whose tokens no longer open, revokes nothing.
    override suspend fun removeLink(orgId: Uuid, memberId: Uuid): GitHubRevocable? {
        val l = GitHubLinks
        val row = database.withOrganization(orgId) {
            val a = GitHubLinkAttempts
            a.deleteWhere { (a.orgId eq orgId) and (a.memberId eq memberId) }
            l.deleteReturning(listOf(l.clientId, l.tokensSealed), where = {
                (l.orgId eq orgId) and (l.memberId eq memberId)
            }).firstOrNull()
        } ?: return null
        val tokens = open(orgId, memberId, row[l.tokensSealed]) ?: return null
        return GitHubRevocable(row[l.clientId], tokens.accessToken)
    }

    // The members come from the function of `ostrom_router` (docs/tenancy.md); each link goes in its organization.
    override suspend fun removeLinksOfOtherClients(clientId: String): List<GitHubRevocable> {
        val f = GitHubLinksOfOtherClients
        val members = database.query {
            f.of(clientId).select(f.orgId, f.memberId).map { it[f.orgId] to it[f.memberId] }
        }
        val l = GitHubLinks
        return members.flatMap { (orgId, memberId) ->
            // Gone meanwhile (unlinked, or linked again with the kept client): nothing is removed.
            val removed = database.withOrganization(orgId) {
                l.deleteReturning(listOf(l.clientId, l.tokensSealed), where = {
                    (l.orgId eq orgId) and (l.memberId eq memberId) and (l.clientId neq clientId)
                }).toList()
            }
            removed.mapNotNull { row ->
                open(orgId, memberId, row[l.tokensSealed])?.let { GitHubRevocable(row[l.clientId], it.accessToken) }
            }
        }
    }

    override suspend fun claim(
        orgId: Uuid,
        memberId: Uuid,
        clientId: String,
        now: Instant,
        validUntil: Instant,
        usableUntil: Instant,
        staleAfter: Duration,
    ): GitHubTokenClaim {
        val l = GitHubLinks
        repeat(CLAIM_ATTEMPTS) {
            // Opened outside the transaction (a suspend call); the locked row decides only if it still holds this
            // envelope, else the tokens may have changed since and are read again.
            val envelope = database.withOrganization(orgId) {
                tokensOf(orgId, memberId).firstOrNull()?.get(l.tokensSealed)
            }
            val tokens = if (envelope == null) null else open(orgId, memberId, envelope)
            val claim = database.withOrganization(orgId) {
                val row = claimRead(orgId, memberId).firstOrNull()
                when {
                    row == null -> GitHubTokenClaim.NotLinked
                    row[l.tokensSealed] != envelope -> null
                    else -> decide(row, tokens, Window(orgId, clientId, now, validUntil, usableUntil, staleAfter))
                }
            }
            if (claim != null) return claim
        }
        // Tokens rewritten at every attempt: the caller waits and asks again, as for a refresh in flight.
        return GitHubTokenClaim.Wait
    }

    /** What a claim is asked for: the member's link and the times it decides by. */
    private class Window(
        val orgId: Uuid,
        val clientId: String,
        val now: Instant,
        val validUntil: Instant,
        val usableUntil: Instant,
        val staleAfter: Duration,
    )

    // Every state a link can be in, decided on the locked row with the tokens it was opened with.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun JdbcTransaction.decide(row: ResultRow, tokens: StoredTokens?, w: Window): GitHubTokenClaim {
        val l = GitHubLinks
        val link = row.toLink(w.orgId)
        if (link.state != GitHubLinkState.LINKED || link.clientId != w.clientId) {
            return GitHubTokenClaim.ReconnectRequired()
        }
        if (tokens == null) {
            // Sealed by another secrets key: unusable for good, so the member is told to link again.
            setState(w.orgId, link.memberId, GitHubLinkState.RECONNECT_REQUIRED)
            return GitHubTokenClaim.ReconnectRequired("tokens_unreadable")
        }
        val expiresAt = link.accessExpiresAt
        val startedAt = row[l.refreshStartedAt]
        val pending = row[l.refreshPending] && startedAt != null && startedAt + w.staleAfter > w.now
        val refreshToken = tokens.refreshToken?.takeIf { (link.refreshExpiresAt ?: Instant.DISTANT_FUTURE) > w.now }
        return when {
            // Another caller refreshes: its token if it lasts long enough, else wait for the new one.
            pending -> if (expiresAt == null || expiresAt > w.usableUntil) {
                GitHubTokenClaim.Ready(link, tokens.accessToken)
            } else {
                GitHubTokenClaim.Wait
            }
            expiresAt == null || expiresAt > w.validUntil -> GitHubTokenClaim.Ready(link, tokens.accessToken)
            // Nothing left to refresh with: the access token while it lasts, then the member links again.
            refreshToken == null -> if (expiresAt > w.now + LAST_MINUTE) {
                GitHubTokenClaim.Ready(link, tokens.accessToken)
            } else {
                setState(w.orgId, link.memberId, GitHubLinkState.RECONNECT_REQUIRED)
                GitHubTokenClaim.ReconnectRequired("refresh_expired")
            }
            else -> {
                // Committed before GitHub is called: a second replica waits instead of spending the same token. The
                // start time is read back as stored (microseconds), since it fences this claim's writes.
                val started = l.updateReturning(
                    listOf(l.refreshStartedAt),
                    where = { (l.orgId eq w.orgId) and (l.memberId eq link.memberId) },
                ) {
                    it[refreshPending] = true
                    it[refreshStartedAt] = w.now
                }.first().present(l.refreshStartedAt, l.refreshPending)
                GitHubTokenClaim.Refresh(link, tokens.accessToken, refreshToken, row[l.tokenVersion], started)
            }
        }
    }

    override suspend fun completeRefresh(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        tokens: GitHubTokens,
        now: Instant,
    ): Boolean {
        val sealed = seal(orgId, memberId, tokens)
        return database.withOrganization(orgId) {
            GitHubLinks.update({ owned(orgId, memberId, claim) }) {
                it[tokensSealed] = sealed
                it[accessExpiresAt] = tokens.accessExpiresAt
                it[refreshExpiresAt] = tokens.refreshExpiresAt
                it[tokenVersion] = claim.tokenVersion + 1
                it[refreshPending] = false
                it[refreshedAt] = now
            } == 1
        }
    }

    override suspend fun abandonRefresh(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        reconnect: Boolean,
    ): Boolean = database.withOrganization(orgId) {
        val state = if (reconnect) GitHubLinkState.RECONNECT_REQUIRED else GitHubLinkState.LINKED
        GitHubLinks.update({ owned(orgId, memberId, claim) }) {
            it[refreshPending] = false
            it[GitHubLinks.state] = state.wire
        } == 1 && reconnect
    }

    private fun JdbcTransaction.setState(orgId: Uuid, memberId: Uuid, state: GitHubLinkState) {
        val l = GitHubLinks
        l.update({ (l.orgId eq orgId) and (l.memberId eq memberId) }) {
            it[this.state] = state.wire
            it[refreshPending] = false
        }
    }

    private suspend fun seal(orgId: Uuid, memberId: Uuid, tokens: GitHubTokens): String = cipher.seal(
        Json.encodeToString(StoredTokens.serializer(), StoredTokens(tokens.accessToken, tokens.refreshToken))
            .encodeToByteArray(),
        linkContext(orgId, memberId),
    )

    private suspend fun open(orgId: Uuid, memberId: Uuid, envelope: String): StoredTokens? {
        val text = (cipher.open(envelope, linkContext(orgId, memberId)) ?: return null).decodeToString()
        return try {
            Json.decodeFromString(StoredTokens.serializer(), text)
        } catch (_: SerializationException) {
            // Opened by the key but not ours: as good as sealed by another key.
            null
        }
    }

    /**
     * The tokens of a link, as sealed.
     *
     * @property accessToken The access token.
     * @property refreshToken The refresh token.
     */
    @Serializable
    private class StoredTokens(val accessToken: String, val refreshToken: String? = null)

    private companion object {
        fun linkContext(orgId: Uuid, memberId: Uuid) = "github-link:$orgId:$memberId"

        fun attemptContext(orgId: Uuid, id: String, memberId: Uuid) = "github-attempt:$orgId:$id:$memberId"

        fun ResultRow.toLink(orgId: Uuid) = GitHubLink(
            orgId = orgId,
            memberId = this[GitHubLinks.memberId],
            identity = GitHubIdentity(this[GitHubLinks.githubUserId], this[GitHubLinks.githubLogin]),
            clientId = this[GitHubLinks.clientId],
            state = GitHubLinkState.of(this[GitHubLinks.state]),
            accessExpiresAt = this[GitHubLinks.accessExpiresAt],
            refreshExpiresAt = this[GitHubLinks.refreshExpiresAt],
            linkedAt = this[GitHubLinks.linkedAt],
            refreshedAt = this[GitHubLinks.refreshedAt],
        )
    }
}

/** Only the current refresh owner writes: a takeover (new start time) or a relink (new version) fences it. */
private fun owned(orgId: Uuid, memberId: Uuid, claim: GitHubTokenClaim.Refresh): Op<Boolean> {
    val l = GitHubLinks
    return (l.orgId eq orgId) and (l.memberId eq memberId) and (l.tokenVersion eq claim.tokenVersion) and
        (l.refreshPending eq true) and (l.refreshStartedAt eq claim.startedAt)
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val LINK_COLUMNS = listOf(
    GitHubLinks.memberId, GitHubLinks.githubUserId, GitHubLinks.githubLogin, GitHubLinks.clientId, GitHubLinks.state,
    GitHubLinks.accessExpiresAt, GitHubLinks.refreshExpiresAt, GitHubLinks.linkedAt, GitHubLinks.refreshedAt,
)

/** A member's link. */
internal fun linkOf(orgId: Uuid, memberId: Uuid): Query {
    val l = GitHubLinks
    return l.select(LINK_COLUMNS).where { (l.orgId eq orgId) and (l.memberId eq memberId) }
}

/** The sealed tokens of a member's link, read before a claim opens them. */
internal fun tokensOf(orgId: Uuid, memberId: Uuid): Query {
    val l = GitHubLinks
    return l.select(l.tokensSealed).where { (l.orgId eq orgId) and (l.memberId eq memberId) }
}

/** A member's link locked for a claim's decision. */
internal fun claimRead(orgId: Uuid, memberId: Uuid): Query {
    val l = GitHubLinks
    return l.select(l.columns).where { (l.orgId eq orgId) and (l.memberId eq memberId) }
        .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate())
}

/** The member of the organization who holds a GitHub account. */
internal fun holderOf(orgId: Uuid, githubUserId: Long): Query {
    val l = GitHubLinks
    return l.select(l.memberId).where { (l.orgId eq orgId) and (l.githubUserId eq githubUserId) }
}

/** A pending attempt. */
internal fun attemptOf(orgId: Uuid, id: String): Query {
    val a = GitHubLinkAttempts
    return a.select(a.columns).where { (a.orgId eq orgId) and (a.id eq id) }
}

/** The attempts a new one replaces: the member's own and the organization's expired ones. */
internal fun StatementBuilder.attemptsCleanup(orgId: Uuid, memberId: Uuid, now: Instant): DeleteStatement {
    val a = GitHubLinkAttempts
    return a.deleteWhere { (a.orgId eq orgId) and ((a.memberId eq memberId) or (a.expiresAt lessEq now)) }
}

/** The links of an organization, by login, [limit] at most. */
internal fun linksOf(orgId: Uuid, limit: Int): Query {
    val l = GitHubLinks
    return l.select(LINK_COLUMNS).where { l.orgId eq orgId }
        .orderBy(l.githubLogin to SortOrder.ASC, l.memberId to SortOrder.ASC).limit(limit)
}

/** Within this time of its expiry, a token that cannot be refreshed is no longer handed out. */
private val LAST_MINUTE = 1.minutes

// The reads of a claim whose tokens another write keeps replacing, before it waits like a refresh in flight.
private const val CLAIM_ATTEMPTS = 3
