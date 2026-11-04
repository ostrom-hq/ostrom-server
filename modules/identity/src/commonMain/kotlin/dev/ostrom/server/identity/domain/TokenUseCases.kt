// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The token kinds a user creates and rotates, capped at [MAX_TOKENS_PER_USER]. The app's tokens are not among them:
 * they have their own cap ([MAX_PHONES_PER_USER]), and a phone signs in again instead of rotating (its unlock key).
 */
val USER_TOKEN_KINDS: Set<TokenKind> = setOf(TokenKind.PERSONAL, TokenKind.CLI)

/** The token kinds the console lists and revokes: a lost phone's token is revoked there. */
val LISTED_TOKEN_KINDS: Set<TokenKind> = USER_TOKEN_KINDS + TokenKind.MOBILE

/** How many characters of a secret are shown to recognize it (after its prefix). */
private const val HINT_LENGTH = 12

/**
 * Makes a token and its secret.
 *
 * @param secrets Makes the secret.
 * @param kind The kind, which prefixes the secret.
 * @param build Builds the token from its id and hint.
 *
 * @return The issued token.
 */
internal inline fun issue(
    secrets: SecretGenerator,
    kind: TokenKind,
    build: (id: Uuid, hint: String) -> ApiToken,
): IssuedToken {
    val secret = kind.prefix + "_" + secrets.secret()
    return IssuedToken(build(Uuid.random(), secret.take(HINT_LENGTH)), secret)
}

/** Creates a personal API token for the signed-in user. */
interface CreatePersonalTokenUseCase {
    /**
     * Creates the token.
     *
     * @param userId The owner.
     * @param name Its label.
     * @param scopes What it allows (at least one).
     * @param lifetime How long it lives (1 to 366 days), or null for a token that does not expire.
     *
     * @return The token and its secret, shown once.
     *
     * @throws InvalidTokenNameException, InvalidScopesException, InvalidTokenLifetimeException, TooManyTokensException
     */
    suspend operator fun invoke(
        userId: Uuid,
        name: String,
        scopes: Collection<String>,
        lifetime: Duration?,
    ): IssuedToken
}

/**
 * [CreatePersonalTokenUseCase] on the repository.
 *
 * @param tokens The tokens.
 * @param secrets Makes secrets.
 * @param hasher Hashes them.
 * @param audit The audit log.
 * @param clock The clock.
 */
class CreatePersonalTokenUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val audit: AuditLog,
    private val clock: Clock,
) : CreatePersonalTokenUseCase {
    override suspend fun invoke(
        userId: Uuid,
        name: String,
        scopes: Collection<String>,
        lifetime: Duration?,
    ): IssuedToken {
        val label = tokenName(name)
        val validScopes = validateScopes(scopes)
        if (lifetime != null && (lifetime < 1.days || lifetime > MAX_TOKEN_LIFETIME)) {
            throw InvalidTokenLifetimeException()
        }
        val now = clock.nowMillis()
        if (tokens.countLive(userId, USER_TOKEN_KINDS, now) >= MAX_TOKENS_PER_USER) throw TooManyTokensException()
        val issued = issue(secrets, TokenKind.PERSONAL) { id, hint ->
            val expiresAt = lifetime?.let { now + it }
            ApiToken(id, userId, null, label, TokenKind.PERSONAL, validScopes, hint, null, now, expiresAt, null, null)
        }
        tokens.create(issued.token, hasher.hash(issued.secret))
        val details = mapOf("scopes" to validScopes.sorted().joinToString(" "))
        audit.record(AuditEntry(null, userId, "token.created", "token", issued.token.id.toString(), details))
        return issued
    }
}

/** Lists the signed-in user's live personal and CLI tokens. */
interface ListTokensUseCase {
    /**
     * Lists the tokens.
     *
     * @param userId The owner.
     *
     * @return The live tokens, newest first.
     */
    suspend operator fun invoke(userId: Uuid): List<ApiToken>
}

/**
 * [ListTokensUseCase] on the repository.
 *
 * @param tokens The tokens.
 * @param clock The clock.
 */
class ListTokensUseCaseImpl(private val tokens: ApiTokensRepository, private val clock: Clock) : ListTokensUseCase {
    override suspend fun invoke(userId: Uuid): List<ApiToken> =
        tokens.listLive(userId, LISTED_TOKEN_KINDS, clock.now())
}

/** Revokes one of the user's tokens. */
interface RevokeTokenUseCase {
    /**
     * Revokes the token.
     *
     * @param userId The owner.
     * @param tokenId The token.
     * @param orgId The organization the token is bound to (`ostrom logout` of the token of `ostrom login --admin`),
     *   whose audit chain records it; null for an account-level token.
     *
     * @throws TokenNotFoundException When it is absent, another user's, or already revoked.
     */
    suspend operator fun invoke(userId: Uuid, tokenId: Uuid, orgId: Uuid? = null)
}

/**
 * [RevokeTokenUseCase] on the repository.
 *
 * @param tokens The tokens.
 * @param audit The audit log.
 * @param clock The clock.
 */
class RevokeTokenUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : RevokeTokenUseCase {
    override suspend fun invoke(userId: Uuid, tokenId: Uuid, orgId: Uuid?) {
        if (!tokens.revoke(userId, tokenId, clock.nowMillis(), orgId)) throw TokenNotFoundException()
        audit.record(AuditEntry(orgId, userId, "token.revoked", "token", tokenId.toString()))
    }
}

/**
 * Replaces a token's secret: a new token with the same name, scopes and lifetime, the old one revoked in the same
 * transaction. For a leaked secret, or a periodic rotation.
 */
interface RotateTokenUseCase {
    /**
     * Rotates the token.
     *
     * @param userId The owner.
     * @param tokenId The token to replace.
     *
     * @return The new token and its secret.
     *
     * @throws TokenNotFoundException When it is absent, another user's, an agent token, revoked or expired.
     */
    suspend operator fun invoke(userId: Uuid, tokenId: Uuid): IssuedToken
}

/**
 * [RotateTokenUseCase] on the repository.
 *
 * @param tokens The tokens.
 * @param secrets Makes secrets.
 * @param hasher Hashes them.
 * @param audit The audit log.
 * @param clock The clock.
 */
class RotateTokenUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val audit: AuditLog,
    private val clock: Clock,
) : RotateTokenUseCase {
    override suspend fun invoke(userId: Uuid, tokenId: Uuid): IssuedToken {
        val now = clock.nowMillis()
        val old = tokens.get(userId, tokenId)
        if (old == null || !old.isRotatableAt(now)) throw TokenNotFoundException()
        val issued = issue(secrets, old.kind) { id, hint ->
            old.copy(
                id = id, hint = hint, createdAt = now, lastUsedAt = null, revokedAt = null,
                expiresAt = old.expiresAt?.let { now + (it - old.createdAt) },
            )
        }
        val rotated = tokens.rotate(userId, tokenId, issued.token, hasher.hash(issued.secret), now)
        if (!rotated) throw TokenNotFoundException()
        val details = mapOf("replacement" to issued.token.id.toString())
        audit.record(AuditEntry(null, userId, "token.rotated", "token", tokenId.toString(), details))
        return issued
    }

    /** A live personal or CLI token: an agent or app token, a revoked or an expired one is never rotated. */
    private fun ApiToken.isRotatableAt(now: Instant): Boolean =
        kind in USER_TOKEN_KINDS && revokedAt == null && expiresAt?.let { it <= now } != true
}

/**
 * Issues the token of one agent session, for the sessions module (no route): its only right is acting on that
 * session (audit A2: it cannot declare a check passed for another session, close another's session, or reach the
 * console).
 */
interface IssueAgentTokenUseCase {
    /**
     * Issues the token.
     *
     * @param userId The user the agent works for.
     * @param orgId The organization of the session.
     * @param agentSessionId The agent session (1 to 128 characters).
     * @param lifetime How long it lives, at most [MAX_AGENT_TOKEN_LIFETIME].
     *
     * @return The token and its secret.
     *
     * @throws IllegalArgumentException When the session id or the lifetime is out of bounds (a caller's bug).
     */
    suspend operator fun invoke(userId: Uuid, orgId: Uuid, agentSessionId: String, lifetime: Duration): IssuedToken
}

/**
 * [IssueAgentTokenUseCase] on the repository.
 *
 * @param tokens The tokens.
 * @param secrets Makes secrets.
 * @param hasher Hashes them.
 * @param clock The clock.
 */
class IssueAgentTokenUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val clock: Clock,
) : IssueAgentTokenUseCase {
    override suspend fun invoke(userId: Uuid, orgId: Uuid, agentSessionId: String, lifetime: Duration): IssuedToken {
        require(agentSessionId.isNotBlank() && agentSessionId.length <= MAX_SESSION_ID) { "Invalid agent session id." }
        require(lifetime.isPositive() && lifetime <= MAX_AGENT_TOKEN_LIFETIME) { "Invalid agent token lifetime." }
        val now = clock.nowMillis()
        val issued = issue(secrets, TokenKind.AGENT) { id, hint ->
            val name = "agent $agentSessionId"
            val scopes = setOf(AGENT_SCOPE)
            val expiresAt = now + lifetime
            ApiToken(id, userId, orgId, name, TokenKind.AGENT, scopes, hint, agentSessionId, now, expiresAt, null, null)
        }
        tokens.create(issued.token, hasher.hash(issued.secret))
        return issued
    }

    private companion object {
        const val MAX_SESSION_ID = 128
    }
}

/** Revokes the tokens of an agent session when it ends, for the sessions module. */
interface RevokeAgentTokensUseCase {
    /**
     * Revokes them.
     *
     * @param agentSessionId The agent session.
     *
     * @return How many were revoked.
     */
    suspend operator fun invoke(agentSessionId: String): Long
}

/**
 * [RevokeAgentTokensUseCase] on the repository.
 *
 * @param tokens The tokens.
 * @param clock The clock.
 */
class RevokeAgentTokensUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val clock: Clock,
) : RevokeAgentTokensUseCase {
    override suspend fun invoke(agentSessionId: String): Long =
        tokens.revokeOfAgentSession(agentSessionId, clock.nowMillis())
}
