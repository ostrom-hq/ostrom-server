// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.MobileCode
import run.ostrom.server.identity.domain.MobileCodesRepository
import run.ostrom.server.identity.domain.MobileTokensRepository
import run.ostrom.server.identity.domain.NotificationPreferences
import run.ostrom.server.identity.domain.NotificationPreferencesRepository
import run.ostrom.server.identity.domain.QuietHours
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.kernel.auth.PhoneUnlockProofs
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MobileCodesRepository] on PostgreSQL through Exposed ([IdentityMobileCodes], migration 5).
 *
 * @param database The JDBC pool.
 */
class MobileCodesRepositoryImpl(private val database: JdbcDatabase) : MobileCodesRepository {
    private val t = IdentityMobileCodes

    override suspend fun create(code: MobileCode, codeHash: String) {
        database.query {
            t.insert {
                it[id] = code.id
                it[this.codeHash] = codeHash
                it[userId] = code.userId
                it[codeChallenge] = code.codeChallenge
                it[redirectUri] = code.redirectUri
                it[createdAt] = code.createdAt
                it[expiresAt] = code.expiresAt
            }
        }
    }

    // One statement marks it used and returns it: two exchanges of the same code cannot both get it.
    override suspend fun consume(codeHash: String, now: Instant): MobileCode? = database.query {
        t.updateReturning(
            listOf(t.id, t.userId, t.codeChallenge, t.redirectUri, t.createdAt, t.expiresAt),
            where = { (t.codeHash eq codeHash) and t.usedAt.isNull() and (t.expiresAt greater now) },
        ) { it[usedAt] = now }.singleOrNull()?.let {
            MobileCode(
                id = it[t.id],
                userId = it[t.userId],
                codeChallenge = it[t.codeChallenge],
                redirectUri = it[t.redirectUri],
                createdAt = it[t.createdAt],
                expiresAt = it[t.expiresAt],
            )
        }
    }
}

/**
 * [MobileTokensRepository] on PostgreSQL through Exposed: the app's tokens are rows of `identity_api_tokens` with an
 * unlock key, no organization's.
 *
 * @param database The JDBC pool.
 */
class MobileTokensRepositoryImpl(private val database: JdbcDatabase) : MobileTokensRepository {
    private val t = IdentityApiTokens

    override suspend fun create(token: ApiToken, secretHash: String, unlockKeyHash: String) {
        database.query { insertToken(token, secretHash, unlockKeyHash) }
    }

    override suspend fun unlock(
        tokenId: Uuid,
        userId: Uuid,
        unlockKeyHash: String,
        at: Instant,
        requestId: String?,
    ): Boolean = database.query {
        t.update({
            (t.id eq tokenId) and (t.userId eq userId) and (t.kind eq TokenKind.MOBILE.name) and
                (t.unlockKeyHash eq unlockKeyHash) and t.revokedAt.isNull() and
                (t.expiresAt.isNull() or (t.expiresAt greater at))
        }) {
            it[authenticatedAt] = at
            it[unlockRequestId] = requestId
        } == 1
    }
}

/**
 * [PhoneUnlockProofs] on the app's tokens (`identity_api_tokens.unlock_request_id`, migration 16): one statement
 * checks and clears the binding, by the token's primary key, so two yeses sent at once cannot both use it.
 *
 * @param database The JDBC pool.
 */
class PhoneUnlockProofsImpl(private val database: JdbcDatabase) : PhoneUnlockProofs {
    override suspend fun consume(credentialId: String, requestId: String, since: Instant): Boolean {
        val id = Uuid.parseOrNull(credentialId) ?: return false
        val t = IdentityApiTokens
        return database.query {
            t.update({
                (t.id eq id) and (t.kind eq TokenKind.MOBILE.name) and (t.unlockRequestId eq requestId) and
                    (t.authenticatedAt greaterEq since) and t.revokedAt.isNull()
            }) { it[unlockRequestId] = null } == 1
        }
    }
}

/**
 * [NotificationPreferencesRepository] on PostgreSQL through Exposed ([IdentityNotificationPreferences], migration 5).
 *
 * @param database The JDBC pool.
 */
class NotificationPreferencesRepositoryImpl(private val database: JdbcDatabase) : NotificationPreferencesRepository {
    private val t = IdentityNotificationPreferences

    override suspend fun get(userId: Uuid): NotificationPreferences? = database.query {
        t.select(t.requests, t.decisions, t.spending, t.quietStart, t.quietEnd, t.quietTimeZone)
            .where { t.userId eq userId }
            .singleOrNull()
            ?.let { row ->
                NotificationPreferences(
                    requests = row[t.requests],
                    decisions = row[t.decisions],
                    spending = row[t.spending],
                    // The three are written together: all set, or none.
                    quietHours = row[t.quietStart]?.let { start ->
                        QuietHours(start, row.present(t.quietEnd), row.present(t.quietTimeZone))
                    },
                )
            }
    }

    override suspend fun put(userId: Uuid, preferences: NotificationPreferences, at: Instant) {
        val quiet = preferences.quietHours
        database.query {
            // ON CONFLICT (user_id) DO UPDATE: every column takes the new value.
            t.upsert {
                it[this.userId] = userId
                it[requests] = preferences.requests
                it[decisions] = preferences.decisions
                it[spending] = preferences.spending
                it[quietStart] = quiet?.start
                it[quietEnd] = quiet?.end
                it[quietTimeZone] = quiet?.timeZone
                it[updatedAt] = at
            }
        }
    }
}
