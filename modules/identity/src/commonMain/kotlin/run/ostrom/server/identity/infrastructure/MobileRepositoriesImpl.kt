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
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [MobileCodesRepository] on PostgreSQL (`identity_mobile_codes`, migration 5).
 *
 * @param driver The database.
 */
class MobileCodesRepositoryImpl(private val driver: Driver) : MobileCodesRepository {
    override suspend fun create(code: MobileCode, codeHash: String) {
        driver.execute(
            Statement.create(
                "INSERT INTO identity_mobile_codes (id, code_hash, user_id, code_challenge, redirect_uri, " +
                    "created_at, expires_at) VALUES (:id, :hash, :user, :challenge, :uri, :created, :expires)",
            ).bind("id", code.id).bind("hash", codeHash).bind("user", code.userId)
                .bind("challenge", code.codeChallenge).bind("uri", code.redirectUri)
                .bind("created", code.createdAt).bind("expires", code.expiresAt),
        ).getOrThrow()
    }

    override suspend fun consume(codeHash: String, now: Instant): MobileCode? = driver.fetchAll(
        // One statement marks it used and returns it: two exchanges of the same code cannot both get it.
        Statement.create(
            "UPDATE identity_mobile_codes SET used_at = :now WHERE code_hash = :hash AND used_at IS NULL " +
                "AND expires_at > :now RETURNING id, user_id, code_challenge, redirect_uri, created_at, expires_at",
        ).bind("hash", codeHash).bind("now", now),
    ).getOrThrow().firstOrNull()?.let {
        MobileCode(
            id = it.get("id").asUuid(),
            userId = it.get("user_id").asUuid(),
            codeChallenge = it.get("code_challenge").asString(),
            redirectUri = it.get("redirect_uri").asString(),
            createdAt = it.get("created_at").asInstant(),
            expiresAt = it.get("expires_at").asInstant(),
        )
    }
}

/**
 * [MobileTokensRepository] on PostgreSQL: the app's tokens are rows of `identity_api_tokens` with an unlock key.
 *
 * @param driver The database.
 */
class MobileTokensRepositoryImpl(private val driver: Driver) : MobileTokensRepository {
    override suspend fun create(token: ApiToken, secretHash: String, unlockKeyHash: String) =
        insert(driver, token, secretHash, unlockKeyHash)

    override suspend fun unlock(
        tokenId: Uuid,
        userId: Uuid,
        unlockKeyHash: String,
        at: Instant,
        requestId: String?,
    ): Boolean = driver.execute(
        Statement.create(
            "UPDATE identity_api_tokens SET authenticated_at = :at, unlock_request_id = :request WHERE id = :id " +
                "AND user_id = :user AND kind = :kind AND unlock_key_hash = :unlock AND revoked_at IS NULL " +
                "AND (expires_at IS NULL OR expires_at > :at)",
        ).bind("at", at).bind("request", requestId).bind("id", tokenId).bind("user", userId)
            .bind("kind", TokenKind.MOBILE.name).bind("unlock", unlockKeyHash),
    ).getOrThrow() == 1L
}

/**
 * [PhoneUnlockProofs] on the app's tokens (`identity_api_tokens.unlock_request_id`, migration 16): one statement
 * checks and clears the binding, by the token's primary key, so two yeses sent at once cannot both use it.
 *
 * @param driver The database.
 */
class PhoneUnlockProofsImpl(private val driver: Driver) : PhoneUnlockProofs {
    override suspend fun consume(credentialId: String, requestId: String, since: Instant): Boolean {
        val id = Uuid.parseOrNull(credentialId) ?: return false
        return driver.execute(
            Statement.create(
                "UPDATE identity_api_tokens SET unlock_request_id = NULL WHERE id = :id AND kind = :kind " +
                    "AND unlock_request_id = :request AND authenticated_at >= :since AND revoked_at IS NULL",
            ).bind("id", id).bind("kind", TokenKind.MOBILE.name).bind("request", requestId).bind("since", since),
        ).getOrThrow() == 1L
    }
}

/**
 * [NotificationPreferencesRepository] on PostgreSQL (`identity_notification_preferences`, migration 5).
 *
 * @param driver The database.
 */
class NotificationPreferencesRepositoryImpl(private val driver: Driver) : NotificationPreferencesRepository {
    override suspend fun get(userId: Uuid): NotificationPreferences? = driver.fetchAll(
        Statement.create(
            "SELECT requests, decisions, spending, quiet_start, quiet_end, quiet_time_zone " +
                "FROM identity_notification_preferences WHERE user_id = :user",
        ).bind("user", userId),
    ).getOrThrow().firstOrNull()?.let {
        NotificationPreferences(
            requests = it.get("requests").asBoolean(),
            decisions = it.get("decisions").asBoolean(),
            spending = it.get("spending").asBoolean(),
            quietHours = it.get("quiet_start").asStringOrNull()?.let { start ->
                QuietHours(start, it.get("quiet_end").asString(), it.get("quiet_time_zone").asString())
            },
        )
    }

    override suspend fun put(userId: Uuid, preferences: NotificationPreferences, at: Instant) {
        val quiet = preferences.quietHours
        driver.execute(
            Statement.create(
                "INSERT INTO identity_notification_preferences (user_id, requests, decisions, spending, quiet_start, " +
                    "quiet_end, quiet_time_zone, updated_at) VALUES (:user, :requests, :decisions, :spending, " +
                    ":start, :end, :zone, :at) ON CONFLICT (user_id) DO UPDATE SET requests = EXCLUDED.requests, " +
                    "decisions = EXCLUDED.decisions, spending = EXCLUDED.spending, " +
                    "quiet_start = EXCLUDED.quiet_start, quiet_end = EXCLUDED.quiet_end, " +
                    "quiet_time_zone = EXCLUDED.quiet_time_zone, updated_at = EXCLUDED.updated_at",
            ).bind("user", userId).bind("requests", preferences.requests).bind("decisions", preferences.decisions)
                .bind("spending", preferences.spending).bind("start", quiet?.start).bind("end", quiet?.end)
                .bind("zone", quiet?.timeZone).bind("at", at),
        ).getOrThrow()
    }
}
