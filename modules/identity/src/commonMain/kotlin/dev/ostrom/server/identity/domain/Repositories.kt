// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Users, their e-mails and the external identities they sign in with. */
interface UsersRepository {
    /**
     * Reads a user.
     *
     * @param id The user id.
     *
     * @return The user, or null when absent.
     */
    suspend fun get(id: Uuid): User?

    /**
     * Finds the user an external identity belongs to.
     *
     * @param issuer The scope of the subject ([VerifiedIdentity.issuer]).
     * @param subject The provider's id of the person.
     *
     * @return The user, or null when this identity was never seen.
     */
    suspend fun findByIdentity(issuer: String, subject: String): User?

    /**
     * Finds the user who owns a verified e-mail.
     *
     * @param email The normalized address.
     *
     * @return The user, or null when no user verified it.
     */
    suspend fun findByVerifiedEmail(email: String): User?

    /**
     * Whether a login is already used.
     *
     * @param login The login.
     *
     * @return True when a user has it.
     */
    suspend fun loginTaken(login: String): Boolean

    /**
     * Finds a user by login.
     *
     * @param login The login, any case (logins are stored in lower case).
     *
     * @return The user, or null.
     */
    suspend fun findByLogin(login: String): User?

    /**
     * Reads several users at once.
     *
     * @param ids The users.
     *
     * @return The users found, in no particular order.
     */
    suspend fun getMany(ids: Collection<Uuid>): List<User>

    /**
     * Creates a user with their first identity and e-mail, in one transaction.
     *
     * @param user The user.
     * @param identity The identity they signed in with.
     * @param email Their first e-mail, if the identity gave one.
     * @param at When.
     *
     * @throws io.github.smyrgeorge.sqlx4k.SQLError A unique violation when another sign-in created the same identity
     *   or verified the same e-mail at the same moment (the caller retries once).
     */
    suspend fun create(user: User, identity: VerifiedIdentity, email: UserEmail?, at: Instant)

    /**
     * Attaches an identity (and its e-mail) to an existing user. An e-mail already present becomes verified if the
     * identity verified it; never the other way round.
     *
     * @param userId The user.
     * @param identity The identity.
     * @param email The e-mail the identity gave, if any.
     * @param at When.
     */
    suspend fun link(userId: Uuid, identity: VerifiedIdentity, email: UserEmail?, at: Instant)

    /**
     * Lists a user's e-mails.
     *
     * @param userId The user.
     *
     * @return The e-mails, verified first, then alphabetically.
     */
    suspend fun listEmails(userId: Uuid): List<UserEmail>

    /**
     * Lists the providers a user can sign in with.
     *
     * @param userId The user.
     *
     * @return The issuers ([VerifiedIdentity.issuer]), sorted.
     */
    suspend fun listIssuers(userId: Uuid): List<String>
}

/** Browser sessions. */
interface WebSessionsRepository {
    /**
     * Stores a new session.
     *
     * @param session The session.
     * @param secretHash The SHA-256 of its cookie value.
     */
    suspend fun create(session: WebSession, secretHash: String)

    /**
     * Finds a session by the hash of its cookie value.
     *
     * @param secretHash The hash.
     *
     * @return The session, revoked or expired ones included, or null.
     */
    suspend fun findBySecretHash(secretHash: String): WebSession?

    /**
     * Records a use.
     *
     * @param id The session.
     * @param at When.
     */
    suspend fun touch(id: Uuid, at: Instant)

    /**
     * Revokes a session (sign-out).
     *
     * @param id The session.
     * @param at When.
     */
    suspend fun revoke(id: Uuid, at: Instant)

    /**
     * Revokes every live session of a user (sign out everywhere).
     *
     * @param userId The user.
     * @param at When.
     *
     * @return How many sessions were revoked.
     */
    suspend fun revokeAllOfUser(userId: Uuid, at: Instant): Long

    /**
     * Lists the live sessions of a user (neither revoked nor expired), the last used first.
     *
     * @param userId The user.
     * @param now The time that decides which sessions are expired.
     * @param limit The most sessions returned.
     *
     * @return The sessions.
     */
    suspend fun listLive(userId: Uuid, now: Instant, limit: Int): List<WebSession>

    /**
     * Revokes one live session of a user (sign out of that browser).
     *
     * @param userId The user: a session of anyone else is never revoked.
     * @param id The session.
     * @param at When, also the time that decides whether it is expired.
     *
     * @return Whether a live session of the user was revoked.
     */
    suspend fun revokeOfUser(userId: Uuid, id: Uuid, at: Instant): Boolean
}

/** API tokens. */
interface ApiTokensRepository {
    /**
     * Stores a new token.
     *
     * @param token The token.
     * @param secretHash The SHA-256 of its secret.
     */
    suspend fun create(token: ApiToken, secretHash: String)

    /**
     * Finds a token by the hash of its secret.
     *
     * @param secretHash The hash.
     *
     * @return The token, revoked or expired ones included, or null.
     */
    suspend fun findBySecretHash(secretHash: String): ApiToken?

    /**
     * Reads a token of a user.
     *
     * @param userId The owner.
     * @param id The token.
     *
     * @return The token, or null when absent or another user's.
     */
    suspend fun get(userId: Uuid, id: Uuid): ApiToken?

