// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubAccountInUseException
import run.ostrom.server.integrations.github.domain.GitHubIdentity
import run.ostrom.server.integrations.github.domain.GitHubLink
import run.ostrom.server.integrations.github.domain.GitHubLinkAttempt
import run.ostrom.server.integrations.github.domain.GitHubLinkKind
import run.ostrom.server.integrations.github.domain.GitHubLinkState
import run.ostrom.server.integrations.github.domain.GitHubLinkStore
import run.ostrom.server.integrations.github.domain.GitHubRevocable
import run.ostrom.server.integrations.github.domain.GitHubTokenClaim
import run.ostrom.server.integrations.github.domain.GitHubTokens
import run.ostrom.server.secrets.domain.SecretCipher
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
 *
 * @param driver The connection pool.
 * @param cipher Seals the tokens.
 */
@Suppress("TooManyFunctions") // One implementation of the port: its attempts, links and refresh claims.
class PostgresGitHubLinkStore(private val driver: Driver, private val cipher: SecretCipher) : GitHubLinkStore {
    private suspend fun <T> scoped(orgId: Uuid, block: suspend Tx.() -> T): T = driver.transaction {
        val tx = Tx(this)
        tx.fetch(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()))
        tx.block()
    }

    override suspend fun startAttempt(attempt: GitHubLinkAttempt, now: Instant) {
        val context = attemptContext(attempt.orgId, attempt.id, attempt.memberId)
        val sealed = cipher.seal(attempt.secret.encodeToByteArray(), context)
        scoped(attempt.orgId) {
            // One pending attempt per member; the organization's expired ones go with it, so the table stays small.
            execute(
                Statement.create(
                    "DELETE FROM github_link_attempts WHERE org_id = :org " +
                        "AND (member_id = :member OR expires_at <= :now)",
                ).bind("org", attempt.orgId).bind("member", attempt.memberId).bind("now", now),
            )
            execute(
                Statement.create(
                    "INSERT INTO github_link_attempts (org_id, id, member_id, kind, secret_sealed, binding, " +
                        "poll_interval, next_poll_at, expires_at) VALUES (:org, :id, :member, :kind, :secret, " +
                        ":binding, :interval, :next, :expires)",
                ).bind("org", attempt.orgId).bind("id", attempt.id).bind("member", attempt.memberId)
                    .bind("kind", attempt.kind.wire).bind("secret", sealed).bind("binding", attempt.binding)
                    .bind("interval", attempt.pollIntervalSeconds).bind("next", attempt.nextPollAt)
                    .bind("expires", attempt.expiresAt),
            )
        }
    }

    override suspend fun attempt(orgId: Uuid, id: String): GitHubLinkAttempt? {
        val row = scoped(orgId) {
            fetch(
                Statement.create(
                    "SELECT member_id, kind, secret_sealed, binding, poll_interval, next_poll_at, expires_at " +
                        "FROM github_link_attempts WHERE org_id = :org AND id = :id",
                ).bind("org", orgId).bind("id", id),
            ).firstOrNull()
        } ?: return null
        val memberId = row.get("member_id").asUuid()
        val kind = GitHubLinkKind.entries.firstOrNull { it.wire == row.get("kind").asString() } ?: return null
        val secret = cipher.open(row.get("secret_sealed").asString(), attemptContext(orgId, id, memberId))
            ?.decodeToString() ?: return null
        return GitHubLinkAttempt(
            id, orgId, memberId, kind, secret, row.get("binding").asStringOrNull(), row.get("poll_interval").asInt(),
            row.get("next_poll_at").asInstant(), row.get("expires_at").asInstant(),
        )
    }

    override suspend fun consumeAttempt(orgId: Uuid, id: String): Boolean = scoped(orgId) {
        execute(
            Statement.create("DELETE FROM github_link_attempts WHERE org_id = :org AND id = :id")
                .bind("org", orgId).bind("id", id),
        ) == 1L
    }

    override suspend fun deferPoll(
        orgId: Uuid,
        id: String,
        expectedNextPollAt: Instant?,
        intervalSeconds: Int,
        nextPollAt: Instant,
    ): Boolean = scoped(orgId) {
        // With an expected value, a compare-and-set: of two polls that read the same slot, one moves it.
        val statement = if (expectedNextPollAt == null) {
            Statement.create(
                "UPDATE github_link_attempts SET poll_interval = :interval, next_poll_at = :next " +
                    "WHERE org_id = :org AND id = :id",
            )
        } else {
            Statement.create(
                "UPDATE github_link_attempts SET poll_interval = :interval, next_poll_at = :next " +
                    "WHERE org_id = :org AND id = :id AND next_poll_at = :expected",
            ).bind("expected", expectedNextPollAt)
        }
        val bound = statement.bind("interval", intervalSeconds).bind("next", nextPollAt)
        execute(bound.bind("org", orgId).bind("id", id)) == 1L
    }

    override suspend fun saveLink(
        orgId: Uuid,
        memberId: Uuid,
        identity: GitHubIdentity,
        clientId: String,
        tokens: GitHubTokens,
        now: Instant,
    ): GitHubLink {
        val sealed = seal(orgId, memberId, tokens)
        return scoped(orgId) {
            // Serialized per organization: two members linking the same account at once never both pass the check.
            lock("github-links:$orgId")
            val holder = fetch(
                Statement.create("SELECT member_id FROM github_links WHERE org_id = :org AND github_user_id = :user")
                    .bind("org", orgId).bind("user", identity.userId),
            ).firstOrNull()?.get("member_id")?.asUuid()
            if (holder != null && holder != memberId) throw GitHubAccountInUseException()
            val version = fetch(
                Statement.create("SELECT token_version FROM github_links WHERE org_id = :org AND member_id = :member")
                    .bind("org", orgId).bind("member", memberId),
            ).firstOrNull()?.get("token_version")?.asInt() ?: 0
            execute(
                Statement.create("DELETE FROM github_links WHERE org_id = :org AND member_id = :member")
                    .bind("org", orgId).bind("member", memberId),
            )
            fetch(
                Statement.create(
                    "INSERT INTO github_links (org_id, member_id, github_user_id, github_login, client_id, " +
                        "tokens_sealed, state, access_expires_at, refresh_expires_at, token_version, " +
                        "refresh_pending, refresh_started_at, linked_at, refreshed_at) VALUES (:org, :member, :user, " +
                        ":login, :client, :tokens, :state, :access, :refresh, :version, FALSE, NULL, :now, :now) " +
                        "RETURNING $LINK_COLUMNS",
                ).bind("org", orgId).bind("member", memberId).bind("user", identity.userId)
                    .bind("login", identity.login).bind("client", clientId).bind("tokens", sealed)
                    .bind("state", GitHubLinkState.LINKED.wire).bind("access", tokens.accessExpiresAt)
                    .bind("refresh", tokens.refreshExpiresAt).bind("version", version + 1).bind("now", now),
            ).first().toLink(orgId)
        }
    }

    override suspend fun link(orgId: Uuid, memberId: Uuid): GitHubLink? = scoped(orgId) {
        fetch(
            Statement.create("SELECT $LINK_COLUMNS FROM github_links WHERE org_id = :org AND member_id = :member")
                .bind("org", orgId).bind("member", memberId),
        ).firstOrNull()?.toLink(orgId)
    }

    override suspend fun links(orgId: Uuid, limit: Int): List<GitHubLink> = scoped(orgId) {
        fetch(
            Statement.create(
                "SELECT $LINK_COLUMNS FROM github_links WHERE org_id = :org " +
                    "ORDER BY github_login, member_id LIMIT :limit",
            ).bind("org", orgId).bind("limit", limit),
        ).map { it.toLink(orgId) }
    }

    override suspend fun removeLink(orgId: Uuid, memberId: Uuid): GitHubRevocable? {
        val row = scoped(orgId) {
            execute(
                Statement.create("DELETE FROM github_link_attempts WHERE org_id = :org AND member_id = :member")
                    .bind("org", orgId).bind("member", memberId),
            )
            fetch(
                Statement.create(
                    "DELETE FROM github_links WHERE org_id = :org AND member_id = :member " +
                        "RETURNING client_id, tokens_sealed",
                ).bind("org", orgId).bind("member", memberId),
            ).firstOrNull()
        } ?: return null
        val tokens = open(orgId, memberId, row.get("tokens_sealed").asString()) ?: return null
        return GitHubRevocable(row.get("client_id").asString(), tokens.accessToken)
    }

    override suspend fun claim(
        orgId: Uuid,
        memberId: Uuid,
        clientId: String,
        now: Instant,
        validUntil: Instant,
        usableUntil: Instant,
        staleAfter: Duration,
    ): GitHubTokenClaim = scoped(orgId) {
        val row = fetch(
            Statement.create(
                "SELECT $LINK_COLUMNS, tokens_sealed, token_version, refresh_pending, refresh_started_at " +
                    "FROM github_links WHERE org_id = :org AND member_id = :member FOR UPDATE",
            ).bind("org", orgId).bind("member", memberId),
        ).firstOrNull() ?: return@scoped GitHubTokenClaim.NotLinked
        val link = row.toLink(orgId)
        val tokens = open(orgId, memberId, row.get("tokens_sealed").asString())
        if (link.state != GitHubLinkState.LINKED || link.clientId != clientId || tokens == null) {
            return@scoped GitHubTokenClaim.ReconnectRequired()
        }
        val expiresAt = link.accessExpiresAt
        val startedAt = row.get("refresh_started_at").asInstantOrNull()
        val pending = row.get("refresh_pending").asBoolean() && startedAt != null && startedAt + staleAfter > now
        val refreshToken = tokens.refreshToken?.takeIf { (link.refreshExpiresAt ?: Instant.DISTANT_FUTURE) > now }
        when {
            // Another caller refreshes: its token if it lasts long enough, else wait for the new one.
            pending -> if (expiresAt == null || expiresAt > usableUntil) {
                GitHubTokenClaim.Ready(link, tokens.accessToken)
            } else {
                GitHubTokenClaim.Wait
            }
            expiresAt == null || expiresAt > validUntil -> GitHubTokenClaim.Ready(link, tokens.accessToken)
            // Nothing left to refresh with: the access token while it lasts, then the member links again.
            refreshToken == null -> if (expiresAt > now + LAST_MINUTE) {
                GitHubTokenClaim.Ready(link, tokens.accessToken)
            } else {
                setState(orgId, memberId, GitHubLinkState.RECONNECT_REQUIRED)
                GitHubTokenClaim.ReconnectRequired(endedNow = true)
            }
            else -> {
                // Committed before GitHub is called: a second replica waits instead of spending the same token. The
                // start time is read back as stored (microseconds), since it fences this claim's writes.
                val started = fetch(
                    Statement.create(
                        "UPDATE github_links SET refresh_pending = TRUE, refresh_started_at = :now " +
                            "WHERE org_id = :org AND member_id = :member RETURNING refresh_started_at",
                    ).bind("now", now).bind("org", orgId).bind("member", memberId),
                ).first().get("refresh_started_at").asInstant()
                val version = row.get("token_version").asInt()
                GitHubTokenClaim.Refresh(link, tokens.accessToken, refreshToken, version, started)
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
        return scoped(orgId) {
            execute(
                owned(
                    "UPDATE github_links SET tokens_sealed = :tokens, access_expires_at = :access, " +
                        "refresh_expires_at = :refresh, token_version = :next, refresh_pending = FALSE, " +
                        "refreshed_at = :now",
                    orgId,
                    memberId,
                    claim,
                ).bind("tokens", sealed).bind("access", tokens.accessExpiresAt).bind("refresh", tokens.refreshExpiresAt)
                    .bind("next", claim.tokenVersion + 1).bind("now", now),
            ) == 1L
        }
    }

    override suspend fun abandonRefresh(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        reconnect: Boolean,
    ): Boolean = scoped(orgId) {
        val state = if (reconnect) GitHubLinkState.RECONNECT_REQUIRED else GitHubLinkState.LINKED
        execute(
            owned("UPDATE github_links SET refresh_pending = FALSE, state = :state", orgId, memberId, claim)
                .bind("state", state.wire),
        ) == 1L && reconnect
    }

    private suspend fun Tx.setState(orgId: Uuid, memberId: Uuid, state: GitHubLinkState) {
        execute(
            Statement.create(
                "UPDATE github_links SET state = :state, refresh_pending = FALSE " +
                    "WHERE org_id = :org AND member_id = :member",
            ).bind("state", state.wire).bind("org", orgId).bind("member", memberId),
        )
    }

    /** Only the current refresh owner writes: a takeover (new start time) or a relink (new version) fences it. */
    private fun owned(set: String, orgId: Uuid, memberId: Uuid, claim: GitHubTokenClaim.Refresh): Statement =
        Statement.create(
            "$set WHERE org_id = :org AND member_id = :member AND token_version = :version " +
                "AND refresh_pending AND refresh_started_at = :started",
        ).bind("org", orgId).bind("member", memberId).bind("version", claim.tokenVersion)
            .bind("started", claim.startedAt)

    private suspend fun seal(orgId: Uuid, memberId: Uuid, tokens: GitHubTokens): String = cipher.seal(
        Json.encodeToString(StoredTokens.serializer(), StoredTokens(tokens.accessToken, tokens.refreshToken))
            .encodeToByteArray(),
        linkContext(orgId, memberId),
    )

    private suspend fun open(orgId: Uuid, memberId: Uuid, envelope: String): StoredTokens? {
        val text = cipher.open(envelope, linkContext(orgId, memberId))?.decodeToString() ?: return null
        return try {
            Json.decodeFromString(StoredTokens.serializer(), text)
        } catch (_: SerializationException) {
            // Opened by the key but not ours: as good as sealed by another key.
            null
        }
    }

    private class Tx(private val executor: QueryExecutor) {
        suspend fun fetch(statement: Statement): List<ResultSet.Row> =
            executor.fetchAll(statement).getOrThrow().toList()

        suspend fun execute(statement: Statement): Long = executor.execute(statement).getOrThrow()

        suspend fun lock(key: String) {
            fetch(
                Statement.create("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS l")
                    .bind("key", key),
            )
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
        const val LINK_COLUMNS = "member_id, github_user_id, github_login, client_id, state, access_expires_at, " +
            "refresh_expires_at, linked_at, refreshed_at"

        fun linkContext(orgId: Uuid, memberId: Uuid) = "github-link:$orgId:$memberId"

        fun attemptContext(orgId: Uuid, id: String, memberId: Uuid) = "github-attempt:$orgId:$id:$memberId"

        fun ResultSet.Row.toLink(orgId: Uuid) = GitHubLink(
            orgId = orgId,
            memberId = get("member_id").asUuid(),
            identity = GitHubIdentity(get("github_user_id").asLong(), get("github_login").asString()),
            clientId = get("client_id").asString(),
            state = GitHubLinkState.of(get("state").asString()),
            accessExpiresAt = get("access_expires_at").asInstantOrNull(),
            refreshExpiresAt = get("refresh_expires_at").asInstantOrNull(),
            linkedAt = get("linked_at").asInstant(),
            refreshedAt = get("refreshed_at").asInstant(),
        )
    }
}

/** Within this time of its expiry, a token that cannot be refreshed is no longer handed out. */
private val LAST_MINUTE = 1.minutes
