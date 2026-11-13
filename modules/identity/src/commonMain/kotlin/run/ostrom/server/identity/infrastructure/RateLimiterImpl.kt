// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.RateLimitWindows
import run.ostrom.server.identity.domain.RateLimiter
import run.ostrom.server.identity.domain.SecretHasher
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.time.Duration

/**
 * [RateLimiter] on PostgreSQL (`identity_rate_limits`, [IdentityMigrations] version 18): fixed windows shared by every
 * replica, so the limit is the configured one however many replicas serve, and timed by the database's clock, so a
 * replica's clock never moves a window. An attempt is one statement, under the key's row lock: two replicas counting
 * the same key take turns. The keys are stored hashed ([SecretHasher]): an e-mail address or a client address never
 * lands in the table.
 *
 * @param driver The database, as the serving role (the table is no organization's).
 * @param hasher Hashes the keys.
 */
class RateLimiterImpl(private val driver: Driver, private val hasher: SecretHasher) : RateLimiter, RateLimitWindows {
    override suspend fun tryAcquire(key: String, limit: Int, window: Duration): Boolean {
        val statement = Statement.create(RATE_LIMIT_ATTEMPT_SQL)
            .bind("key", hasher.hash(key))
            .bind("seconds", window.inWholeSeconds.coerceAtLeast(1))
        val attempts = driver.fetchAll(statement).getOrThrow().single().get("attempts").asLong()
        return attempts <= limit
    }

    override suspend fun purgeExpired(): Long =
        driver.fetchAll(Statement.create(RATE_LIMIT_PURGE_SQL)).getOrThrow().single().get("purged").asLong()
}

/**
 * One attempt on a key: the first of a window opens it for `:seconds` from now (database clock) and counts 1; the next
 * ones before its end add 1; the first after its end opens a new one. The count after this attempt is returned.
 */
internal const val RATE_LIMIT_ATTEMPT_SQL =
    "INSERT INTO identity_rate_limits (key_hash, attempts, ends_at) " +
        "VALUES (:key, 1, now() + make_interval(secs => :seconds)) " +
        "ON CONFLICT (key_hash) DO UPDATE SET " +
        "attempts = CASE WHEN identity_rate_limits.ends_at <= now() THEN 1 " +
        "ELSE identity_rate_limits.attempts + 1 END, " +
        "ends_at = CASE WHEN identity_rate_limits.ends_at <= now() THEN EXCLUDED.ends_at " +
        "ELSE identity_rate_limits.ends_at END " +
        "RETURNING attempts"

/** The windows already over, which no attempt reads again. */
internal const val RATE_LIMIT_PURGE_SQL =
    "WITH gone AS (DELETE FROM identity_rate_limits WHERE ends_at <= now() RETURNING 1) " +
        "SELECT count(*) AS purged FROM gone"