    /**
     * Lists a user's live tokens (not revoked, not expired) of the given kinds.
     *
     * @param userId The owner.
     * @param kinds The kinds to list.
     * @param now The current time, for expiry.
     *
     * @return At most [MAX_TOKENS_PER_USER] tokens, newest first.
     */
    suspend fun listLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): List<ApiToken>

    /**
     * Counts a user's live tokens of the given kinds, up to [MAX_TOKENS_PER_USER].
     *
     * @param userId The owner.
     * @param kinds The kinds.
     * @param now The current time.
     *
     * @return The count, capped.
     */
    suspend fun countLive(userId: Uuid, kinds: Set<TokenKind>, now: Instant): Int

    /**
     * Records a use.
     *
     * @param id The token.
     * @param at When.
     */
    suspend fun touch(id: Uuid, at: Instant)

    /**
     * Revokes a token of a user.
     *
     * @param userId The owner.
     * @param id The token.
     * @param at When.
     *
     * @return True when a live token was revoked.
     */
    suspend fun revoke(userId: Uuid, id: Uuid, at: Instant): Boolean

    /**
     * Replaces a token by a new one in one transaction: the old one is revoked only if it was still live.
     *
     * @param userId The owner.
     * @param oldId The token to replace.
     * @param replacement The new token.
     * @param secretHash The SHA-256 of the new secret.
     * @param at When.
     *
     * @return True when the old token was live and is now replaced; false (and nothing written) otherwise.
     */
    suspend fun rotate(userId: Uuid, oldId: Uuid, replacement: ApiToken, secretHash: String, at: Instant): Boolean

    /**
     * Revokes the agent tokens of an agent session (when it ends).
     *
     * @param agentSessionId The agent session.
     * @param at When.
     *
     * @return How many tokens were revoked.
     */
    suspend fun revokeOfAgentSession(agentSessionId: String, at: Instant): Long
}

/**
 * An organization's service keys: the `SERVICE` tokens of [ApiTokensRepository], read and revoked inside their
 * organization (they are created with [ApiTokensRepository.create], found by hash like any token).
 */
interface ServiceKeysRepository {
    /**
     * Lists an organization's live service keys (not revoked, not expired), inside that organization.
     *
     * @param orgId The organization.
     * @param now The current time, for expiry.
     *
     * @return At most [MAX_SERVICE_KEYS_PER_ORG] keys, newest first.
     */
    suspend fun listLive(orgId: Uuid, now: Instant): List<ApiToken>

    /**
     * Revokes a live service key of an organization, inside that organization.
     *
     * @param orgId The organization.
     * @param id The key.
     * @param at When.
     *
     * @return True when a live key was revoked; false when it is absent, another organization's, not a service key,
     *   expired or already revoked.
     */
    suspend fun revoke(orgId: Uuid, id: Uuid, at: Instant): Boolean
}

/** Device authorizations of `ostrom login`. */
interface DeviceAuthorizationsRepository {
    /**
     * Stores a new authorization.
     *
     * @param authorization The authorization.
     * @param deviceCodeHash The SHA-256 of the device code.
     */
    suspend fun create(authorization: DeviceAuthorization, deviceCodeHash: String)

    /**
     * Finds an authorization by its device code.
     *
     * @param deviceCodeHash The SHA-256 of the device code.
     *
     * @return The authorization, or null.
     */
    suspend fun findByDeviceCodeHash(deviceCodeHash: String): DeviceAuthorization?

    /**
     * Finds a pending authorization by its user code.
     *
     * @param userCode The normalized user code.
     * @param now The current time: expired ones are not returned.
     *
     * @return The authorization, or null.
     */
    suspend fun findPendingByUserCode(userCode: String, now: Instant): DeviceAuthorization?

    /**
     * Answers a pending authorization.
     *
     * @param id The authorization.
     * @param userId The user who answers.
     * @param approved True to approve, false to deny.
     * @param now The current time: an expired one is not answered.
     *
     * @return True when it was pending and is now answered.
     */
    suspend fun answer(id: Uuid, userId: Uuid, approved: Boolean, now: Instant): Boolean

    /**
     * Records a poll.
     *
     * @param id The authorization.
     * @param at When.
     */
    suspend fun markPolled(id: Uuid, at: Instant)

    /**
     * Marks an approved authorization consumed and stores the token it gave, in one transaction: two concurrent
     * polls never both get a token.
     *
     * @param id The authorization.
     * @param token The CLI token.
     * @param secretHash The SHA-256 of its secret.
     *
     * @return True when this call consumed it.
     */
    suspend fun consume(id: Uuid, token: ApiToken, secretHash: String): Boolean
}

/** The signed, chained audit log. */
interface AuditRepository {
    /**
     * Appends an event to a chain. The chain is locked for the transaction, so concurrent appends are ordered.
     *
     * @param orgId The organization of the chain, or null for the account-level chain.
     * @param build Builds the event from the previous event's hash (null for the first) and the next sequence.
     *
     * @return The stored event.
     */
    suspend fun append(orgId: Uuid?, build: suspend (previousHash: String?, sequence: Long) -> AuditEvent): AuditEvent

    /**
     * Lists the events of a chain, newest first.
     *
     * @param orgId The organization, or null for the account-level chain.
     * @param actorId For the account-level chain (required there), only this user's events; ignored for an
     *   organization.
     * @param beforeSequence Only events before this sequence (keyset pagination), or null for the newest.
     * @param limit At most this many (1 to 200).
     *
     * @return The events.
     */
    suspend fun list(orgId: Uuid?, actorId: Uuid?, beforeSequence: Long?, limit: Int): List<AuditEvent>
}
